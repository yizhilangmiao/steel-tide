package dev.modmind.irontide.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.IRotate;
import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.menu.CoreMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 安山核心方块。实现 {@link IRotate} 当应力出口用：核心是多方块控制器，
 * 外露的那一面就是输出轴，玩家把 Create 的传动轴直接插在这一面上就能取力。
 */
public class AndesiteCoreBlock extends BaseEntityBlock implements IRotate {
    /** 核心外露方向 = 输出轴方向，由结构校验结果按布局写回。 */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public AndesiteCoreBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(AndesiteCoreBlock::new);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AndesiteCoreBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                           BlockEntityType<T> type) {
        // 客户端也要 tick：Create 的动力学渲染靠 BE 的 tick 状态推进
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof AndesiteCoreBlockEntity core) {
                core.tick();
            }
        };
    }

    // ---- IRotate ----

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction side) {
        // 沿自身轴线两端都能接头：外露面给玩家接传动轴，内侧面接结构里那台正对的十字齿轮箱
        return side.getAxis() == state.getValue(FACING).getAxis();
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    // ---- 交互 ----

    /** 手持可燃物右键 = 投燃料；手持维修套件右键 = 修被摧毁的核心。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.is(ModMindEntry.CORE_REPAIR_KIT.get())) {
            if (level.isClientSide) {
                return ItemInteractionResult.SUCCESS;
            }
            if (level.getBlockEntity(pos) instanceof AndesiteCoreBlockEntity core) {
                return core.repairFromKit(player, stack)
                        ? ItemInteractionResult.SUCCESS
                        : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        boolean looksLikeFuel = stack.getBurnTime(RecipeType.SMELTING) > 0;
        if (!looksLikeFuel) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof AndesiteCoreBlockEntity core) {
            return core.feedFuel(stack, player, !player.isShiftKeyDown())
                    ? ItemInteractionResult.SUCCESS
                    : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof AndesiteCoreBlockEntity core) {
            if (player.isShiftKeyDown()) {
                core.reportStatus(player);
                return InteractionResult.CONSUME;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                // 必须走带 extra data 的重载：原版 openMenu(provider) 不写 buf，
                // 客户端会拿 null 去 readBlockPos，直接 NPE 掉线。
                serverPlayer.openMenu(new SimpleMenuProvider(
                                (containerId, inventory, p) -> new CoreMenu(containerId, inventory, core),
                                Component.literal("钢铁潮汐 · 安山核心")),
                        buffer -> buffer.writeBlockPos(pos));
                return InteractionResult.CONSUME;
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** 玩家拆控制器：在方块真正移除前同步还原外壳，避免留下孤儿壳。 */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof AndesiteCoreBlockEntity core) {
            core.unformImmediately();
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 控制器被拆/被替换时先把外壳还原，再走正常掉落与方块移除流程，避免留下孤儿壳。
     * 这里必须同步还原：控制器 BE 一旦移除，分批队列就没有地方继续运行了。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof AndesiteCoreBlockEntity core) {
            core.unformImmediately();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AndesiteCoreBlockEntity core) {
            core.onPlaced();
        }
    }
}
