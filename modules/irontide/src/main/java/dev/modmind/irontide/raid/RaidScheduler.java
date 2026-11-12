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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 怪潮调度器（设计 §2.4）。
 * <p>
 * 时间轴：第 8 天预警 → 第 9 天侦察 → 第 10 天夜进攻。时间完全由世界时间驱动，
 * 不依赖玩家在线；怪物类型与波次数值全部来自 {@code config/irontide/raids.json}。
 * <p>
 * 首版范围：刷怪 + 攻核 + 天亮结算。词缀、信标完整度、干预（第 9 天削弱词缀）留后续切片。
 */
public final class RaidScheduler {
    private static final int CHECK_INTERVAL = 20;
    /** 收尾发光每次挂 3 秒，靠每秒一次的 tickActive 续上。 */
    private static final int GLOW_DURATION_TICKS = 60;

    private static int counter;
    private static int lastDay = Integer.MIN_VALUE;
    private static int cycleDay = 1;

    private static boolean warnedThisCycle;
    private static boolean scoutedThisCycle;
    /** 本夜已经开打过（或判定打不起来）。天亮清零，避免 endRaid 之后下一 tick 又重开、聊天栏刷屏。 */
    private static boolean raidEndedThisNight;

    private static boolean active;
    private static int waveIndex = 1;
    private static int targetCount;
    private static int spawnedCount;
    private static int spawnCooldown;
    private static int damageCooldown;

    private static final Set<UUID> raiders = new HashSet<>();
    /** 本波的主攻方向（弧度）。一波怪只从这个扇面里出来，不再是四面八方随机。 */
    private static double spawnAngle;
    private static BlockPos corePos;
    private static ServerLevel coreLevel;
    private static int coreDimId = Integer.MIN_VALUE;
    /** 本 tick 的核心网络（主核 + 前哨），承伤/寻路/拆墙都看这张表。 */
    private static List<AndesiteCoreBlockEntity> network = List.of();
    /** 开战时前哨的血量上限总和；结算比例 = 当前前哨总血量 ÷ 它。 */
    private static int outpostMaxTotal;

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

