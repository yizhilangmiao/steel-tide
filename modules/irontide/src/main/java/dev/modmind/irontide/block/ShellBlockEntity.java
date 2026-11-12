package dev.modmind.irontide.block;

import dev.modmind.irontide.ModMindEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 结构外壳的方块实体：只记两件事 —— 控制器在哪、这格原本是什么方块。
 * <p>
 * 必须直接继承 {@link BlockEntity}：控制器的 {@code SmartBlockEntity} 把
 * {@code saveAdditional/loadAdditional} 变成了 final，照抄会编译不过。
 * <p>
 * 「哪些格子是壳」这份账只存在壳自己身上，控制器不另存一份；区块卸载再加载时
 * 靠这里的 NBT 恢复，再靠 {@link #onLoad()} 自愈。
 */
public class ShellBlockEntity extends BlockEntity {
    /** 归属的安山核心位置；未绑定时为零点，自愈时会判定为「没有控制器」。 */
    private BlockPos controllerPos = BlockPos.ZERO;
    /** 被壳盖住的原始方块状态；空气表示没有记录，壳不该独立存在。 */
    private BlockState originalState = Blocks.AIR.defaultBlockState();

    public ShellBlockEntity(BlockPos pos, BlockState state) {
        super(ModMindEntry.SHELL_BE.get(), pos, state);
    }

    /** 成型时由控制器调用：绑定控制器并记住原方块。 */
    public void bind(BlockPos controller, BlockState original) {
        this.controllerPos = controller;
        this.originalState = original;
        setChanged();
    }

    public BlockPos controllerPos() {
        return controllerPos;
    }

    public BlockState originalState() {
        return originalState;
    }

    /**
     * 取出原方块状态并清空记录。掉落与还原共用它，保证同一格的原方块只被处理一次；
     * 清空后再 setBlock / removeBlock 时，ShellBlock.onRemove 不会重复掉落。
     */
    public BlockState releaseOriginalState() {
        BlockState original = originalState;
        originalState = Blocks.AIR.defaultBlockState();
        setChanged();
        return original;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("controller", NbtUtils.writeBlockPos(controllerPos));
        tag.put("original", NbtUtils.writeBlockState(originalState));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        controllerPos = NbtUtils.readBlockPos(tag, "controller").orElse(BlockPos.ZERO);
        originalState = NbtUtils.readBlockState(
                registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("original"));
    }

    /**
     * 自愈：保证壳永远不会脱离控制器独立存在，避免存档里卡一堆无用方块。
     * <ul>
     *   <li>没记到原方块（空气）→ 说明这格不是成型写出来的，直接移除；</li>
     *   <li>控制器那一格已经不是 {@link AndesiteCoreBlockEntity} → 还原成原方块。</li>
     * </ul>
     */
    @Override
    public void onLoad() {
        super.onLoad();
        Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (originalState.isAir()) {
            level.removeBlock(worldPosition, false);
            return;
        }
        if (!(level.getBlockEntity(controllerPos) instanceof AndesiteCoreBlockEntity)) {
            restore(level);
        }
    }

    /** 还原成原方块。先清空记录，免得 {@link ShellBlock#onRemove} 又掉一份原方块。 */
    private void restore(Level level) {
        BlockState restore = releaseOriginalState();
        level.setBlock(worldPosition, restore,
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
}
