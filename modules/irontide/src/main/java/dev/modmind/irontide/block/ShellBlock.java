package dev.modmind.irontide.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 结构外壳：多方块成型后盖在外部方块上的「皮肤」。
 * <p>
 * 刻意<b>不</b>实现 {@code IRotate}、也不注册任何 Capability —— 否则会被 Create 的
 * 应力网络或物流模组当成可接入的方块，成型瞬间读数乱跳。
 * <p>
 * 壳本身不掉落：拆壳掉的是它盖住的那个原方块（记录在 {@link ShellBlockEntity} 里），
 * 所以这里不需要也不应该有 loot table。
 */
public class ShellBlock extends BaseEntityBlock {
    public static final EnumProperty<ShellType> TYPE = EnumProperty.create("type", ShellType.class);

    public ShellBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TYPE, ShellType.ANDESITE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(ShellBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TYPE);
    }

    /** 普通方块模型，没有任何自定义渲染。 */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShellBlockEntity(pos, state);
    }

    /** 壳是外观，不是资源；原方块由 ShellBlockEntity 记录并单独掉落。 */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of();
    }

    /** 玩家拆壳：按壳 BE 里记录的原方块掉一次，然后清空记录，避免 onRemove 重复掉。 */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ShellBlockEntity shell) {
            BlockState original = shell.releaseOriginalState();
            if (!player.isCreative()) {
                dropOriginal(level, pos, original);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 非玩家拆除（爆炸、活塞、第三方改方块）：仍然按原方块掉一次。
     * <p>
     * 玩家拆除时 {@link #playerWillDestroy} 已经清空记录，这里自然不会再掉。
     * 还原流程会先清空记录再 setBlock，所以也不会在还原时复制原方块。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof ShellBlockEntity shell) {
            dropOriginal(level, pos, shell.releaseOriginalState());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void dropOriginal(Level level, BlockPos pos, BlockState original) {
        if (!original.isAir() && original.getBlock().asItem() != Items.AIR) {
            Block.popResource(level, pos, new ItemStack(original.getBlock().asItem()));
        }
    }
}
