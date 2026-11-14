package dev.modmind.irontide.raid;

import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.config.RaidConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 攻核怪拆墙（W4 领地分区）。
 * <p>
 * 新口径：{@code r>=27} 的任意方块都能被拆，{@code r<=26} 一律不碰；永久保护名单
 * （核心、壳、基岩、命令方块、结构方块）写在 {@link Territory} 里，不再依赖配置白名单。
 * <p>
 * 拆墙按<b>硬度累积</b>：每敲一次进度 +1，需求次数 = {@code clamp(ceil(硬度 × 4), 4, maxProgress)}，
 * 所以木墙快、石墙中、铁块慢、黑曜石最终也能破（封顶 {@code maxProgress} 次）。
 * <p>
 * 性能边界：进度表每波清空、最多记 {@value #MAX_TRACKED} 格；每次调用最多拆 {@code maxPerTick} 格，
 * 不会因几十只怪同 tick 一起改方块拖主线程。
 */
public final class RaidBreaker {
    /** 进度表上限：超过就直接清空重来，避免玩家乱搭的方块把表撑大。 */
    private static final int MAX_TRACKED = 256;
    /** 硬度换算系数：木板（2.0）×4 = 8 次，铁块（5.0）×4 = 20 次，黑曜石（50）×4 封顶 120 次。 */
    private static final double HIT_SCALE = 4.0D;

    private static final Map<Long, Integer> progress = new HashMap<>();

    private RaidBreaker() {
    }

    /** 每波开始/结束时清空进度表（设计：拆墙进度表每波清空、上限 256 格）。 */
    public static void reset() {
        progress.clear();
    }

    public static void tick(ServerLevel level, RaidConfig.Breaker config, int protectedRadius,
                            BlockPos center, Collection<UUID> raiders,
                            List<AndesiteCoreBlockEntity> cores) {
        if (!config.enabled() || config.maxPerTick() <= 0 || center == null) {
            return;
        }
        if (progress.size() > MAX_TRACKED) {
            progress.clear();
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
            Vec3 coreCenter = Vec3.atCenterOf(core.getBlockPos());
            if (mob.position().distanceToSqr(coreCenter) > rangeSq) {
                continue;
            }
            BlockPos candidate = blockingBlock(level, mob, coreCenter);
            if (candidate == null) {
                continue;
            }
            BlockState state = level.getBlockState(candidate);
            if (state.isAir() || Territory.isDenied(state)) {
                continue;
            }
            if (!Territory.isBreakable(center, candidate, protectedRadius)) {
                continue;
            }
            int required = requiredHits(level, candidate, state, config.maxProgress());
            if (required <= 0) {
                continue;
            }
            long key = candidate.asLong();
            int done = progress.merge(key, 1, Integer::sum);
            if (done < required) {
                hitFeedback(level, candidate, state);
                budget--;
                continue;
            }
            progress.remove(key);
            level.destroyBlock(candidate, false, mob);
            level.playSound(null, candidate, state.getSoundType().getBreakSound(),
                    SoundSource.HOSTILE, 0.8F, 0.9F);
            budget--;
        }
    }

    /** 需求次数：硬度 × 系数，至少 4 次，封顶 {@code maxProgress}。硬度为负（不可破坏）返回 0。 */
    private static int requiredHits(ServerLevel level, BlockPos pos, BlockState state, int maxProgress) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return 0;
        }
        int cap = Math.max(1, maxProgress);
        int hits = (int) Math.ceil(Math.max(0.0F, hardness) * HIT_SCALE);
        return Math.max(4, Math.min(cap, hits));
    }

    /** 敲墙反馈：方块碎屑 + 命中音效。 */
    private static void hitFeedback(ServerLevel level, BlockPos pos, BlockState state) {
        level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.5F, 1.0F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                3, 0.25D, 0.25D, 0.25D, 0.02D);
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
            BlockState state = level.getBlockState(candidate);
            if (!state.isAir() && !Territory.isDenied(state)) {
                return candidate;
            }
        }
        return null;
    }
}