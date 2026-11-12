package dev.modmind.irontide.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public record CasingSpec(
        ResourceLocation id,
        @Nullable BlockState formed,
        double armor,
        double healthMul,
        double fuelEfficiency
) {
    public Block block() {
        return BuiltInRegistries.BLOCK.get(id);
    }

    /** 世界里的这个方块，成型后应该变成什么；没有配置就返回 null。 */
    public @Nullable BlockState formedFor(Block actualBlock) {
        return formed != null && actualBlock == block() ? formed : null;
    }
}