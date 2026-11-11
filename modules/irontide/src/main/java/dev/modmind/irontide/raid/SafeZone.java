package dev.modmind.irontide.raid;

import dev.modmind.irontide.ModMindEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashSet;
import java.util.Set;

/**
 * 安全区：以核心为中心的立方体，水平半宽 {@code radius}、竖直相对核心上下各 {@code halfHeight}。
 * <p>
 * 两层职责：
 * <ol>
 *   <li><b>机制层</b>——区内不刷怪。{@link #contains(int, int, int)} 由刷怪事件调用，这是硬保证，
 *       即使光全关掉也成立。</li>
 *   <li><b>视觉层</b>——地表每隔几格放一颗隐形光方块（{@code minecraft:light}，亮度可调），
 *       外加四向天空光柱，让玩家一眼看出边界在哪。</li>
 * </ol>
 * 只在怪潮夜里激活，天亮后 {@link #deactivate} 撤销并把自己放的光方块收走，
 * 只动 {@link #placed} 里记录过的位置，不碰玩家自己放的光源。
 * <p>
 * 用光方块而不是直接改光照数据层：1.21.1 的 section 不对外暴露 light 层，改数据层要走内部 API，
 * 而且写进去的光会跟着区块存档，异常退出就清不掉了。光方块是普通方块，存档里看得见、删得干净。
 */
public final class SafeZone {
    /** 光方块之间的水平间隔目标密度：整个区域大约按 18×18 个光点铺。 */
    private static final int LIGHT_GRID = 18;

    /** {@link #tick} 的调用间隔，与 {@code RaidScheduler.CHECK_INTERVAL} 保持一致。 */
    private static final int TICK_STEP = 20;

    private static boolean active;
    private static int centerX;
    private static int centerY;
    private static int centerZ;
    private static int radius;
    private static int halfHeight;
    private static int lightLevel;
    private static int beaconCount;
    private static int particleIntervalTicks;

    private static int particleCooldown;
    private static boolean lightPending;
    private static final Set<Long> placed = new HashSet<>();

    private SafeZone() {
    }

    public static boolean isActive() {
        return active;
    }

    /** 水平半宽与竖直半高都按立方体判定。 */
    public static boolean contains(int x, int y, int z) {
        if (!active) {
            return false;
        }
        return Math.abs(x - centerX) <= radius
                && Math.abs(z - centerZ) <= radius
                && Math.abs(y - centerY) <= halfHeight;
    }

    public static boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public static void activate(ServerLevel level, BlockPos center, int zoneRadius, int zoneHalfHeight,
                                int zoneLightLevel, int beacons, int intervalTicks) {
        deactivate(level);

        centerX = center.getX();
        centerY = center.getY();
        centerZ = center.getZ();
        radius = Math.max(1, zoneRadius);
        halfHeight = Math.max(1, zoneHalfHeight);
        lightLevel = Math.max(0, Math.min(15, zoneLightLevel));
        beaconCount = Math.max(0, Math.min(4, beacons));
        particleIntervalTicks = Math.max(1, intervalTicks);
        particleCooldown = particleIntervalTicks;
        active = true;
        lightPending = lightLevel > 0;

        ModMindEntry.LOGGER.info(
                "[IronTide] safe zone active at {} radius={} halfHeight={} light={} beacons={}",
                center, radius, halfHeight, lightLevel, beaconCount);
    }

    /** 撤销：把自己放过的光方块收走，再停掉粒子。 */
    public static void deactivate(ServerLevel level) {
        int removed = 0;
        if (level != null) {
            for (long key : placed) {
                BlockPos pos = BlockPos.of(key);
                if (level.getBlockState(pos).is(Blocks.LIGHT)) {
                    level.removeBlock(pos, false);
                    removed++;
                }
            }
        }
        placed.clear();
        if (!active) {
            lightPending = false;
            return;
        }
        active = false;
        lightPending = false;
        particleCooldown = 0;
        ModMindEntry.LOGGER.info("[IronTide] safe zone off, {} light block(s) removed", removed);
    }

    /**
     * 每 tick 调一次：铺光（只铺一次）+ 发粒子光柱。
     * 必须在服务端主线程调用。
     */
    public static void tick(ServerLevel level) {
        if (!active) {
            return;
        }
        if (lightPending) {
            lightPending = false;
            placeLights(level);
        }
        particleCooldown += TICK_STEP;
        if (particleCooldown >= particleIntervalTicks) {
            particleCooldown = 0;
            emitBeacons(level);
        }
    }

    /**
     * 在地表之上铺一层隐形光方块。每隔 {@code stride} 格一颗，7 级光能覆盖到，整片看起来是亮的，
     * 而方块数量控制在几百个。
     */
    private static void placeLights(ServerLevel level) {
        int span = radius * 2 + 1;
        int stride = Math.max(1, (int) Math.ceil(span / (double) LIGHT_GRID));
        int added = 0;
        for (int dx = -radius; dx <= radius; dx += stride) {
            for (int dz = -radius; dz <= radius; dz += stride) {
                int x = centerX + dx;
                int z = centerZ + dz;
                if (!level.isLoaded(new BlockPos(x, centerY, z))) {
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).isAir()) {
                    continue;
                }
                level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel), 3);
                placed.add(pos.asLong());
                added++;
            }
        }
        ModMindEntry.LOGGER.info("[IronTide] safe zone lit with {} light block(s)", added);
    }

    /** 四向天空光柱：东西南北各一根，每 2 格一颗 END_ROD。 */
    private static void emitBeacons(ServerLevel level) {
        if (beaconCount <= 0) {
            return;
        }
        int[][] dirs = {{radius, 0}, {-radius, 0}, {0, radius}, {0, -radius}};
        for (int i = 0; i < beaconCount && i < dirs.length; i++) {
            int x = centerX + dirs[i][0];
            int z = centerZ + dirs[i][1];
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int top = centerY + halfHeight;
            for (int y = ground; y <= top; y += 2) {
                level.sendParticles(ParticleTypes.END_ROD, x + 0.5D, y, z + 0.5D,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }
}
