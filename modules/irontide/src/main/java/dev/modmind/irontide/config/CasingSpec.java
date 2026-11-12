package dev.modmind.irontide.config;

import dev.modmind.irontide.block.ShellBlock;
import dev.modmind.irontide.block.ShellType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 一种机壳材质：世界里摆的是哪个方块、成型后换成哪种壳、给核心什么加成、从几档核心起可用。
 * <p>
 * {@code minTier} 是门槛：核心档位序号（T1=1、T2=2 …）低于它时，用这种材质搭的结构算不完整，
 * 校验器会把它计入缺失，避免 T1 直接铺钢壳跳过成长曲线。
 */
public record CasingSpec(
        ResourceLocation id,
        @Nullable BlockState formed,
        double armor,
        double healthMul,
        double fuelEfficiency,
        int minTier
) {
    public Block block() {
        return BuiltInRegistries.BLOCK.get(id);
    }

    /** 世界里的这个方块，成型后应该变成什么；没有配置就返回 null。 */
    public @Nullable BlockState formedFor(Block actualBlock) {
        return formed != null && actualBlock == block() ? formed : null;
    }

    /** 成型后壳的类型；没配 formed 就是 null。 */
    public @Nullable ShellType shellType() {
        if (formed == null || !(formed.getBlock() instanceof ShellBlock shell)) {
            return null;
        }
        return formed.getValue(ShellBlock.TYPE);
    }

    /** 面板与日志用的名字：优先取壳类型的中文名，拿不到就退回方块 id 的路径部分。 */
    public String label() {
        ShellType type = shellType();
        return type != null ? type.displayName() : id.getPath();
    }
}