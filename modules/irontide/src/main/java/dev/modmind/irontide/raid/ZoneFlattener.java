package dev.modmind.irontide.raid;

import dev.modmind.irontide.ModMindEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 主核成型时的地形平整（W7-B6）。
 * <p>
 * 只在核心成型那一刻跑一次，也可用 {@code /irontide flatten} 手动重跑。
 * 规则（保守优先）：
 * <ul>
 *   <li>只在保护半径内（{@code r<=R}）工作，每 tick 最多处理 64 列；</li>
 *   <li>只削「自然方块」：草/土/石/沙/砂砾/雪/水/叶与常见草花；</li>
 *   <li>一列里只要出现玩家方块、带 BlockEntity 的方块（容器/告示牌/机器），整列跳过；</li>
 *   <li>最多向下削 12 格；浅坑（≤ 3 格）用泥土补回基准面。</li>
 * </ul>
 * 基准面取核心方块自身的 y。
 */
public final class ZoneFlattener {

    private static final int COLUMNS_PER_TICK = 64;
    private static final int MAX_CUT = 12;
    private static final int MAX_FILL = 3;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final Set<String> NATURAL = Set.of(
            "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:podzol", "minecraft:mycelium", "minecraft:dirt_path",
            "minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate", "minecraft:tuff",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:calcite",
            "minecraft:gravel", "minecraft:sand", "minecraft:red_sand", "minecraft:sandstone",
            "minecraft:snow", "minecraft:snow_block", "minecraft:powder_snow",
            "minecraft:ice", "minecraft:packed_ice", "minecraft:water",
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
            "minecraft:dead_bush", "minecraft:seagrass", "minecraft:kelp", "minecraft:kelp_plant",
            "minecraft:dandelion", "minecraft:poppy", "minecraft:cornflower", "minecraft:azure_bluet",
            "minecraft:oxeye_daisy", "minecraft:allium", "minecraft:lily_of_the_valley"
    );

    /** 成型后自动平整的总开关（可用 {@code /irontide flatten auto off} 关掉）。 */
    private static boolean autoEnabled = true;

    private static ServerLevel level;
    private static List<BlockPos> columns = List.of();
    private static int cursor;
    private static int targetY;
    private static int removed;
    private static int placed;
    private static boolean running;

    private ZoneFlattener() {
    }

    public static boolean isAutoEnabled() {
        return autoEnabled;
    }

    public static void setAutoEnabled(boolean enabled) {
        autoEnabled = enabled;
    }

    public static boolean isRunning() {
        return running;
    }

    /** 成型时调用：自动开关关掉就不动。 */
    public static boolean startAuto(ServerLevel serverLevel, BlockPos center, int radius) {
        return autoEnabled && start(serverLevel, center, radius, center.getY());
    }

    /**
     * 启动一次平整任务。返回 false 表示已经有任务在跑。
     */
    public static boolean start(ServerLevel serverLevel, BlockPos center, int radius, int baseY) {
        if (running || serverLevel == null || center == null) {
            return false;
        }
        int r = Math.max(1, radius);
        List<BlockPos> list = new ArrayList<>((2 * r + 1) * (2 * r + 1));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                list.add(new BlockPos(center.getX() + dx, baseY, center.getZ() + dz));
            }
        }
        level = serverLevel;
        columns = list;
        cursor = 0;
        targetY = baseY;
        removed = 0;
        placed = 0;
        running = true;
        ModMindEntry.LOGGER.info("[IronTide] flatten started: center={} radius={} baseY={} columns={}",
                center, r, baseY, list.size());
        return true;
    }

    public static void cancel() {
        running = false;
        columns = List.of();
        level = null;
        cursor = 0;
    }

    /** 每 server tick 调一次；不在跑就只做一次布尔判断。 */
    public static void tick() {
        if (!running || level == null) {
            return;
        }
        int processed = 0;
        while (cursor < columns.size() && processed < COLUMNS_PER_TICK) {
            flattenColumn(columns.get(cursor++));
            processed++;
        }
        if (cursor >= columns.size()) {
            running = false;
            ModMindEntry.LOGGER.info("[IronTide] flatten finished: removed={} placed={} columns={}",
                    removed, placed, columns.size());
            columns = List.of();
            level = null;
        }
    }

    private static void flattenColumn(BlockPos column) {
        // 1) 往上削：出现玩家方块/带 BE 的方块就整列放弃，避免把建筑削坏
        for (int y = targetY + MAX_CUT; y > targetY; y--) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            if (!level.isLoaded(pos)) {
                return;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (level.getBlockEntity(pos) != null || !isNatural(state)) {
                return;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            removed++;
        }
        // 2) 往下补：只在基准面是空气、坑深 ≤ MAX_FILL、且下面那层是自然方块时填土
        BlockPos top = new BlockPos(column.getX(), targetY, column.getZ());
        if (!level.isLoaded(top) || !level.getBlockState(top).isAir()) {
            return;
        }
        int floor = -1;
        for (int y = targetY - 1; y >= targetY - MAX_FILL; y--) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (isNatural(state) && level.getBlockEntity(pos) == null) {
                floor = y;
            }
            break;
        }
        if (floor < 0) {
            return;
        }
        for (int y = floor + 1; y <= targetY; y++) {
            level.setBlock(new BlockPos(column.getX(), y, column.getZ()),
                    Blocks.DIRT.defaultBlockState(), FLAGS);
            placed++;
        }
    }

    private static boolean isNatural(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            return false;
        }
        String path = id.toString();
        return NATURAL.contains(path) || path.endsWith("_leaves");
    }
}
