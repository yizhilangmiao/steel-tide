package dev.modmind.irontide.raid;

import dev.modmind.irontide.config.RaidConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 攻核怪填沟（W4 领地分区）。
 * <p>
 * 只填 {@code r>=27} 的沟：从沟底往上垫，深 2–4、宽 ≤ 3。每只怪每波最多
 * {@code perRaiderPerWave} 块，全局每波 {@code globalPerWave} 块，每次调用最多 {@code perTick} 块。
 * <p>
 * 判定用「怪脚下的高度 - 目标列地表高度」：地表比脚下低 2–4 格就是沟，
 * 把地表那个空气格垫上默认方块，重复几次就把沟填平。
 */
public final class RaidFiller {

    private static final Map<UUID, Integer> perRaider = new HashMap<>();
    private static int globalFilled;

    private RaidFiller() {
    }

    /** 每小波开始/结束时清空配额。 */
    public static void resetWave() {
        perRaider.clear();
        globalFilled = 0;
    }

    public static void tick(ServerLevel level, RaidConfig.Filler config, BlockPos center,
                            int protectedRadius, Collection<UUID> raiders) {
        if (!config.enabled() || config.perRaiderPerWave() <= 0 || config.globalPerWave() <= 0) {
            return;
        }
        if (center == null || globalFilled >= config.globalPerWave()) {
            return;
        }
        Block block = resolveBlock(config.block());
        if (block == Blocks.AIR) {
            return;
        }
        int budget = Math.max(1, config.perTick());
        for (UUID uuid : raiders) {
            if (budget <= 0 || globalFilled >= config.globalPerWave()) {
                return;
            }
            Entity entity = level.getEntity(uuid);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                continue;
            }
            int used = perRaider.getOrDefault(uuid, 0);
            if (used >= config.perRaiderPerWave()) {
                continue;
            }
            BlockPos target = findTrench(level, mob, config, protectedRadius, center);
            if (target == null) {
                continue;
            }
            level.setBlock(target, block.defaultBlockState(), 3);
            level.playSound(null, target, block.defaultBlockState().getSoundType().getPlaceSound(),
                    SoundSource.HOSTILE, 0.5F, 1.0F);
            perRaider.put(uuid, used + 1);
            globalFilled++;
            budget--;
        }
    }

    /** 朝核心方向找一格「深 2–4、宽 ≤3」的沟底空气格；找不到返回 null。 */
    private static BlockPos findTrench(ServerLevel level, Mob mob, RaidConfig.Filler config,
                                       int protectedRadius, BlockPos center) {
        BlockPos base = mob.blockPosition();
        int stepX = (int) Math.signum(center.getX() + 0.5D - mob.getX());
        int stepZ = (int) Math.signum(center.getZ() + 0.5D - mob.getZ());
        int width = Math.max(1, Math.min(3, config.maxWidth()));
        int maxDepth = Math.max(1, config.maxDepth());
        for (int ahead = 1; ahead <= 2; ahead++) {
            for (int side = -(width / 2); side <= width / 2; side++) {
                int x = base.getX() + stepX * ahead + (stepZ != 0 ? side : 0);
                int z = base.getZ() + stepZ * ahead + (stepX != 0 ? side : 0);
                BlockPos column = new BlockPos(x, base.getY(), z);
                if (Territory.isProtectedZone(center, column, protectedRadius)) {
                    continue;
                }
                if (!level.isLoaded(column)) {
                    continue;
                }
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int depth = base.getY() - ground;
                if (depth < 2 || depth > maxDepth) {
                    continue;
                }
                BlockPos pos = new BlockPos(x, ground, z);
                if (!level.getBlockState(pos).isAir()) {
                    continue;
                }
                return pos;
            }
        }
        return null;
    }

    private static Block resolveBlock(String id) {
        ResourceLocation location = id == null ? null : ResourceLocation.tryParse(id.trim());
        if (location == null) {
            return Blocks.COBBLESTONE;
        }
        Block block = BuiltInRegistries.BLOCK.get(location);
        return block == null || block == Blocks.AIR ? Blocks.COBBLESTONE : block;
    }
}
