package dev.modmind.irontide.raid;

import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.config.RaidConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 攻核怪拆墙（W4「破坏白名单」）。
 * <p>
 * 只拆 {@code raids.json → break.whitelist} 里的方块：设计口径是「木墙会被拆，铁墙不会」。
 * 每只怪按自己的 {@code intervalTicks} 朝核心方向找一格，命中白名单就拆掉，全局每 tick 有
 * {@code maxPerTick} 的预算封顶，避免几十只怪同 tick 一起改方块把主线程拖住。
 * <p>
 * 安全边界：不在白名单里的一律不碰——核心壳、机组、玩家机器（铁矿/铜块/机器方块）默认全在名单外。
 */
public final class RaidBreaker {
    private RaidBreaker() {
    }

    public static void tick(ServerLevel level, RaidConfig.Breaker config,
                            Collection<UUID> raiders, List<AndesiteCoreBlockEntity> cores) {
        if (!config.enabled() || config.maxPerTick() <= 0 || cores.isEmpty()) {
            return;
        }
        int budget = config.maxPerTick();
        double rangeSq = config.range() * config.range();
        for (UUID uuid : raiders) {
            if (budget <= 0) {
                return;
            }
            Entity entity = level.getEntity(uuid);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                continue;
            }
            if (mob.tickCount % Math.max(1, config.intervalTicks()) != 0) {
                continue;
            }
            AndesiteCoreBlockEntity core = CoreNetwork.nearest(cores, mob.getX(), mob.getY(), mob.getZ());
            if (core == null) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(core.getBlockPos());
            if (mob.position().distanceToSqr(center) > rangeSq) {
                continue;
            }
            BlockPos candidate = blockingBlock(level, mob, center);
            if (candidate == null) {
                continue;
            }
            BlockState state = level.getBlockState(candidate);
            if (!isWhitelisted(state, config.whitelist())) {
                continue;
            }
            level.destroyBlock(candidate, false, mob);
            level.playSound(null, candidate, state.getSoundType().getBreakSound(),
                    SoundSource.HOSTILE, 0.8F, 0.9F);
            budget--;
        }
    }

    /** 朝核心方向的下一格：先看脚下正前方，再看头上一格，最后退化成单轴。 */
    private static BlockPos blockingBlock(ServerLevel level, Mob mob, Vec3 target) {
        double dx = target.x - mob.getX();
        double dz = target.z - mob.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.01D) {
            return null;
        }
        int stepX = (int) Math.signum(dx / length);
        int stepZ = (int) Math.signum(dz / length);
        BlockPos base = mob.blockPosition();
        BlockPos[] candidates = {
                base.offset(stepX, 0, stepZ),
                base.offset(stepX, 1, stepZ),
                base.offset(0, 0, stepZ),
                base.offset(stepX, 0, 0),
                base.offset(stepX, 2, stepZ)
        };
        for (BlockPos candidate : candidates) {
            if (!level.getBlockState(candidate).isAir()) {
                return candidate;
            }
        }
        return null;
    }

    /** 白名单匹配：{@code #namespace:tag} 走方块标签，其余按方块 id 精确匹配。 */
    public static boolean isWhitelisted(BlockState state, List<String> whitelist) {
        for (String entry : whitelist) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String text = entry.trim();
            if (text.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(text.substring(1));
                if (id == null) {
                    continue;
                }
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
                if (state.is(tag)) {
                    return true;
                }
            } else {
                ResourceLocation id = ResourceLocation.tryParse(text);
                if (id != null && BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }
}