    public static void reset() {
        counter = 0;
        lastDay = Integer.MIN_VALUE;
        cycleDay = 1;
        warnedThisCycle = false;
        scoutedThisCycle = false;
        raidEndedThisNight = false;
        active = false;
        waveIndex = 1;
        targetCount = 0;
        spawnedCount = 0;
        spawnCooldown = 0;
        damageCooldown = 0;
        raiders.clear();
        spawnAngle = 0.0D;
        SafeZone.deactivate(coreLevel);
        corePos = null;
        coreLevel = null;
        coreDimId = Integer.MIN_VALUE;
        network = List.of();
        outpostMaxTotal = 0;
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
                // 主核无血时 HUD 显示「最吃紧的前哨」，主核自己永远是满的，看了没意义
                AndesiteCoreBlockEntity display = core;
                List<AndesiteCoreBlockEntity> targets = damageTargets(core);
                if (!targets.isEmpty() && IronTideData.config().mainCoreInvulnerable()) {
                    display = lowestRatio(targets);
                }
                health = display.getHealth();
                maxHealth = display.getHealthMax();
            }
        }
        SteamHudPayload payload = new SteamHudPayload(
                health, maxHealth, active ? waveIndex : 0, raiders.size(), active);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
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
            announce(server, "§6[钢铁潮汐] §e第 " + config.warnDay() + " 天：地平线传来震动。怪潮将在第 "
                    + config.attackDay() + " 天夜间抵达。");
        }
        if (cycleDay == config.scoutDay() && !scoutedThisCycle) {
            scoutedThisCycle = true;
            RaidConfig.Wave wave = config.wave(waveIndex);
            announce(server, "§6[钢铁潮汐] §e第 " + config.scoutDay() + " 天：侦察确认 —— 第 "
                    + waveIndex + " 波，约 " + config.effectiveBase(waveIndex) + " 个目标，词缀 " + wave.affixes()
                    + " 条（最高 " + roman(wave.maxAffixLevel()) + " 级）。明晚进攻。");
        }
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
        int online = Math.max(1, server.getPlayerCount());
        // GearMul 按核心实际档位缩放（设计 §2.4）：T1 → 1.00，最高档 → 1 + gearMulMax
        CoreConfig coreConfig = IronTideData.config();
        int maxTier = Math.max(1, coreConfig.tiers().size());
        int coreTier = Math.max(1, Math.min(maxTier, core.tierIndex()));
        targetCount = config.scaledSize(waveIndex, online, coreTier, maxTier);
        spawnedCount = 0;
        raiders.clear();
        spawnCooldown = 0;
        damageCooldown = 0;
        corePos = core.getBlockPos();
        coreLevel = level;
        coreDimId = level.dimension().location().hashCode();
        active = true;
        network = CoreNetwork.members(core, coreConfig.networkRadius());
        outpostMaxTotal = totalOutpostMax(core);
        RaidPerf.begin(server);
        // 每波抽一个主攻方向：整波怪都压在同一侧，玩家能把防线摆对地方
        spawnAngle = level.random.nextDouble() * Math.PI * 2.0D;

        RaidConfig.SafeZone zone = config.safeZone();
        if (zone.enabled()) {
            SafeZone.activate(level, corePos, zone.radius(), zone.halfHeight(),
                    zone.lightUp() ? zone.lightLevel() : 0, zone.beacons(), zone.particleIntervalTicks());
        }

        String outposts;
        if (network.size() > 1) {
            outposts = coreConfig.mainCoreInvulnerable()
                    ? "§7（含前哨 §f" + (network.size() - 1) + "§7 座，主核不承伤）"
                    : "§7（含前哨 §f" + (network.size() - 1) + "§7 座，承伤分摊）";
        } else if (coreConfig.mainCoreInvulnerable()) {
            outposts = "§7（未设前哨，主核不承伤）";
        } else {
            outposts = "";
        }
        announce(server, "§4[钢铁潮汐] §c怪潮第 " + waveIndex + " 波开始！§7目标：安山核心（"
                + targetCount + " 个来袭），来自§e " + directionLabel(spawnAngle) + "§7 侧。" + outposts
                + "守住它直到天亮。");
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
        if (IronTideData.config().mainCoreInvulnerable() && outpostMaxTotal > 0 && targets.isEmpty()) {
            endRaid(server, "§c前哨全部失守，怪潮散去。");
            return;
        }
        steerRaiders(level, targets);
        highlightLeftovers(level, config);
        RaidBreaker.tick(level, config.breaker(), raiders, targets.isEmpty() ? network : targets);
        RaidPerf.sample(server, raiders.size());

        if (spawnedCount >= targetCount && raiders.isEmpty()) {
            endRaid(server, "§a怪潮被清空。");
            return;
        }
        if (!night) {
            endRaid(server, "§a天亮了，怪潮退去。");
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

        SafeZone.tick(level);

        if (--damageCooldown <= 0) {
            damageCooldown = 1;
            harvestCoreDamage(level, config, targets);
        }
    }

    /** 怪潮锚定的核心：只认开战时那个坐标上的核心，避免跨网络跳到别处的核心。 */
    private static AndesiteCoreBlockEntity resolveRaidCore() {
        if (coreLevel == null || corePos == null) {
            return null;
        }
        return coreLevel.getBlockEntity(corePos) instanceof AndesiteCoreBlockEntity core ? core : null;
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
            if (member.getState() != CoreState.ACTIVE || member.getHealth() <= 0) {
                continue;
            }
            return member;
        }
        return null;
    }

    /** 把锚点切到接替的前哨：刷怪、承伤、结算都跟着新核心走。 */
    private static AndesiteCoreBlockEntity promoteCore(MinecraftServer server, AndesiteCoreBlockEntity successor) {
        corePos = successor.getBlockPos();
        if (successor.getLevel() instanceof ServerLevel level) {
            coreLevel = level;
        }
        announce(server, "§6[钢铁潮汐] §e主核失守，前哨接替 —— 坐标 "
                + corePos.getX() + ", " + corePos.getY() + ", " + corePos.getZ() + "。");
        return successor;
    }

    /**
     * 本波可承伤的目标。默认（{@code mainCoreInvulnerable=true}）= 网络里的前哨，主核自己不挨打；
     * 关掉开关则整张网络都算目标。返回空表表示「这一波没东西可打」。
     */
    private static List<AndesiteCoreBlockEntity> damageTargets(AndesiteCoreBlockEntity anchor) {
        if (network.isEmpty()) {
            return anchor == null ? List.of() : List.of(anchor);
        }
        if (!IronTideData.config().mainCoreInvulnerable()) {
            return network;
        }
        List<AndesiteCoreBlockEntity> out = new ArrayList<>(network.size());
        for (AndesiteCoreBlockEntity member : network) {
            if (member == null || member == anchor) {
                continue;
            }
            if (member.getState() != CoreState.ACTIVE || member.getHealth() <= 0) {
                continue;
            }
            out.add(member);
        }
        return out;
    }

    /** 开战时前哨的血量上限总和（不含主核）；无前哨返回 0。 */
    private static int totalOutpostMax(AndesiteCoreBlockEntity anchor) {
        if (!IronTideData.config().mainCoreInvulnerable()) {
            return 0;
        }
        int sum = 0;
        for (AndesiteCoreBlockEntity member : network) {
            if (member != null && member != anchor) {
                sum += Math.max(0, member.getHealthMax());
            }
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
                // 每 3 秒补一次真寻路当兜底，避免被地形卡住后永远原地磨
                if (mob.tickCount % 60 == 0) {
                    mob.getNavigation().moveTo(coreX, coreY, coreZ, 1.0D);
                } else if (mob.tickCount % 20 == 0) {
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
     * 攻核扣血（W3 前哨：主核无血）。
     * <p>
     * 只对「可承伤目标」累计——默认就是前哨（主核不掉血）；关掉 {@code network.mainCoreInvulnerable}
     * 才把整张网络都算进来。先按核心累计，再受全局 {@code attack.dpsCap} 封顶。
     * 开了 {@code network.shareDamage} 且目标多于一座时，总量在它们之间均摊（每座至少 1 点）。
     */
    private static void harvestCoreDamage(ServerLevel level, RaidConfig config,
                                          List<AndesiteCoreBlockEntity> cores) {
        if (cores.isEmpty()) {
            return;
        }
        double rangeSq = config.attackRange() * config.attackRange();
        int[] perCore = new int[cores.size()];
        int total = 0;
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
            if (best >= 0 && bestSq <= rangeSq) {
                perCore[best] += config.attackDamagePerSecond();
                total += config.attackDamagePerSecond();
            }
        }
        if (total <= 0) {
            return;
        }
        if (config.attackDpsCap() > 0 && total > config.attackDpsCap()) {
            double scale = (double) config.attackDpsCap() / (double) total;
            int capped = 0;
            for (int i = 0; i < perCore.length; i++) {
                perCore[i] = (int) Math.floor(perCore[i] * scale);
                capped += perCore[i];
            }
            total = Math.min(config.attackDpsCap(), capped);
        }
        CoreConfig coreConfig = IronTideData.config();
        if (coreConfig.shareDamage() && cores.size() > 1) {
            int share = Math.max(1, (int) Math.round((double) total / (double) cores.size()));
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
        Entity entity = type.create(level);
        if (entity == null) {
            return false;
        }

        // 只在主攻方向的扇面里落点：arcDegrees 越小越像「一队人从同一条路压过来」
        double spread = Math.toRadians(Math.max(0, Math.min(360, config.spawnArcDegrees()))) * 0.5D;
        double angle = spawnAngle + (level.random.nextDouble() * 2.0D - 1.0D) * spread;
        int radius = config.spawnRadiusMin()
                + level.random.nextInt(Math.max(1, config.spawnRadiusMax() - config.spawnRadiusMin() + 1));
        int x = corePos.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = corePos.getZ() + (int) Math.round(Math.sin(angle) * radius);
        int y = air
                ? corePos.getY() + config.airHeight()
                : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

        // 出生就面朝核心，一落地就往里冲（省掉一段转身时间）
        double toCoreX = corePos.getX() + 0.5D - (x + 0.5D);
        double toCoreZ = corePos.getZ() + 0.5D - (z + 0.5D);
        float yaw = (float) (Math.toDegrees(Math.atan2(toCoreZ, toCoreX)) - 90.0D);
        entity.moveTo(x + 0.5D, y, z + 0.5D, yaw, 0.0F);
        if (!level.addFreshEntity(entity)) {
            return false;
        }
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        raiders.add(entity.getUUID());
        return true;
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

        // 结算必须在清空 coreLevel/corePos 之前做，否则拿不到核心血量
        AndesiteCoreBlockEntity settledCore = null;
        if (coreLevel != null && corePos != null) {
            if (coreLevel.getBlockEntity(corePos) instanceof AndesiteCoreBlockEntity found) {
                settledCore = found;
            }
        }
        RaidSettlement.settle(server, coreLevel, corePos, settledCore, network, outpostMaxTotal,
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
        network = List.of();
        outpostMaxTotal = 0;
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
