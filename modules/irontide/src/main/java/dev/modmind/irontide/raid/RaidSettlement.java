package dev.modmind.irontide.raid;

import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.block.CoreState;
import dev.modmind.irontide.config.CoreConfig;
import dev.modmind.irontide.data.IronTideData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 战后结算：按核心剩余血量分档给奖，并把战果播报到聊天栏。
 *
 * <p>首版没有词缀系统，所以只发通用货币「怪潮残骸」；词缀战利品等词缀做完再接进来。
 * 波次越高给得越多，权重是 {@code wave * 2 + 2}。</p>
 *
 * <p>W3 前哨分摊：开了 {@code core.json → network.settlementSplit} 且网络里有多座存活核心时，
 * 残骸按核心数拆份，每份给离那座核心最近的玩家（128 格内；没人就并回全员发放）。</p>
 */
public final class RaidSettlement {

    /** 完美防守线：剩余 ≥90%。 */
    private static final double PERFECT_RATIO = 0.90D;
    /** 稳住阵脚线：剩余 ≥50%。 */
    private static final double STEADY_RATIO = 0.50D;
    /** 分摊时找「负责这座核心的玩家」的最大距离。 */
    private static final double SHARE_NEAR_RADIUS = 128.0D;

    private RaidSettlement() {
    }

    /**
     * @param core      结算时的核心方块实体，可能已被摧毁或已不在（返回 null）
     * @param network   本波的核心网络（主核 + 前哨），可以为空
     * @param remaining 结束时还活着的怪数量
     * @param reason    结束原因，用于播报
     */
    public static void settle(MinecraftServer server, ServerLevel level, BlockPos pos,
                              AndesiteCoreBlockEntity core, List<AndesiteCoreBlockEntity> network,
                              int wave, int total, int remaining, String reason) {
        if (server == null) {
            return;
        }

        boolean alive = core != null && core.getState() == CoreState.ACTIVE && core.getHealth() > 0;
        int health = core != null ? core.getHealth() : 0;
        int max = core != null ? core.getHealthMax() : 0;
        double ratio = max > 0 ? (double) health / max : 0.0D;

        double performance;
        String grade;
        if (!alive) {
            performance = 0.0D;
            grade = "核心失守";
        } else if (ratio >= PERFECT_RATIO) {
            performance = 1.5D;
            grade = "完美防守 +50%";
        } else if (ratio >= STEADY_RATIO) {
            performance = 1.25D;
            grade = "稳住阵脚 +25%";
        } else {
            performance = 1.0D;
            grade = "勉强守住";
        }

        // 信标模块的奖励加成：没装 / 核心已毁时按 0 处理，纯乘算、不改变 performance 分档。
        double beaconBonus = core != null ? core.getBeaconReward() : 0.0D;
        int scrap = (int) Math.round((wave * 2 + 2) * performance * (1.0D + beaconBonus));

        List<AndesiteCoreBlockEntity> survivors = aliveMembers(network);
        CoreConfig coreConfig = IronTideData.isLoaded() ? IronTideData.config() : null;
        boolean split = coreConfig != null && coreConfig.settlementSplit()
                && survivors.size() > 1 && scrap > 0;

        int shareAmount = 0;
        if (scrap > 0) {
            if (split) {
                int count = survivors.size();
                int base = scrap / count;
                int remainder = scrap - base * count;
                shareAmount = base;
                for (int i = 0; i < count; i++) {
                    int amount = base + (i == 0 ? remainder : 0);
                    if (amount <= 0) {
                        continue;
                    }
                    grantTo(server, nearestPlayer(server, survivors.get(i)), amount);
                }
            } else {
                grant(server, scrap);
            }
        }

        announceSummary(server, wave, total, remaining, reason, alive, health, max, ratio, grade,
                scrap, beaconBonus, split ? survivors.size() : 0, shareAmount);
        ModMindEntry.LOGGER.info(
                "[IronTide] raid settled wave={} total={} remaining={} reason='{}' alive={} health={}/{}"
                        + " performance={} scrap={} beacon={} cores={} split={} peakMspt={}",
                wave, total, remaining, reason, alive, health, max, performance, scrap, beaconBonus,
                survivors.size(), split, String.format("%.1f", RaidPerf.peakMspt()));
    }

