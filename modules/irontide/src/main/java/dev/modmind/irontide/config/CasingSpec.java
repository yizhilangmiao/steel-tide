package dev.modmind.irontide.config;

import net.minecraft.resources.ResourceLocation;

public record CasingSpec(ResourceLocation id, double armor, double healthMul, double fuelEfficiency) {
}