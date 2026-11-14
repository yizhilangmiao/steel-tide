package dev.modmind.irontide.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 领地分区视觉（W4 领地分区）。
 * <p>
 * 三条边界：绿环 {@code #3CFF6E} 在 {@code r=25}，黄环 {@code #FFD23C} 在 {@code r=27} 与 {@code r=40}。
 * 每 2–3 格一个点，地表高度缓存 5 秒（100 tick），只发给 {@code 64} 格内的玩家，
 * 每 10 tick 发一次；另带 8 向低光柱方便远处定位。
 * <p>
 * 只在主核 ACTIVE 时开启；停机/摧毁/怪潮结束时 {@link #deactivate()}。
 */
public final class ZoneVisuals {

    private static final int INTERVAL_TICKS = 10;
    private static final int HEIGHT_CACHE_TICKS = 100;
    private static final int PLAYER_RANGE = 64;
    private static final int RING_STEP = 3;

    private static final ParticleOptions GREEN =
            new DustParticleOptions(new Vector3f(0x3C / 255.0F, 0xFF / 255.0F, 0x6E / 255.0F), 1.0F);
    private static final ParticleOptions YELLOW =
            new DustParticleOptions(new Vector3f(0xFF / 255.0F, 0xD2 / 255.0F, 0x3C / 255.0F), 1.0F);

    private static final Map<Long, Integer> heightCache = new HashMap<>();
    /** 玩家上次是否在保护区内，用来只在他真的跨过边界那一刻提醒一次。 */
    private static final Map<UUID, Boolean> insideTracker = new HashMap<>();

    private static boolean active;
    private static BlockPos center;
    private static int counter;
    private static long heightCacheTick = Long.MIN_VALUE;
    /** 绿环保护区边界（= 当前档位的保护半径，T4 为 26）。 */
    private static int greenRing = 26;
    /** 黄环：防御区内沿与外沿（T4 为 27 与 40）。 */
    private static int[] yellowRings = {27, 40};

    private ZoneVisuals() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void activate(BlockPos corePos, TerritoryScale.Ring ring) {
        if (corePos == null) {
            return;
        }
        int green = ring == null ? 26 : ring.protectedRadius();
        int[] yellow = ring == null
                ? new int[]{27, 40}
                : new int[]{ring.defenseStart(), ring.defenseEnd()};
        boolean changed = !active || !corePos.equals(center)
                || green != greenRing || !java.util.Arrays.equals(yellow, yellowRings);
        if (changed) {
            heightCache.clear();
            heightCacheTick = Long.MIN_VALUE;
            insideTracker.clear();
        }
        center = corePos.immutable();
        greenRing = green;
        yellowRings = yellow;
        active = true;
    }

    public static void deactivate() {
        active = false;
        center = null;
        counter = 0;
        heightCache.clear();
        heightCacheTick = Long.MIN_VALUE;
        insideTracker.clear();
    }

    public static void tick(ServerLevel level, int step) {
        if (!active || center == null) {
            return;
        }
        counter += Math.max(1, step);
        if (counter < INTERVAL_TICKS) {
            return;
        }
        counter = 0;
        long now = level.getGameTime();
        if (now - heightCacheTick >= HEIGHT_CACHE_TICKS || heightCacheTick == Long.MIN_VALUE) {
            heightCache.clear();
            heightCacheTick = now;
        }
        emitRing(level, greenRing, GREEN, now);
        for (int radius : yellowRings) {
            emitRing(level, radius, YELLOW, now);
        }
        emitPillars(level);
        emitCornerBeams(level);
        updateBoundaryCrossing(level);
    }

    /** 四角高光柱：从地表一直拉到 14 格高，远处一眼就能定位领地四角。 */
    private static void emitCornerBeams(ServerLevel level) {
        int cx = center.getX();
        int cz = center.getZ();
        int[][] corners = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] corner : corners) {
            int x = cx + corner[0] * greenRing;
            int z = cz + corner[1] * greenRing;
            BlockPos column = new BlockPos(x, center.getY(), z);
            if (!level.isLoaded(column)) {
                continue;
            }
            int ground = surface(level, x, z) + 1;
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(x + 0.5D, ground, z + 0.5D) > (double) PLAYER_RANGE * PLAYER_RANGE) {
                    continue;
                }
                for (int dy = 0; dy < 14; dy += 2) {
                    level.sendParticles(player, ParticleTypes.END_ROD, true,
                            x + 0.5D, ground + dy, z + 0.5D, 2, 0.05D, 0.0D, 0.05D, 0.0D);
                }
            }
        }
    }

    /** 穿越保护边界时给本人一条 actionbar 提示；只在「跨过」的那一下发，不刷屏。 */
    private static void updateBoundaryCrossing(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            double radius = Territory.radius(center, player.getX(), player.getZ());
            boolean inside = radius <= greenRing + 0.5D;
            Boolean previous = insideTracker.put(player.getUUID(), inside);
            if (previous == null || previous == inside) {
                continue;
            }
            if (inside) {
                player.displayClientMessage(Component.literal("§a已进入主核保护区（怪不会进来）"), true);
            } else {
                player.displayClientMessage(Component.literal("§e已离开保护区，进入防御区"), true);
            }
        }
    }

    /** 方形环：只画四条边，每 {@value #RING_STEP} 格一个点。 */
    private static void emitRing(ServerLevel level, int radius, ParticleOptions particle, long now) {
        int cx = center.getX();
        int cz = center.getZ();
        for (int offset = -radius; offset <= radius; offset += RING_STEP) {
            emitPoint(level, cx + offset, cz + radius, particle);
            emitPoint(level, cx + offset, cz - radius, particle);
            if (offset != -radius && offset != radius) {
                emitPoint(level, cx + radius, cz + offset, particle);
                emitPoint(level, cx - radius, cz + offset, particle);
            }
        }
    }

    private static void emitPoint(ServerLevel level, int x, int z, ParticleOptions particle) {
        BlockPos column = new BlockPos(x, center.getY(), z);
        if (!level.isLoaded(column)) {
            return;
        }
        int y = surface(level, x, z) + 1;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x + 0.5D, y, z + 0.5D) > (double) PLAYER_RANGE * PLAYER_RANGE) {
                continue;
            }
            level.sendParticles(player, particle, true, x + 0.5D, y, z + 0.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** 8 向低光柱：只画到地表上方 6 格，不影响可读性。 */
    private static void emitPillars(ServerLevel level) {
        int cx = center.getX();
        int cz = center.getZ();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] dir : dirs) {
            int x = cx + dir[0] * greenRing;
            int z = cz + dir[1] * greenRing;
            BlockPos column = new BlockPos(x, center.getY(), z);
            if (!level.isLoaded(column)) {
                continue;
            }
            int ground = surface(level, x, z) + 1;
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(x + 0.5D, ground, z + 0.5D) > (double) PLAYER_RANGE * PLAYER_RANGE) {
                    continue;
                }
                for (int dy = 0; dy < 6; dy += 2) {
                    level.sendParticles(player, ParticleTypes.END_ROD, true,
                            x + 0.5D, ground + dy, z + 0.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
            }
        }
    }

    private static int surface(ServerLevel level, int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        Integer cached = heightCache.get(key);
        if (cached != null) {
            return cached;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        heightCache.put(key, y);
        return y;
    }
}
