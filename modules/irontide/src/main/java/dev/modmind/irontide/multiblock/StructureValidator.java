package dev.modmind.irontide.multiblock;

import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.block.ShellBlock;
import dev.modmind.irontide.block.ShellType;
import dev.modmind.irontide.config.CasingSpec;
import dev.modmind.irontide.config.CoreConfig;
import dev.modmind.irontide.data.IronTideData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StructureValidator {
    private StructureValidator() {
    }

    public static StructureCheckResult check(ServerLevel level, BlockPos anchor, MultiblockDefinition definition,
                                             CoreConfig config) {
        List<List<MultiblockDefinition.Cell>> layouts = definition.layouts();
        StructureCheckResult closest = null;
        for (int index = 0; index < layouts.size(); index++) {
            StructureCheckResult result = checkLayout(level, anchor, definition, config, layouts.get(index), index);
            if (result.complete()) {
                return result;
            }
            if (closest == null || result.missingCount() < closest.missingCount()) {
                closest = result;
            }
        }
        return closest;
    }

    /**
     * Checks one concrete layout. When several rotations are possible, the caller reports the
     * closest one so the in-game hints always describe a structure the player can actually finish.
     */
    private static StructureCheckResult checkLayout(ServerLevel level, BlockPos anchor,
                                                    MultiblockDefinition definition, CoreConfig config,
                                                    List<MultiblockDefinition.Cell> layout, int layoutIndex) {
        ResourceLocation shellToId = definition.shellToId();
        int tierLevel = config.tierLevel(definition.tier());

        int matched = 0;
        int shellCells = 0;
        Map<CasingSpec, Integer> casingCounts = new LinkedHashMap<>();
        List<StructureCheckResult.Missing> missing = new ArrayList<>();

        for (MultiblockDefinition.Cell cell : layout) {
            BlockPos pos = anchor.offset(cell.dx(), cell.dy(), cell.dz());
            boolean shellCell = config.isCasingCell(cell.expected());
            if (shellCell) {
                shellCells++;
            }
            if (!level.isLoaded(pos)) {
                missing.add(new StructureCheckResult.Missing(pos, cell.expected(), cell.symbol()));
                continue;
            }

            BlockState state = level.getBlockState(pos);
            Block actualBlock = state.getBlock();
            ResourceLocation actual = BuiltInRegistries.BLOCK.getKey(actualBlock);
            boolean accepted = actual.equals(cell.expected());
            if (!accepted && definition.shellFrom() != null && cell.symbol() == definition.shellFrom()
                    && shellToId != null && actual.equals(shellToId)) {
                accepted = true;
            }
            // 成型外观也算匹配：壳的 type 不要求和 expected 一一对应，因为原来
            // 的安山格也可能被玩家放成黄铜/钢/铜，成型时按当前方块决定壳类型。
            if (!accepted && shellCell && actualBlock instanceof ShellBlock && config.hasFormedCasing()) {
                accepted = true;
            }

            CasingSpec casing = null;
            if (shellCell) {
                casing = actualBlock instanceof ShellBlock
                        ? config.casingForShellType(state.getValue(ShellBlock.TYPE))
                        : config.casingById(actual);
                // 档位门槛：这种材质要求更高档的核心，当前档位不够就当作这一格没搭
                if (casing != null && casing.minTier() > tierLevel) {
                    accepted = false;
                }
            }

            if (accepted) {
                matched++;
                if (casing != null) {
                    casingCounts.merge(casing, 1, Integer::sum);
                }
            } else {
                missing.add(new StructureCheckResult.Missing(pos, cell.expected(), cell.symbol()));
            }
        }

        double healthMul = config.healthMulFor(casingCounts);
        double armor = config.armorFor(casingCounts);
        return new StructureCheckResult(definition, missing.isEmpty(), layout.size(), matched,
                shellCells, List.copyOf(missing), healthMul, armor, layoutIndex,
                casingSummary(casingCounts, shellCells));
    }

    /** "安山 12 / 钢 6"：只列实际出现的材质，顺序跟配置里 casing 的顺序一致。 */
    private static String casingSummary(Map<CasingSpec, Integer> counts, int shellCells) {
        if (shellCells <= 0) {
            return "无";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<CasingSpec, Integer> entry : counts.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(entry.getKey().label()).append(' ').append(entry.getValue());
        }
        return out.length() == 0 ? "未识别" : out.toString();
    }

    /**
     * Revalidates controllers whose structure may contain the changed position. This does not
     * load chunks and is only called from block place/break events.
     */
    public static void revalidateAround(ServerLevel level, BlockPos changed) {
        if (!IronTideData.isLoaded()) {
            return;
        }
        for (MultiblockDefinition definition : IronTideData.definitions().all()) {
            for (MultiblockDefinition.Cell cell : definition.layoutCells()) {
                BlockPos anchor = changed.offset(-cell.dx(), -cell.dy(), -cell.dz());
                if (!level.isLoaded(anchor)) {
                    continue;
                }
                if (level.getBlockEntity(anchor) instanceof AndesiteCoreBlockEntity core
                        && !core.isForming()) {
                    core.validateNow();
                }
            }
        }
    }
}