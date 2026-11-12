package dev.modmind.irontide.multiblock;

import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.block.ShellBlock;
import dev.modmind.irontide.block.ShellType;
import dev.modmind.irontide.config.CoreConfig;
import dev.modmind.irontide.data.IronTideData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

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
        ResourceLocation andesiteId = config.andesiteCasing().id();
        ResourceLocation brassId = config.brassCasing().id();
        ResourceLocation shellToId = definition.shellToId();

        int matched = 0;
        int shellCells = 0;
        int andesiteCells = 0;
        int brassCells = 0;
        List<StructureCheckResult.Missing> missing = new ArrayList<>();

        for (MultiblockDefinition.Cell cell : layout) {
            BlockPos pos = anchor.offset(cell.dx(), cell.dy(), cell.dz());
            boolean shellCell = cell.expected().equals(andesiteId) || cell.expected().equals(brassId);
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
            // 的安山格也可能被玩家放成黄铜，成型时按当前方块决定壳类型。
            if (!accepted && shellCell && actualBlock instanceof ShellBlock && config.hasFormedCasing()) {
                accepted = true;
            }

            if (accepted) {
                matched++;
            } else {
                missing.add(new StructureCheckResult.Missing(pos, cell.expected(), cell.symbol()));
            }

            if (shellCell) {
                if (actualBlock instanceof ShellBlock) {
                    ShellType type = state.getValue(ShellBlock.TYPE);
                    if (type == ShellType.ANDESITE) {
                        andesiteCells++;
                    } else if (type == ShellType.BRASS) {
                        brassCells++;
                    }
                } else if (actual.equals(andesiteId)) {
                    andesiteCells++;
                } else if (actual.equals(brassId)) {
                    brassCells++;
                }
            }
        }

        double brassRatio = shellCells == 0 ? 0.0D : (double) brassCells / (double) shellCells;
        double healthMul = config.healthMulFor(brassRatio);
        double armor = config.armorFor(brassRatio);
        return new StructureCheckResult(definition, missing.isEmpty(), layout.size(), matched,
                shellCells, andesiteCells, brassCells, List.copyOf(missing), brassRatio, healthMul, armor,
                layoutIndex);
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