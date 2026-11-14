package dev.modmind.irontide.raid;

import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.block.CoreState;
import dev.modmind.irontide.config.CoreConfig;
import dev.modmind.irontide.config.RaidConfig;
import dev.modmind.irontide.data.IronTideData;
import dev.modmind.irontide.net.SteamHudPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 怪潮调度器（设计 §2.4）。
 * <p>
 * 时间轴：第 4 天预警 → 第 5 天夜进攻；`cycleDays=5`，每大波 3 小波。时间完全由世界时间驱动，
 * 不依赖玩家在线；怪物类型与波次数值全部来自 {@code config/irontide/raids.json}。
 * <p>
 * 首版范围：刷怪 + 攻核 + 天亮结算。词缀、信标完整度、干预（第 9 天削弱词缀）留后续切片。
 */
public final class RaidScheduler {
    private static final int CHECK_INTERVAL = 20;
    /** 收尾发光每次挂 3 秒，靠每秒一次的 tickActive 续上。 */
    private static final int GLOW_DURATION_TICKS = 60;

    /** 每大波固定 3 小波（设计 §波次）。 */
    public static final int SUBWAVES_PER_BIG_WAVE = 3;

    private static int counter;
    private static int lastDay = Integer.MIN_VALUE;
    private static int cycleDay = 1;

    private static boolean warnedThisCycle;
    private static boolean scoutedThisCycle;
    /** 本夜已经开打过（或判定打不起来）。天亮清零，避免 endRaid 之后下一 tick 又重开、聊天栏刷屏。 */
    private static boolean raidEndedThisNight;

    private static boolean active;
    private static int waveIndex = 1;
    /** 当前小波序号（1..3）；大波是 {@link #waveIndex}。 */
    private static int subWave = 1;
    private static int targetCount;
    private static int spawnedCount;
    private static int spawnCooldown;
    /** 下一次全局同步脉冲还剩多少 tick（100 = 5 秒）。 */
    private static int attackPulseCooldown;
    /** 小波之间的间隔倒计时（tick）；>0 时不刷怪。 */
    private static int waveGapCooldown;
    /** 上一次脉冲播报的 gameTime（90 秒冷却）。 */
    private static long lastPulseAnnounce = Long.MIN_VALUE;

    /** 受伤脉搏序号：每结算一次攻核 +1，随 HUD 下发，客户端靠它触发一次闪红。 */
    private static int hitPulseSeq;
    /** 发光扫描节流：20 tick 主循环里每 2 次做一次（即 2 秒）。 */
    private static int glowCounter;

    /** 卡怪跟踪：最近一次「有进展」的 tick + 历史最佳水平距离 + 已重刷次数。 */
    private record StuckTrack(double bestDistance, long lastProgressTick, int respawns) {
    }

    /** 本 tick 的领地半径表：随主核档位缩放（W7-B7）。默认 T4，开战时按实际核心覆盖。 */
    private static TerritoryScale.Ring ring = TerritoryScale.of(4, 0);

    private static final Map<UUID, StuckTrack> stuckTracks = new HashMap<>();

    private static final Set<UUID> raiders = new HashSet<>();
    /** 本波的主动方向（弧度）。一波怪只从这个扇面里出来，不再是四面八方随机。 */
    private static double spawnAngle;
    /** 本大波预掷的 3 个小波方位（预警夜掷定并缓存）。 */
    private static final double[] plannedAngles = new double[SUBWAVES_PER_BIG_WAVE];
    private static boolean anglesPlanned;
    private static BlockPos corePos;
    private static ServerLevel coreLevel;
    private static int coreDimId = Integer.MIN_VALUE;
    /** 本次刷怪的锚点（主核，或同向前哨 8 格外）。 */
    private static BlockPos spawnCenter;
    /** 同向前哨；不为 null 时刷怪锚点挂在它身上。 */
    private static AndesiteCoreBlockEntity spawnOutpost;
    /** 本波已播报过的接替锦点：接替逻辑被重复触发时不再刷屏。 */
    private static BlockPos announcedAnchor;
    /** 本 tick 的核心网络（主核 + 前哨），承伤/寻路/拆墙都看这张表。 */
    private static List<AndesiteCoreBlockEntity> network = List.of();
    /** 开战时网络内所有承伤核心的血量上限总和（含主核）；结算比例 = 当前总血量 ÷ 它。 */
    private static int coreMaxTotal;

    private RaidScheduler() {
    }

    public static boolean isActive() {
        return active;
    }

    public static int currentWave() {
        return waveIndex;
    }

    /** 该实体是否是本波怪潮刷出来的（用于掉落判定）。 */
    public static boolean isRaider(UUID uuid) {
        return active && uuid != null && raiders.contains(uuid);
    }

    /** 波特率或存档卸载时清空全部波次状态（包括领地视觉与拆墙进度）。 */
    public static void reset() {
        counter = 0;
        lastDay = Integer.MIN_VALUE;
        cycleDay = 1;
        warnedThisCycle = false;
        scoutedThisCycle = false;
        raidEndedThisNight = false;
        active = false;
        waveIndex = 1;
        subWave = 1;
        targetCount = 0;
        spawnedCount = 0;
        spawnCooldown = 0;
        attackPulseCooldown = 0;
        waveGapCooldown = 0;
        lastPulseAnnounce = Long.MIN_VALUE;
        raiders.clear();
        stuckTracks.clear();
        glowCounter = 0;
        ring = TerritoryScale.of(4, 0);
        spawnAngle = 0.0D;
        anglesPlanned = false;
        SafeZone.deactivate(coreLevel);
        ZoneVisuals.deactivate();
        RaidBreaker.reset();
        RaidFiller.resetWave();
        corePos = null;
        coreLevel = null;
        coreDimId = Integer.MIN_VALUE;
        spawnCenter = null;
        spawnOutpost = null;
        announcedAnchor = null;
        network = List.of();
        coreMaxTotal = 0;
        RaidPerf.reset();
    }

