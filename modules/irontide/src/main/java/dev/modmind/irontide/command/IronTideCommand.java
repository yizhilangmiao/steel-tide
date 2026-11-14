package dev.modmind.irontide.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.data.IronTideData;
import dev.modmind.irontide.raid.ZoneFlattener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /irontide ...} 运维指令（W7）：地形平整、坐标记录、死亡点回传。
 */
public final class IronTideCommand {

    /** 玩家死亡点存在 persistentData 里的键。 */
    public static final String DEATH_KEY = "irontide:last_death";

    private IronTideCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("irontide")
                .then(Commands.literal("flatten")
                        .requires(source -> source.hasPermission(2))
                        .executes(IronTideCommand::flatten)
                        .then(Commands.literal("auto")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setAuto(context,
                                                BoolArgumentType.getBool(context, "enabled"))))))
                .then(Commands.literal("here")
                        .executes(IronTideCommand::here))
                .then(Commands.literal("back")
                        .executes(IronTideCommand::back)));
    }

    /** 记录死亡点（由 {@code IronTideEvents} 在玩家死亡时调用）。 */
    public static void rememberDeath(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", player.blockPosition().asLong());
        tag.putString("dim", player.level().dimension().location().toString());
        player.getPersistentData().put(DEATH_KEY, tag);
    }

    private static int flatten(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        if (!IronTideData.isLoaded()) {
            source.sendFailure(Component.literal("钢铁潮汐配置未加载"));
            return 0;
        }
        AndesiteCoreBlockEntity core = nearestCore(player);
        if (core == null) {
            source.sendFailure(Component.literal("128 格内没有已成型的主核/前哨"));
            return 0;
        }
        if (!(core.getLevel() instanceof ServerLevel coreLevel)) {
            source.sendFailure(Component.literal("核心所在维度不可用"));
            return 0;
        }
        int radius = dev.modmind.irontide.raid.TerritoryScale
                .of(core.tierIndex(), IronTideData.raidConfig()).protectedRadius();
        if (!ZoneFlattener.start(coreLevel, core.getBlockPos(), radius, core.getBlockPos().getY())) {
            source.sendFailure(Component.literal("已经有平整任务在跑"));
            return 0;
        }
        BlockPos pos = core.getBlockPos();
        source.sendSuccess(() -> Component.literal("开始平整主核周围 " + radius + " 格地形（只削自然方块，跳过建筑/容器）"
                + "\n主核坐标：" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), false);
        return 1;
    }

    private static int setAuto(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                               boolean enabled) {
        ZoneFlattener.setAutoEnabled(enabled);
        context.getSource().sendSuccess(() -> Component.literal("成型后自动平整：" + (enabled ? "开" : "关")), false);
        return 1;
    }

    private static int here(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos pos = player.blockPosition();
        String dim = player.level().dimension().location().toString();
        context.getSource().sendSuccess(() -> Component.literal(
                "当前位置：" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "（" + dim + "）"), false);
        return 1;
    }

    private static int back(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        CompoundTag data = player.getPersistentData();
        if (!data.contains(DEATH_KEY)) {
            source.sendFailure(Component.literal("还没有记录到死亡点"));
            return 0;
        }
        CompoundTag tag = data.getCompound(DEATH_KEY);
        BlockPos pos = BlockPos.of(tag.getLong("pos"));
        ResourceLocation dimId = ResourceLocation.tryParse(tag.getString("dim"));
        ResourceKey<Level> key = dimId == null ? Level.OVERWORLD
                : ResourceKey.create(Registries.DIMENSION, dimId);
        ServerLevel target = source.getServer().getLevel(key);
        if (target == null) {
            source.sendFailure(Component.literal("死亡点维度已不存在"));
            return 0;
        }
        player.teleportTo(target, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("已回到死亡点：" + pos.getX() + ", " + pos.getY()
                + ", " + pos.getZ()), false);
        return 1;
    }

    /** 找玩家 128 格内最近的、已成型的主核或前哨。 */
    private static AndesiteCoreBlockEntity nearestCore(ServerPlayer player) {
        AndesiteCoreBlockEntity best = null;
        double bestSq = 128.0D * 128.0D;
        for (AndesiteCoreBlockEntity core : java.util.List.copyOf(AndesiteCoreBlockEntity.loaded())) {
            if (core.isRemoved() || core.getLevel() != player.level()) {
                continue;
            }
            double distSq = core.getBlockPos().distSqr(player.blockPosition());
            if (distSq < bestSq) {
                bestSq = distSq;
                best = core;
            }
        }
        return best;
    }
}
