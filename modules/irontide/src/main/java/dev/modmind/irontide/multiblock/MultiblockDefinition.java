package dev.modmind.irontide.multiblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmind.irontide.config.JsonSupport;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MultiblockDefinition {
    public record Cell(int dx, int dy, int dz, char symbol, ResourceLocation expected) {
    }

    private final String id;
    private final String tier;
    private final int edge;
    private final ResourceLocation anchor;
    private final boolean allowRotation;
    private final Map<Character, ResourceLocation> legend;
    private final char controllerMarker;
    private final char stressOutputMarker;
    private final Character shellFrom;
    private final Character shellTo;
    private final List<List<Cell>> layouts;
    private final String source;

    private MultiblockDefinition(String id, String tier, int edge, ResourceLocation anchor, boolean allowRotation,
                                 Map<Character, ResourceLocation> legend, char controllerMarker, char stressOutputMarker,
                                 Character shellFrom, Character shellTo, List<List<Cell>> layouts, String source) {
        this.id = id;
        this.tier = tier;
        this.edge = edge;
        this.anchor = anchor;
        this.allowRotation = allowRotation;
        this.legend = legend;
        this.controllerMarker = controllerMarker;
        this.stressOutputMarker = stressOutputMarker;
        this.shellFrom = shellFrom;
        this.shellTo = shellTo;
        this.layouts = layouts;
        this.source = source;
    }

    public String id() {
        return id;
    }

    public String tier() {
        return tier;
    }

    public int edge() {
        return edge;
    }

    public ResourceLocation anchor() {
        return anchor;
    }

    public boolean allowRotation() {
        return allowRotation;
    }

    public Map<Character, ResourceLocation> legend() {
        return legend;
    }

    public char controllerMarker() {
        return controllerMarker;
    }

    public char stressOutputMarker() {
        return stressOutputMarker;
    }

    public Character shellFrom() {
        return shellFrom;
    }

    public Character shellTo() {
        return shellTo;
    }

    public ResourceLocation shellToId() {
        return shellTo == null ? null : legend.get(shellTo);
    }

    /** 主布局（rot0）。只需要一套坐标的调用方用它。 */
    public List<Cell> cells() {
        return layouts.get(0);
    }

    /** 所有候选布局：rotatable=false 时只有一套，true 时是 rot0/rot90/rot180/rot270。 */
    public List<List<Cell>> layouts() {
        return layouts;
    }

    /** 所有布局的去重格子并集，用于反推可能的锚点位置。 */
    public List<Cell> layoutCells() {
        if (layouts.size() == 1) {
            return layouts.get(0);
        }
        Map<String, Cell> unique = new LinkedHashMap<>();
        for (List<Cell> layout : layouts) {
            for (Cell cell : layout) {
                unique.putIfAbsent(cell.dx() + "," + cell.dy() + "," + cell.dz(), cell);
            }
        }
        return List.copyOf(unique.values());
    }

    public String layoutLabel() {
        return layouts.size() > 1 ? "rotatable" : "fixed";
    }

    public static String layoutLabel(int index) {
        return switch (index) {
            case 0 -> "rot0";
            case 1 -> "rot90";
            case 2 -> "rot180";
            case 3 -> "rot270";
            default -> "rot" + (index * 90);
        };
    }

    public String source() {
        return source;
    }

    public Cell controllerCell() {
        for (Cell cell : cells()) {
            if (cell.dx() == 0 && cell.dy() == 0 && cell.dz() == 0) {
                return cell;
            }
        }
        return null;
    }

    public static MultiblockDefinition parse(JsonObject root, String source) {
        String id = JsonSupport.string(root, "id", source + ".id");
        String tier = JsonSupport.string(root, "tier", source + ".tier");
        int edge = JsonSupport.integer(root, "edge", source + ".edge");
        if (edge <= 0) {
            throw JsonSupport.invalid(source + ".edge", "must be > 0, was " + edge);
        }

        String anchorText = JsonSupport.string(root, "anchor", source + ".anchor");
        ResourceLocation anchor = ResourceLocation.tryParse(anchorText);
        if (anchor == null) {
            throw JsonSupport.invalid(source + ".anchor", "invalid resource location '" + anchorText + "'");
        }
        boolean allowRotation = JsonSupport.bool(root, "allowRotation", source + ".allowRotation");

        JsonObject legendObject = JsonSupport.object(root, "legend", source + ".legend");
        Map<Character, ResourceLocation> legend = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : legendObject.entrySet()) {
            if (entry.getKey().length() != 1) {
                throw JsonSupport.invalid(source + ".legend", "keys must be single characters, found '" + entry.getKey() + "'");
            }
            String blockId = entry.getValue().getAsString();
            ResourceLocation blockKey = ResourceLocation.tryParse(blockId);
            if (blockKey == null) {
                throw JsonSupport.invalid(source + ".legend." + entry.getKey(), "invalid resource location '" + blockId + "'");
            }
            legend.put(entry.getKey().charAt(0), blockKey);
        }

        JsonObject markers = JsonSupport.object(root, "markers", source + ".markers");
        char controllerMarker = singleCharacter(JsonSupport.string(markers, "controller", source + ".markers.controller"),
                source + ".markers.controller");
        char stressOutputMarker = singleCharacter(JsonSupport.string(markers, "stressOutput", source + ".markers.stressOutput"),
                source + ".markers.stressOutput");
        Character shellFrom = null;
        Character shellTo = null;
        if (markers.has("shellSwap")) {
            JsonArray swap = markers.getAsJsonArray("shellSwap");
            if (swap == null || swap.size() != 2) {
                throw JsonSupport.invalid(source + ".markers.shellSwap", "must be an array of two single-character strings");
            }
            shellFrom = singleCharacter(swap.get(0).getAsString(), source + ".markers.shellSwap[0]");
            shellTo = singleCharacter(swap.get(1).getAsString(), source + ".markers.shellSwap[1]");
            if (!legend.containsKey(shellFrom) || !legend.containsKey(shellTo)) {
                throw JsonSupport.invalid(source + ".markers.shellSwap", "both characters must exist in legend");
            }
        }

        JsonObject layersObject = JsonSupport.object(root, "layers", source + ".layers");
        Map<Integer, List<String>> layers = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : layersObject.entrySet()) {
            int y;
            try {
                y = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException exception) {
                throw JsonSupport.invalid(source + ".layers", "layer key must be an integer Y offset, found '" + entry.getKey() + "'");
            }
            if (!entry.getValue().isJsonArray()) {
                throw JsonSupport.invalid(source + ".layers." + entry.getKey(), "must be an array of strings");
            }
            List<String> rows = new ArrayList<>();
            for (JsonElement row : entry.getValue().getAsJsonArray()) {
                if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString()) {
                    throw JsonSupport.invalid(source + ".layers." + entry.getKey(), "rows must be strings");
                }
                rows.add(row.getAsString());
            }
            layers.put(y, rows);
        }
        if (layers.isEmpty()) {
            throw JsonSupport.invalid(source + ".layers", "must contain at least one layer");
        }

        int controllerCount = 0;
        int controllerLayer = 0;
        int controllerRow = -1;
        int controllerColumn = -1;
        for (Map.Entry<Integer, List<String>> layer : layers.entrySet()) {
            List<String> rows = layer.getValue();
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                for (int x = 0; x < row.length(); x++) {
                    if (row.charAt(x) == controllerMarker) {
                        controllerCount++;
                        controllerLayer = layer.getKey();
                        controllerRow = z;
                        controllerColumn = x;
                    }
                }
            }
        }
        if (controllerCount != 1) {
            throw JsonSupport.invalid(source, "expected exactly one controller marker '" + controllerMarker
                    + "', found " + controllerCount);
        }
        if (controllerLayer != 0) {
            throw JsonSupport.invalid(source, "controller marker must be on layer Y=0, found Y=" + controllerLayer);
        }

        List<Cell> cells = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> layer : layers.entrySet()) {
            List<String> rows = layer.getValue();
            if (rows.size() != edge) {
                throw JsonSupport.invalid(source + ".layers." + layer.getKey(),
                        "expected " + edge + " rows, found " + rows.size());
            }
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                if (row.length() != edge) {
                    throw JsonSupport.invalid(source + ".layers." + layer.getKey() + "[" + z + "]",
                            "expected " + edge + " columns, found " + row.length());
                }
                for (int x = 0; x < row.length(); x++) {
                    char symbol = row.charAt(x);
                    ResourceLocation expected = legend.get(symbol);
                    if (expected == null) {
                        throw JsonSupport.invalid(source + ".legend", "missing legend entry for character '" + symbol + "'");
                    }
                    cells.add(new Cell(x - controllerColumn, layer.getKey() - controllerLayer, z - controllerRow,
                            symbol, expected));
                }
            }
        }
        cells.sort(Comparator.comparingInt(Cell::dy).thenComparingInt(Cell::dz).thenComparingInt(Cell::dx));

        Cell anchorCell = null;
        for (Cell cell : cells) {
            if (cell.dx() == 0 && cell.dy() == 0 && cell.dz() == 0) {
                anchorCell = cell;
                break;
            }
        }
        if (anchorCell == null || anchorCell.symbol() != controllerMarker) {
            throw JsonSupport.invalid(source, "controller marker must be at relative anchor (0,0,0)");
        }
        if (!anchorCell.expected().equals(anchor)) {
            throw JsonSupport.invalid(source + ".anchor", "anchor id must match legend['" + controllerMarker
                    + "'] (" + anchorCell.expected() + ")");
        }

        List<List<Cell>> layouts = buildLayouts(cells, allowRotation, source);
        return new MultiblockDefinition(id, tier, edge, anchor, allowRotation, Map.copyOf(legend),
                controllerMarker, stressOutputMarker, shellFrom, shellTo, layouts, source);
    }

    private static final int ROTATION_STEPS = 4;

    /**
     * 围绕控制器（相对坐标恒为 0,0,0）在 X-Z 平面上生成 4 个 90 度间隔的等价布局。
     * 结构相对控制器不对称（控制器贴在一条边上），所以旋转后控制器会落到另一条边的中点，
     * 玩家从四个方向搭同一个结构都能匹配。
     */
    private static List<List<Cell>> buildLayouts(List<Cell> base, boolean allowRotation, String source) {
        List<List<Cell>> layouts = new ArrayList<>(ROTATION_STEPS);
        layouts.add(base);
        if (!allowRotation) {
            return List.copyOf(layouts);
        }
        for (int step = 1; step < ROTATION_STEPS; step++) {
            layouts.add(rotate(base, step));
        }
        if (!sameLayout(rotate(base, ROTATION_STEPS), base)) {
            throw JsonSupport.invalid(source + ".allowRotation",
                    "layout does not return to its original shape after 4 x 90 degree steps");
        }
        return List.copyOf(layouts);
    }

    /** (dx,dz) -> (-dz,dx) 为俯视顺时针 90 度；控制器本身恒在原点。 */
    private static List<Cell> rotate(List<Cell> base, int step) {
        List<Cell> rotated = new ArrayList<>(base.size());
        for (Cell cell : base) {
            int dx = cell.dx();
            int dz = cell.dz();
            for (int i = 0; i < step; i++) {
                int nextDx = -dz;
                int nextDz = dx;
                dx = nextDx;
                dz = nextDz;
            }
            rotated.add(new Cell(dx, cell.dy(), dz, cell.symbol(), cell.expected()));
        }
        rotated.sort(Comparator.comparingInt(Cell::dy).thenComparingInt(Cell::dz).thenComparingInt(Cell::dx));
        return List.copyOf(rotated);
    }

    private static boolean sameLayout(List<Cell> left, List<Cell> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            Cell a = left.get(i);
            Cell b = right.get(i);
            if (a.dx() != b.dx() || a.dy() != b.dy() || a.dz() != b.dz() || a.symbol() != b.symbol()) {
                return false;
            }
        }
        return true;
    }

    private static char singleCharacter(String value, String path) {
        if (value.length() != 1) {
            throw JsonSupport.invalid(path, "must be a single character, found '" + value + "'");
        }
        return value.charAt(0);
    }
}