    public static void tick(MinecraftServer server) {
        if (!IronTideData.isLoaded()) {
            return;
        }
        if (++counter < CHECK_INTERVAL) {
            return;
        }
        counter = 0;

        RaidConfig config = IronTideData.raidConfig();
        ServerLevel overworld = server.overworld();
        long dayTime = overworld.getDayTime();
        int day = (int) (dayTime / 24000L);
        boolean night = (dayTime % 24000L) >= 13000L;

        if (day != lastDay) {
            onNewDay(server, config, day);
        }

        if (!night) {
            raidEndedThisNight = false;
        }

        if (active) {
            tickActive(server, config, night);
        } else if (cycleDay == config.attackDay() && night && !raidEndedThisNight) {
            startRaid(server, config, day);
        }
        syncHud(server);
    }

    /** 每 20 tick 把核心蒸汽值推给所有在线玩家；不在怪潮时也发，用来让客户端把 HUD 收起来。 */
    private static void syncHud(MinecraftServer server) {
        int health = 0;
        int maxHealth = 0;
        if (active) {
            AndesiteCoreBlockEntity core = resolveRaidCore();
            if (core == null) {
                core = pickCore();
            }
            if (core != null) {
                // HUD 显示最吃紧的承伤核心（主核现在也有血，所以可能是主核自己）
                AndesiteCoreBlockEntity display = core;
                List<AndesiteCoreBlockEntity> targets = damageTargets(core);
                if (!targets.isEmpty()) {
                    display = lowestRatio(targets);
                }
                health = display.getHealth();
                maxHealth = display.getHealthMax();
            }
        }
        SteamHudPayload payload = new SteamHudPayload(
                health, maxHealth, active ? waveIndex : 0, raiders.size(),
                daysToNextWave(),
                active ? subWave : 0,
                active ? spawnedCount : 0,
                active ? targetCount : 0,
                active && spawnedCount < targetCount ? Math.max(0, spawnCooldown * CHECK_INTERVAL) : 0,
                active ? Math.max(0, waveGapCooldown) : 0,
                hitPulseSeq,
                active);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * 距下一次大波还有几天：{@code attackDay - cycleDay}，当天就是 0（今晚）。
     * 没有任何核心、也不在怪潮时返回 -1，客户端就只显示「状态未知」。
     */
    private static int daysToNextWave() {
        if (!active && pickCore() == null) {
            return -1;
        }
        RaidConfig config = IronTideData.raidConfig();
        return Math.max(0, config.attackDay() - cycleDay);
    }

    private static void onNewDay(MinecraftServer server, RaidConfig config, int day) {
        lastDay = day;
        cycleDay = Math.floorMod(day, config.cycleDays()) + 1;
        waveIndex = day / config.cycleDays() + 1;

        if (cycleDay == 1) {
            warnedThisCycle = false;
            scoutedThisCycle = false;
        }

        if (cycleDay == config.warnDay() && !warnedThisCycle) {
            warnedThisCycle = true;
            // 方位在预警夜掷定并缓存；开战时直接复用，不再重新抽
            rollAngles(server.overworld(), config, waveIndex);
            String side = directionLabel(plannedAngles[0]);
            boolean early = waveIndex <= config.early().singleSideBigWaves();
            String detail = early
                    ? "本夜三小波同向而来，主攻方位§e " + side + "§e。"
                    : "本夜起每小波独立掷向（大波 " + config.early().singleSideBigWaves()
                    + " 之后），首波方位§e " + side + "§e。";
            announce(server, "§6[钢铁潮汐] §e第 " + config.warnDay() + " 天：地平线传来震动。怪潮将在第 "
                    + config.attackDay() + " 天夜间抵达，共 3 小波。" + detail);
        }
        if (cycleDay == config.scoutDay() && !scoutedThisCycle) {
            scoutedThisCycle = true;
            RaidConfig.Wave wave = config.wave(waveIndex);
            announce(server, "§6[钢铁潮汐] §e第 " + config.scoutDay() + " 天：侦察确认 —— 第 "
                    + waveIndex + " 波，约 " + config.effectiveBase(waveIndex) + " 个目标/小波，词缀 " + wave.affixes()
                    + " 条（最高 " + roman(wave.maxAffixLevel()) + " 级）。明晚进攻。");
        }

        // A1：每天早上播报一次倒计时（与预警/侦察同天就多一行，但不重复每天多次）
        int daysLeft = Math.max(0, config.attackDay() - cycleDay);
        if (daysLeft > 0) {
            announce(server, "§6[钢铁潮汐] §e第 " + day + " 天清晨：距下一次怪潮还有 §c" + daysLeft
                    + "§e 天（第 " + config.attackDay() + " 天夜间）。");
        } else {
            announce(server, "§6[钢铁潮汐] §c第 " + day + " 天清晨：今晚怪潮将至，请加固防线。");
        }
    }

    /** 预掷本次大波的 3 个小波方位：早期大波单侧，之后每小波独立随机。 */
    private static void rollAngles(ServerLevel level, RaidConfig config, int bigWave) {
        boolean early = bigWave <= config.early().singleSideBigWaves();
        double side = bigWave == 1
                ? sideAngle(config.early().firstBigWaveSide())
                : level.random.nextDouble() * Math.PI * 2.0D;
        for (int i = 0; i < SUBWAVES_PER_BIG_WAVE; i++) {
            plannedAngles[i] = early ? side : level.random.nextDouble() * Math.PI * 2.0D;
        }
        anglesPlanned = true;
    }

    /** 配置里的方位词换成弧度（MC 里 +X 是东、+Z 是南）。 */
    private static double sideAngle(String side) {
        if (side == null) {
            return Math.PI;
        }
        return switch (side.toLowerCase(java.util.Locale.ROOT)) {
            case "east" -> 0.0D;
            case "south" -> Math.PI * 0.5D;
            case "north" -> Math.PI * 1.5D;
            default -> Math.PI;
        };
    }

    private static void startRaid(MinecraftServer server, RaidConfig config, int day) {
        AndesiteCoreBlockEntity core = pickCore();
        if (core == null) {
            raidEndedThisNight = true;
            return;
        }
        if (core.getState() != CoreState.ACTIVE) {
            raidEndedThisNight = true;
            announce(server, "§6[钢铁潮汐] §7核心未运转，今晚怪潮暂缓。");
            return;
        }
        if (!(core.getLevel() instanceof ServerLevel level)) {
            raidEndedThisNight = true;
            return;
        }
        corePos = core.getBlockPos();
        coreLevel = level;
        coreDimId = level.dimension().location().hashCode();
        announcedAnchor = null;
        active = true;
        subWave = 1;
        waveGapCooldown = 0;
        // 预警夜没跑（比如改了 warnDay）时开战补掷；否则直接用缓存的方位
        if (!anglesPlanned) {
            rollAngles(level, config, waveIndex);
        }
        RaidPerf.begin(server);
        beginSubWave(server, config, 1);
    }

    /** 开一只小波：重置计数、选方位/锚点、刷第一波、开播报。 */
    private static void beginSubWave(MinecraftServer server, RaidConfig config, int index) {
        if (coreLevel == null || corePos == null) {
            return;
        }
        if (!(coreLevel.getBlockEntity(corePos) instanceof AndesiteCoreBlockEntity core) || core.isRemoved()) {
            endRaid(server, "核心已不存在，怪潮散去。");
            return;
        }
        subWave = Math.max(1, Math.min(SUBWAVES_PER_BIG_WAVE, index));
        if (!anglesPlanned) {
            rollAngles(coreLevel, config, waveIndex);
        }
        spawnAngle = plannedAngles[subWave - 1];

        int online = Math.max(1, server.getPlayerCount());
        // GearMul 按核心实际档位缩放（设计 §2.4）：T1 → 1.00，最高档 → 1 + gearMulMax
        CoreConfig coreConfig = IronTideData.config();
        int maxTier = Math.max(1, coreConfig.tiers().size());
        int coreTier = Math.max(1, Math.min(maxTier, core.tierIndex()));
        targetCount = config.scaledSize(waveIndex, online, coreTier, maxTier);
        spawnedCount = 0;
        raiders.clear();
        spawnCooldown = 0;
        attackPulseCooldown = Math.max(1, config.attackIntervalTicks());
        waveGapCooldown = 0;

        network = CoreNetwork.members(core, coreConfig.networkRadius());
        coreMaxTotal = totalCoreMax(core);
        RaidBreaker.reset();
        RaidFiller.resetWave();
        refreshRing(core);
        refreshSpawnAnchor(config);

        RaidConfig.SafeZone zone = config.safeZone();
        if (zone.enabled()) {
            SafeZone.activate(coreLevel, corePos, ring.protectedRadius(), zone.halfHeight(),
                    zone.lightUp() ? zone.lightLevel() : 0, zone.beacons(), zone.particleIntervalTicks(),
                    zone.shape());
        } else {
            SafeZone.deactivate(coreLevel);
        }
        ZoneVisuals.activate(corePos, ring);

        String anchorText = spawnOutpost == null ? "主核方形环 45–60 格" : "同向前哨 8 格外";
        announce(server, "§4[钢铁潮汐] §c怪潮第 " + waveIndex + " 大波 · 小波 " + subWave + "/"
                + SUBWAVES_PER_BIG_WAVE + " 开始！§7目标：安山核心（" + targetCount + " 个来袭），来自§e "
                + directionLabel(spawnAngle) + "§7 侧，刷怪锚点：" + anchorText + "。"
                + protectedOutpostWarning(core) + "守住它直到天亮。");
    }

    /** 刷怪锚点：同向且可打的前哨优先（8 格外）；否则回主核方形环。 */
    /**
     * 按主核档位刷新领地半径（W7-B7）。档位变了就把旧墙到新墙之间的怪推到新边界外，
     * 避免升级时怪被关在扩大后的保护区/墙内。降档同理。
     */
    private static void refreshRing(AndesiteCoreBlockEntity core) {
        if (core == null) {
            return;
        }
        TerritoryScale.Ring next = TerritoryScale.of(core.tierIndex(), IronTideData.raidConfig());
        if (next.tier() == ring.tier() && next.protectedRadius() == ring.protectedRadius()) {
            return;
        }
        ring = next;
        if (coreLevel != null) {
            for (UUID uuid : raiders) {
                Entity entity = coreLevel.getEntity(uuid);
                if (entity != null && entity.isAlive()) {
                    Territory.clampToWall(entity, corePos, ring.wallLimit());
                }
            }
        }
    }

    private static void refreshSpawnAnchor(RaidConfig config) {
        spawnCenter = corePos;
        spawnOutpost = null;
        if (corePos == null) {
            return;
        }
        double allowed = Math.toRadians(Math.max(0, Math.min(180, config.outpost().sameSideDegrees())));
        int protectedRadius = ring.protectedRadius();
        for (AndesiteCoreBlockEntity member : network) {
            if (member == null || member.getBlockPos().equals(corePos)) {
                continue;
            }
            if (Territory.radius(corePos, member.getBlockPos()) <= protectedRadius) {
                continue;
            }
            double angle = Math.atan2(member.getBlockPos().getZ() - corePos.getZ(),
                    member.getBlockPos().getX() - corePos.getX());
            if (angleDiff(angle, spawnAngle) <= allowed) {
                spawnOutpost = member;
                spawnCenter = member.getBlockPos();
                return;
            }
        }
    }

    /** 两个弧度之间的最小夹角（0..π）。 */
    private static double angleDiff(double a, double b) {
        double diff = Math.abs(a - b) % (Math.PI * 2.0D);
        return diff > Math.PI ? Math.PI * 2.0D - diff : diff;
    }

    /** 开战时提醒：网络里有前哨落在主核保护区（r≤26）时，本波它不承伤。 */
    private static String protectedOutpostWarning(AndesiteCoreBlockEntity anchor) {
        int protectedRadius = ring.protectedRadius();
        for (AndesiteCoreBlockEntity member : network) {
            if (member != null && member != anchor
                    && Territory.radius(anchor.getBlockPos(), member.getBlockPos()) <= protectedRadius) {
                return "§7（前哨位于保护区内，本波无法攻击）";
            }
        }
        return "";
    }

    private static void tickActive(MinecraftServer server, RaidConfig config, boolean night) {
        AndesiteCoreBlockEntity core = resolveRaidCore();
        if (core == null || core.getState() != CoreState.ACTIVE) {
            // 主核被毁/停机时，让网络里还活着的核心接替锚点，怪潮继续；全没了才收场。
            AndesiteCoreBlockEntity successor = successorCore(network, core);
            if (successor == null) {
                endRaid(server, core == null ? "核心已不存在，怪潮散去。" : "核心停机，怪潮失去目标。");
                return;
            }
            core = promoteCore(server, successor);
        }
        if (!(core.getLevel() instanceof ServerLevel level)) {
            endRaid(server, "核心所在维度不可用，怪潮散去。");
            return;
        }

        raiders.removeIf(uuid -> {
            Entity entity = level.getEntity(uuid);
            return entity == null || !entity.isAlive();
        });

        network = CoreNetwork.members(core, IronTideData.config().networkRadius());
        List<AndesiteCoreBlockEntity> targets = damageTargets(core);
        if (targets.isEmpty()) {
            endRaid(server, "§c核心网络全部失守，怪潮散去。");
            return;
        }
        refreshRing(core);
        steerRaiders(level, targets);
        cullFarRaiders(level);
        if (++glowCounter >= 2) {
            glowCounter = 0;
            highlightLeftovers(level, config);
        }
        RaidPerf.sample(server, raiders.size());

        // 全局同步脉冲：一个调度器级别的时钟，只在脉冲瞬间结算；
        // 平均 DPS 由 damagePerSecond/dpsCap 决定，intervalTicks 只影响节奏。
        attackPulseCooldown -= CHECK_INTERVAL;
        if (attackPulseCooldown <= 0) {
            attackPulseCooldown = Math.max(1, config.attackIntervalTicks());
            harvestCoreDamage(server, level, config, targets, core);
            if (damageTargets(core).isEmpty()) {
                endRaid(server, "§c核心网络全部失守，怪潮散去。");
                return;
            }
        }

        SafeZone.tick(level);

        if (!night) {
            endRaid(server, "§a天亮了，怪潮退去。");
            return;
        }

        // 本小波刷完且清空：等 waveGapTicks 开下一小波；三小波打完才结束本夜
        boolean cleared = spawnedCount >= targetCount && raiders.isEmpty();
        if (cleared) {
            if (subWave >= SUBWAVES_PER_BIG_WAVE) {
                endRaid(server, "§a本夜三小波全部清空。");
                return;
            }
            if (waveGapCooldown <= 0) {
                waveGapCooldown = Math.max(CHECK_INTERVAL, config.spawnWaveGapTicks());
                announce(server, "§6[钢铁潮汐] §7第 " + subWave + " 小波已清空，下一小波将在 "
                        + (config.spawnWaveGapTicks() / 20) + " 秒后抵达。");
            }
            waveGapCooldown -= CHECK_INTERVAL;
            if (waveGapCooldown <= 0) {
                beginSubWave(server, config, subWave + 1);
            }
            return;
        }

        // 同时存活上限：到顶就暂停刷怪，等清掉一批再继续（气氛更连续，也不至于几十只同屏）
        boolean aliveAtCap = config.spawnMaxAlive() > 0 && raiders.size() >= config.spawnMaxAlive();
        if (spawnedCount < targetCount && !aliveAtCap) {
            if (--spawnCooldown <= 0) {
                spawnCooldown = Math.max(1, config.spawnIntervalTicks() / CHECK_INTERVAL);
                int burst = Math.min(config.spawnPerBurst(), targetCount - spawnedCount);
                for (int i = 0; i < burst; i++) {
                    if (spawnOne(level, config, i % 4 == 3)) {
                        spawnedCount++;
                    }
                }
            }
        }
    }

    /** 怪潮锚定的核心：只认开战时那个坐标上的核心，避免跨网络跳到别处的核心。 */
    private static AndesiteCoreBlockEntity resolveRaidCore() {
        if (coreLevel == null || corePos == null) {
            return null;
        }
        return coreLevel.getBlockEntity(corePos) instanceof AndesiteCoreBlockEntity core && !core.isRemoved()
                ? core : null;
    }

    /** 网络里还活着、且不是当前锚点的核心；主核失守后由它接替。 */
    private static AndesiteCoreBlockEntity successorCore(List<AndesiteCoreBlockEntity> members,
                                                         AndesiteCoreBlockEntity current) {
        for (AndesiteCoreBlockEntity member : members) {
            if (member == null || member == current) {
                continue;
            }
            if (member.getLevel() != coreLevel) {
                continue;
            }
            if (member.isRemoved() || member.getLevel().getBlockEntity(member.getBlockPos()) != member) {
                continue;
            }
            if (member.getState() != CoreState.ACTIVE || member.getHealth() <= 0) {
                continue;
            }
            return member;
        }
        return null;
    }

    /** 把锚点切到接替的前哨：刷怪、承伤、结算都跟着新核心走。 */
    private static AndesiteCoreBlockEntity promoteCore(MinecraftServer server, AndesiteCoreBlockEntity successor) {
        BlockPos next = successor.getBlockPos();
        boolean moved = !next.equals(corePos);
        corePos = next;
        if (successor.getLevel() instanceof ServerLevel level) {
            coreLevel = level;
        }
        network = CoreNetwork.members(successor, IronTideData.config().networkRadius());
        coreMaxTotal = totalCoreMax(successor);
        refreshRing(successor);
        ZoneVisuals.activate(corePos, ring);
        refreshSpawnAnchor(IronTideData.raidConfig());
        // 只在锦点真的换了位置时播报：接替被重复触发（旧 BE 残留）也不刷屏
        if (moved && !next.equals(announcedAnchor)) {
            announcedAnchor = next;
            announce(server, "§6[钢铁潮汐] §e主核失守，前哨接替 —— 坐标 "
                    + next.getX() + ", " + next.getY() + ", " + next.getZ() + "。");
        }
        return successor;
    }

    /**
     * 本波可承伤的目标。主核默认有血（{@code mainCoreInvulnerable=false}），所以整张网络都算；
     * 旧配置把开关设为 {@code true} 时退回「只算前哨」。
     * <p>
     * 落在主核保护区（{@code r<=protectedRadius}）里的前哨不承伤（设计：保护区内前哨本波无法攻击）。
     * 返回空表表示「这一波没东西可打」。
     */
    private static List<AndesiteCoreBlockEntity> damageTargets(AndesiteCoreBlockEntity anchor) {
        if (network.isEmpty()) {
            return anchor == null ? List.of() : List.of(anchor);
        }
        boolean invulnerable = IronTideData.config().mainCoreInvulnerable();
        List<AndesiteCoreBlockEntity> out = new ArrayList<>(network.size());
        for (AndesiteCoreBlockEntity member : network) {
            if (member == null || member.getState() != CoreState.ACTIVE || member.getHealth() <= 0) {
                continue;
            }
            if (invulnerable && member == anchor) {
                continue;
            }
            if (isProtectedOutpost(member, anchor)) {
                continue;
            }
            out.add(member);
        }
        return out;
    }

    /** 前哨是否落在主核保护区内（{@code r<=26}）；主核自身不算。 */
    private static boolean isProtectedOutpost(AndesiteCoreBlockEntity member, AndesiteCoreBlockEntity anchor) {
        if (member == null || anchor == null || member == anchor) {
            return false;
        }
        int protectedRadius = ring.protectedRadius();
        return Territory.radius(anchor.getBlockPos(), member.getBlockPos()) <= protectedRadius;
    }

    /** 开战时网络内所有承伤核心的血量上限总和（含主核）；无目标返回 0。 */
    private static int totalCoreMax(AndesiteCoreBlockEntity anchor) {
        int sum = 0;
        for (AndesiteCoreBlockEntity member : network) {
            if (member == null || member.getState() != CoreState.ACTIVE) {
                continue;
            }
            if (IronTideData.config().mainCoreInvulnerable() && member == anchor) {
                continue;
            }
            if (isProtectedOutpost(member, anchor)) {
                continue;
            }
            sum += Math.max(0, member.getHealthMax());
        }
        return sum;
    }

    /** HUD 用：血量比例最低的那座前哨（最先守不住的那座）。 */
    private static AndesiteCoreBlockEntity lowestRatio(List<AndesiteCoreBlockEntity> cores) {
        AndesiteCoreBlockEntity best = cores.get(0);
        double bestRatio = Double.MAX_VALUE;
        for (AndesiteCoreBlockEntity core : cores) {
            double ratio = (double) core.getHealth() / Math.max(1, core.getHealthMax());
            if (ratio < bestRatio) {
                bestRatio = ratio;
                best = core;
            }
        }
        return best;
    }

    /**
     * 攻核寻路（设计 §2.4：先朝核心走，路上遇到玩家就转火玩家）。
     * <p>
     * 已经锁定玩家目标、或者 16 格内有玩家时，直接让原版 AI 接管（僵尸/骷髅自带追人逻辑）；
     * 否则每 10 tick 把导航目标打向核心，压过原版的游荡行为。到达后由 {@link #harvestCoreDamage} 扣血。
     */
    private static void steerRaiders(ServerLevel level, List<AndesiteCoreBlockEntity> targets) {
        List<AndesiteCoreBlockEntity> pool = targets.isEmpty() ? network : targets;
        if (pool.isEmpty()) {
            return;
        }

        RaidConfig config = IronTideData.raidConfig();
        double chaseSq = (double) config.chaseRadius() * (double) config.chaseRadius();

        for (UUID uuid : raiders) {
            Entity entity = level.getEntity(uuid);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                continue;
            }
            if (mob.getTarget() != null && mob.getTarget().isAlive()) {
                continue;
            }
            if (level.getNearestPlayer(mob, 16.0D) != null) {
                continue;
            }
            // 前哨分流：每只怪扑离自己最近的那座目标（无前哨时就扑主核）
            AndesiteCoreBlockEntity target = CoreNetwork.nearest(pool, mob.getX(), mob.getY(), mob.getZ());
            if (target == null) {
                continue;
            }
            double coreX = target.getBlockPos().getX() + 0.5D;
            double coreY = target.getBlockPos().getY();
            double coreZ = target.getBlockPos().getZ() + 0.5D;
            double distSq = mob.distanceToSqr(coreX, coreY, coreZ);
            if (distSq > chaseSq) {
                // 远怪：平时只给移动控制一个直线目标，不做 A* 寻路——几十只怪同时寻路是这里最贵的一块；
                // 距离指令降到 40 tick，真寻路 6 秒补一次当兜底（避免被地形卡住后原地磨）
                if (mob.tickCount % 120 == 0) {
                    mob.getNavigation().moveTo(coreX, coreY, coreZ, 1.0D);
                } else if (mob.tickCount % 40 == 0) {
                    mob.getMoveControl().setWantedPosition(coreX, coreY, coreZ, 1.0D);
                }
            } else if (mob.tickCount % 10 == 0 && mob.getNavigation().isDone()) {
                // 近怪：交给原版寻路绕障碍；已经在走就不重算路径
                mob.getNavigation().moveTo(coreX, coreY, coreZ, 1.0D);
            }
        }
    }

