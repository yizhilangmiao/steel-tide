package dev.modmind.irontide.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record StructureCheckResult(
        MultiblockDefinition definition,
        boolean complete,
        int totalCells,
        int matchedCells,
        int shellCells,
        int andesiteCells,
        int brassCells,
        List<Missing> missing,
        double brassRatio,
        double healthMul,
        double armor,
        int layoutIndex
) {
    public int missingCount() {
        return missing.size();
    }

    public String layoutLabel() {
        return MultiblockDefinition.layoutLabel(layoutIndex);
    }

    public String missingByExpected() {
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (Missing cell : missing) {
            counts.merge(cell.expected(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    public record Missing(BlockPos pos, ResourceLocation expected, char symbol) {
    }
}