    /** 网络里还活着的核心（ACTIVE 且血量 > 0）；结算分摊只用这张表。 */
    private static List<AndesiteCoreBlockEntity> aliveMembers(List<AndesiteCoreBlockEntity> network) {
        List<AndesiteCoreBlockEntity> out = new ArrayList<>();
        if (network == null) {
            return out;
        }
        for (AndesiteCoreBlockEntity member : network) {
            if (member != null && member.getState() == CoreState.ACTIVE && member.getHealth() > 0) {
                out.add(member);
            }
        }
        return out;
    }

    /** 离这座核心最近、且还在同一维度的玩家；找不到返回 null（这一份并回全员发放）。 */
    private static @Nullable ServerPlayer nearestPlayer(MinecraftServer server, AndesiteCoreBlockEntity core) {
        if (!(core.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        Vec3 center = Vec3.atCenterOf(core.getBlockPos());
        double limitSq = SHARE_NEAR_RADIUS * SHARE_NEAR_RADIUS;
        ServerPlayer best = null;
        double bestSq = limitSq;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != level) {
                continue;
            }
            double distSq = player.position().distanceToSqr(center);
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = player;
            }
        }
        return best;
    }

    private static void grant(MinecraftServer server, int amount) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            grantTo(server, player, amount);
        }
    }

    /** 给单个玩家发残骸；目标为 null 时退回全员发放，保证奖励不丢。 */
    private static void grantTo(MinecraftServer server, @Nullable ServerPlayer player, int amount) {
        if (amount <= 0) {
            return;
        }
        if (player == null) {
            for (ServerPlayer fallback : server.getPlayerList().getPlayers()) {
                giveTo(fallback, amount);
            }
            return;
        }
        giveTo(player, amount);
    }

    private static void giveTo(ServerPlayer player, int amount) {
        ItemStack stack = new ItemStack(ModMindEntry.RAID_SCRAP.get(), amount);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.displayClientMessage(
                Component.literal("§6+ " + amount + " §7怪潮残骸").withStyle(ChatFormatting.GOLD), false);
    }

    private static void announceSummary(MinecraftServer server, int wave, int total, int remaining,
                                        String reason, boolean alive, int health, int max,
                                        double ratio, String grade, int scrap, double beaconBonus,
                                        int splitCores, int shareAmount) {
        StringBuilder out = new StringBuilder();
        out.append("§6[钢铁潮汐] §f第 ").append(wave).append(" 波结算 §7—— ").append(reason).append('\n');
        out.append("§7- 来袭 §f").append(total).append(" §7只，撤退时剩 §f").append(remaining).append(" §7只\n");

        if (alive) {
            out.append("§7- 核心 §f").append(health).append(" / ").append(max)
                    .append(" §7（§f").append(Math.round(ratio * 100)).append("%§7）· ")
                    .append(ratio >= PERFECT_RATIO ? "§a" : ratio >= STEADY_RATIO ? "§e" : "§c")
                    .append(grade).append('\n');
            out.append("§7- 战利品：§6怪潮残骸 ×").append(scrap);
            if (beaconBonus > 0.0D) {
                out.append(" §7· 信标 §a+").append(Math.round(beaconBonus * 100)).append('%');
            }
            out.append(" §7（已放入背包）");
            if (splitCores > 1) {
                out.append('\n').append("§7- 前哨分摊：§f").append(splitCores).append("§7 座核心，每座 §f")
                        .append(shareAmount).append(" §7残骸（发给离该核心最近的玩家）");
            }
        } else {
            out.append("§c- 核心被摧毁，本波无奖励。持「核心维修套件」右键核心可修复。");
        }

        server.getPlayerList().broadcastSystemMessage(Component.literal(out.toString()), false);
    }
}