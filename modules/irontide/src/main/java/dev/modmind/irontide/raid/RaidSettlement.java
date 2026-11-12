package dev.modmind.irontide.raid;

import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.block.CoreState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 战后结算：按核心剩余血量分档给奖，并把战果播报到聊天栏。
 *
 * <p>首版没有词缀系统，所以只发通用货币「怪潮残骸」；词缀战利品等词缀做完再接进来。
 * 波次越高给得越多，权重是 {@code wave * 2 + 2}。</p>
 */
public final class RaidSettlement {

    /** 完美防守线：剩余 ≥90%。 */
    private static final double PERFECT_RATIO = 0.90D;
    /** 稳住阵脚线：剩余 ≥50%。 */
    private static final double STEADY_RATIO = 0.50D;

    private RaidSettlement() {
    }

    /**
     * @param core      结算时的核心方块实体，可能已被摧毁或已不在（返回 null）
     * @param remaining 结束时还活着的怪数量
     * @param reason    结束原因，用于播报
     */
    public static void settle(MinecraftServer server, ServerLevel level, BlockPos pos,
                              AndesiteCoreBlockEntity core, int wave, int total, int remaining, String reason) {
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
        if (scrap > 0) {
            grant(server, scrap);
        }

        announceSummary(server, wave, total, remaining, reason, alive, health, max, ratio, grade, scrap, beaconBonus);
        ModMindEntry.LOGGER.info(
                "[IronTide] raid settled wave={} total={} remaining={} reason='{}' alive={} health={}/{} performance={} scrap={} beacon={}",
                wave, total, remaining, reason, alive, health, max, performance, scrap, beaconBonus);
    }

    private static void grant(MinecraftServer server, int amount) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack stack = new ItemStack(ModMindEntry.RAID_SCRAP.get(), amount);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            player.displayClientMessage(
                    Component.literal("§6+ " + amount + " §7怪潮残骸").withStyle(ChatFormatting.GOLD), false);
        }
    }

    private static void announceSummary(MinecraftServer server, int wave, int total, int remaining,
                                        String reason, boolean alive, int health, int max,
                                        double ratio, String grade, int scrap, double beaconBonus) {
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
        } else {
            out.append("§c- 核心被摧毁，本波无奖励。持「核心维修套件」右键核心可修复。");
        }

        server.getPlayerList().broadcastSystemMessage(Component.literal(out.toString()), false);
    }
}