    /**
     * 收尾高亮：剩余来袭怪少于阈值时给每只挂发光（配置 spawn.glowWhenAliveBelow，0 = 关闭）。
     * 每次更新 60 tick 持续时间，够撑到下一次 1 秒结算；怪清空或波次结束就不再加。
     */
    private static void highlightLeftovers(ServerLevel level, RaidConfig config) {
        int threshold = config.glowWhenAliveBelow();
        if (threshold <= 0 || raiders.isEmpty() || raiders.size() >= threshold) {
            return;
        }
        for (UUID uuid : raiders) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof Mob mob && mob.isAlive()) {
                mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0, false, false, false));
            }
        }
    }

    /**
     * 攻核结算（全局同步 5 秒脉冲）。
     * <p>
     * 调度器只有一个时钟：{@code attack.intervalTicks}（默认 100）递减到 0 时统一结算一次。
     * 接触集合 = 近身（{@code attack.range}）∪ 贴墙（空气墙 26.35），按 UUID 去重，
     * 所以同一只怪不会既算近身又算贴墙。
     * <p>
     * 每只怪单次伤害 = {@code damagePerSecond × intervalTicks / 20}（默认 6→2 ×5 = 10）；
     * 全局单次上限 = {@code dpsCap × intervalTicks / 20}（默认 16 × 5 = 80）。
     */
    private static void harvestCoreDamage(MinecraftServer server, ServerLevel level, RaidConfig config,
                                          List<AndesiteCoreBlockEntity> cores,
                                          AndesiteCoreBlockEntity anchor) {
        if (cores.isEmpty()) {
            return;
        }
        double pulseSeconds = Math.max(1, config.attackIntervalTicks()) / 20.0D;
        int perRaider = (int) Math.max(1, Math.round(config.attackDamagePerSecond() * pulseSeconds));
        int pulseCap = config.attackDpsCap() > 0
                ? (int) Math.max(0, Math.round(config.attackDpsCap() * pulseSeconds)) : 0;
        double rangeSq = config.attackRange() * config.attackRange();
        double wallLimit = ring.wallLimit();

        int[] perCore = new int[cores.size()];
        int total = 0;
        int contacts = 0;
        for (UUID uuid : raiders) {
            Entity entity = level.getEntity(uuid);
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            int best = -1;
            double bestSq = Double.MAX_VALUE;
            for (int i = 0; i < cores.size(); i++) {
                double distSq = entity.distanceToSqr(Vec3.atCenterOf(cores.get(i).getBlockPos()));
                if (distSq < bestSq) {
                    bestSq = distSq;
                    best = i;
                }
            }
            boolean near = best >= 0 && bestSq <= rangeSq;
            // 贴墙：空气墙把怪夹在 26.35 外，这一圈也算本次脉冲的接触
            boolean wall = anchor != null && Territory.isTouchingWall(entity, anchor.getBlockPos(), wallLimit);
            if (!near && !wall) {
                continue;
            }
            if (best < 0) {
                best = 0;
            }
            perCore[best] += perRaider;
            total += perRaider;
            contacts++;
        }
        if (total <= 0) {
            return;
        }
        if (pulseCap > 0 && total > pulseCap) {
            double scale = (double) pulseCap / (double) total;
            int capped = 0;
            for (int i = 0; i < perCore.length; i++) {
                perCore[i] = (int) Math.floor(perCore[i] * scale);
                capped += perCore[i];
            }
            total = Math.min(pulseCap, capped);
        }
        CoreConfig coreConfig = IronTideData.config();
        if (coreConfig.shareDamage() && cores.size() > 1) {
            int share = Math.max(1, total / cores.size());
            for (AndesiteCoreBlockEntity member : cores) {
                member.applyRaidDamage(share);
            }
        } else {
            for (int i = 0; i < cores.size(); i++) {
                if (perCore[i] > 0) {
                    cores.get(i).applyRaidDamage(perCore[i]);
                }
            }
        }
        pulseFeedback(server, level, anchor, total, contacts);
    }

    /** 脉冲反馈：核心周围一圈紫色粒子 + 一声低频闷响；播报跟脉冲走、90 秒冷却。 */
    private static void pulseFeedback(MinecraftServer server, ServerLevel level,
                                      AndesiteCoreBlockEntity anchor, int total, int contacts) {
        if (anchor == null) {
            return;
        }
        hitPulseSeq++;
        BlockPos pos = anchor.getBlockPos();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
                pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                16, 1.6D, 1.0D, 1.6D, 0.05D);
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE,
                net.minecraft.sounds.SoundSource.HOSTILE, 0.5F, 0.5F);
        long now = level.getGameTime();
        if (lastPulseAnnounce == Long.MIN_VALUE || now - lastPulseAnnounce >= 1800L) {
            lastPulseAnnounce = now;
            announce(server, "§6[钢铁潮汐] §e核心受袭：本次脉冲 " + total + " 点伤害（"
                    + contacts + " 只怪接触核心）。");
        }
    }

    private static boolean spawnOne(ServerLevel level, RaidConfig config, boolean air) {
        List<String> pool = air && !config.airRaiders().isEmpty()
                ? config.airRaiders()
                : config.groundRaiders();
        if (pool.isEmpty()) {
            return false;
        }
        String id = pool.get(level.random.nextInt(pool.size()));
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return false;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(location);
        return spawnOfType(level, config, type, air) != null;
    }

    /**
     * 用指定实体类型在环带合法落点投放一只怪。正常刷怪与卡怪重刷共用这一份落点逻辑，
     * 保证「刷怪落点必须按当前半径重校验」只有一处实现。返回新怪物 UUID，失败返回 null。
     */
    private static UUID spawnOfType(ServerLevel level, RaidConfig config, EntityType<?> type, boolean air) {
        if (type == null) {
            return null;
        }
        Entity entity = type.create(level);
        if (entity == null) {
            return null;
        }

        // 方形环：45–60 格（同向前哨时改在它 8 格外），y 不参与分区判定
        BlockPos anchor = spawnCenter != null ? spawnCenter : corePos;
        if (anchor == null) {
            return null;
        }
        int radiusMin = spawnOutpost == null ? Math.max(1, ring.spawnMin())
                : Math.max(1, config.outpost().spawnRadius());
        int radiusMax = spawnOutpost == null ? Math.max(radiusMin, ring.spawnMax())
                : Math.max(radiusMin, config.outpost().spawnRadius() + 2);
        double spread = Math.toRadians(Math.max(0, Math.min(360, config.spawnArcDegrees()))) * 0.5D;

        int x = anchor.getX();
        int z = anchor.getZ();
        int y = anchor.getY();
        boolean loaded = false;
        // 出生点必须已加载；最多重试 8 次，避开未生成的区块（不为此加载新区块）
        for (int attempt = 0; attempt < 8 && !loaded; attempt++) {
            double angle = spawnAngle + (level.random.nextDouble() * 2.0D - 1.0D) * spread;
            int radius = radiusMin + level.random.nextInt(Math.max(1, radiusMax - radiusMin + 1));
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            // 把极坐标投到方形环上：max(|dx|,|dz|) == radius
            double scale = radius / Math.max(1.0E-4D, Math.max(Math.abs(cos), Math.abs(sin)));
            x = anchor.getX() + (int) Math.round(cos * scale);
            z = anchor.getZ() + (int) Math.round(sin * scale);
            y = air ? anchor.getY() + config.airHeight()
                    : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            loaded = level.isLoaded(new BlockPos(x, y, z));
        }
        if (!loaded) {
            return null;
        }

        // 出生就面朝核心，一落地就往里冲（省掉一段转身时间）
        double toCoreX = anchor.getX() + 0.5D - (x + 0.5D);
        double toCoreZ = anchor.getZ() + 0.5D - (z + 0.5D);
        float yaw = (float) (Math.toDegrees(Math.atan2(toCoreZ, toCoreX)) - 90.0D);
        entity.moveTo(x + 0.5D, y, z + 0.5D, yaw, 0.0F);
        if (!level.addFreshEntity(entity)) {
            return null;
        }
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        raiders.add(entity.getUUID());
        return entity.getUUID();
    }

    /**
     * 卡怪超时重刷（W7）。只遍历 {@code raiders}，复用 2 tick 的空气墙轻量路径，不新增全实体扫描。
     * <p>
     * 「有进展」＝与主核的水平切比雪夫距离比历史最佳再近 2 格以上。连续
     * {@code spawn.stuckTimeoutTicks}（默认 1200 = 60 秒）无进展就换一只：
     * 丢弃旧实体、在主核环带合法落点重新投放同类型，不计击杀/不计结算；同一只最多重刷
     * {@code spawn.stuckMaxRespawns} 次，超过就 discard 并回收名额让调度器补一只。
     * <p>
     * 排除：已锁定目标/已被玩家锁定的怪、16 格内有玩家的怪、贴墙（含正在拆墙）的怪。
     */
    private static void trackStuckRaiders(ServerLevel level, RaidConfig config) {
        int timeout = config.stuckTimeoutTicks();
        if (timeout <= 0 || corePos == null || raiders.isEmpty()) {
            if (stuckTracks.size() > raiders.size()) {
                stuckTracks.keySet().retainAll(raiders);
            }
            return;
        }
        long now = level.getGameTime();
        double wallLimit = ring.wallLimit();
        List<UUID> timedOut = new ArrayList<>();
        for (UUID uuid : raiders) {
            Entity entity = level.getEntity(uuid);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                stuckTracks.remove(uuid);
                continue;
            }
            double dist = Territory.radius(corePos, mob.getX(), mob.getZ());
            StuckTrack track = stuckTracks.get(uuid);
            if (track == null) {
                stuckTracks.put(uuid, new StuckTrack(dist, now, 0));
                continue;
            }
            boolean excluded = (mob.getTarget() != null && mob.getTarget().isAlive())
                    || level.getNearestPlayer(mob, 16.0D) != null
                    || Territory.isTouchingWall(mob, corePos, wallLimit);
            if (excluded) {
                // 打墙/追人期间不算卡，但刷新计时：刚离开墙的怪不会立刻被重刷
                stuckTracks.put(uuid, new StuckTrack(Math.min(track.bestDistance(), dist), now, track.respawns()));
                continue;
            }
            if (dist <= track.bestDistance() - 2.0D) {
                stuckTracks.put(uuid, new StuckTrack(dist, now, track.respawns()));
                continue;
            }
            if (now - track.lastProgressTick() >= timeout) {
                timedOut.add(uuid);
            }
        }
        for (UUID uuid : timedOut) {
            StuckTrack track = stuckTracks.remove(uuid);
            int respawns = track == null ? 0 : track.respawns();
            Entity entity = level.getEntity(uuid);
            raiders.remove(uuid);
            if (entity == null) {
                continue;
            }
            EntityType<?> type = entity.getType();
            boolean air = !config.airRaiders().isEmpty() && !entity.onGround();
            entity.discard();
            if (respawns >= config.stuckMaxRespawns()) {
                // 重刷次数用尽：直接丢掉并回收名额，让刷怪循环按 target−spawned 的差额补一只
                spawnedCount = Math.max(0, spawnedCount - 1);
                continue;
            }
            UUID fresh = spawnOfType(level, config, type, air);
            if (fresh == null) {
                spawnedCount = Math.max(0, spawnedCount - 1);
                continue;
            }
            stuckTracks.put(fresh, new StuckTrack(Double.MAX_VALUE, now, respawns + 1));
        }
    }

    /** 离锚点超过 96 格的怪直接清掉并回收名额（远处怪既打不到核心也白吃 AI）。 */
    private static void cullFarRaiders(ServerLevel level) {
        if (corePos == null || raiders.isEmpty()) {
            return;
        }
        double limitSq = 96.0D * 96.0D;
        double cx = corePos.getX() + 0.5D;
        double cy = corePos.getY() + 0.5D;
        double cz = corePos.getZ() + 0.5D;
        Iterator<UUID> iterator = raiders.iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            Entity entity = level.getEntity(uuid);
            if (entity == null) {
                iterator.remove();
                stuckTracks.remove(uuid);
                continue;
            }
            if (entity.distanceToSqr(cx, cy, cz) > limitSq) {
                entity.discard();
                iterator.remove();
                stuckTracks.remove(uuid);
                spawnedCount = Math.max(0, spawnedCount - 1);
            }
        }
    }

    /** 把弧度换成广播里的方位词（MC 里 +X 是东、+Z 是南）。 */
    private static String directionLabel(double angle) {
        double degrees = (Math.toDegrees(angle) % 360.0D + 360.0D) % 360.0D;
        if (degrees < 45.0D || degrees >= 315.0D) {
            return "东";
        }
        if (degrees < 135.0D) {
            return "南";
        }
        if (degrees < 225.0D) {
            return "西";
        }
        return "北";
    }

    private static AndesiteCoreBlockEntity pickCore() {
        AndesiteCoreBlockEntity fallback = null;
        for (AndesiteCoreBlockEntity core : List.copyOf(AndesiteCoreBlockEntity.loaded())) {
            if (core.isRemoved() || core.getLevel() == null
                    || core.getLevel().getBlockEntity(core.getBlockPos()) != core) {
                continue;
            }
            if (core.getState() == CoreState.ACTIVE) {
                return core;
            }
            if (fallback == null) {
                fallback = core;
            }
        }
        return fallback;
    }

    private static void endRaid(MinecraftServer server, String reason) {
        if (!active) {
            return;
        }
        active = false;
        raidEndedThisNight = true;
        SafeZone.deactivate(coreLevel);
        ZoneVisuals.deactivate();
        RaidBreaker.reset();
        RaidFiller.resetWave();

        // 结算必须在清空 coreLevel/corePos 之前做，否则拿不到核心血量
        AndesiteCoreBlockEntity settledCore = null;
        if (coreLevel != null && corePos != null) {
            if (coreLevel.getBlockEntity(corePos) instanceof AndesiteCoreBlockEntity found) {
                settledCore = found;
            }
        }
        RaidSettlement.settle(server, coreLevel, corePos, settledCore, network, coreMaxTotal,
                waveIndex, targetCount, raiders.size(), reason);

        announce(server, "§6[钢铁潮汐] §7第 " + waveIndex + " 波结束 —— " + reason
                + " 来袭 " + targetCount + "，剩余 " + raiders.size() + "。");
        if (coreLevel != null) {
            for (UUID uuid : raiders) {
                Entity entity = coreLevel.getEntity(uuid);
                if (entity != null) {
                    entity.discard();
                }
            }
        }
        raiders.clear();
        spawnedCount = 0;
        targetCount = 0;
        corePos = null;
        coreLevel = null;
        coreDimId = Integer.MIN_VALUE;
        announcedAnchor = null;
        spawnCenter = null;
        spawnOutpost = null;
        subWave = 1;
        waveGapCooldown = 0;
        attackPulseCooldown = 0;
        lastPulseAnnounce = Long.MIN_VALUE;
        anglesPlanned = false;
        network = List.of();
        coreMaxTotal = 0;
    }

    /**
     * 空气墙轻量钩子：由 {@link dev.modmind.irontide.event.IronTideEvents} 每 2 tick 调用一次，
     * 不碰 {@link #CHECK_INTERVAL}（20 tick）的主循环，所以不会把寻路/高亮/RaidPerf 频率放大 10 倍。
     * <p>
     * 只夹来袭怪（+ {@code defense.denyEntities}），玩家、被动生物、投射物一律不受影响。
     * 拆墙和填沟也挂在这里，预算按每次调用封顶。
     */
    public static void tickAirWall(MinecraftServer server) {
        if (!active || corePos == null || coreLevel == null || coreLevel.getServer() != server) {
            return;
        }
        RaidConfig config = IronTideData.raidConfig();
        RaidConfig.Defense defense = config.defense();
        ServerLevel level = coreLevel;
        double wallLimit = ring.wallLimit();
        int protectedRadius = ring.protectedRadius();

        if (config.safeZone().airWall()) {
            for (UUID uuid : raiders) {
                Entity entity = level.getEntity(uuid);
                if (entity != null && entity.isAlive()) {
                    Territory.clampToWall(entity, corePos, wallLimit);
                }
            }
            if (!defense.denyEntities().isEmpty()) {
                clampDeniedEntities(level, defense, wallLimit);
            }
        }

        List<AndesiteCoreBlockEntity> targets = damageTargets(resolveRaidCore());
        trackStuckRaiders(level, config);
        RaidBreaker.tick(level, config.breaker(), protectedRadius, corePos, raiders,
                targets.isEmpty() ? network : targets);
        RaidFiller.tick(level, config.filler(), corePos, protectedRadius, raiders);
        // 每 2 tick 推进一次，内部按 10 tick 节流
        ZoneVisuals.tick(level, 2);
    }

    /** 配置了 {@code defense.denyEntities} 时才扫一扫描（默认空，不产生实体扫描开销）。 */
    private static void clampDeniedEntities(ServerLevel level, RaidConfig.Defense defense, double wallLimit) {
        double reach = wallLimit + 8.0D;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                corePos.getX() - reach, corePos.getY() - reach, corePos.getZ() - reach,
                corePos.getX() + reach, corePos.getY() + reach, corePos.getZ() + reach);
        for (Entity entity : level.getEntities((Entity) null, box, candidate -> {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType());
            return id != null && defense.denyEntities().contains(id.toString());
        })) {
            Territory.clampToWall(entity, corePos, wallLimit);
        }
    }

    private static void announce(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    private static String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(value);
        };
    }
}
