package dev.modmind.irontide.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.block.ShellType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record CoreConfig(
        double healthBase,
        double minRatioOnUpgrade,
        boolean carryRatioOnUpgrade,
        int fuelCapacity,
        double burnPerSecond,
        double lowFuelRatio,
        int noFuelModuleCapacity,
        double noFuelModuleEfficiency,
        List<CasingSpec> casings,
        double armorCap,
        List<TierSpec> tiers,
        JsonObject stress,
        JsonObject build,
        JsonObject modules,
        List<ModuleSpec> fuelModules,
        List<ModuleSpec> armorModules,
        List<ModuleSpec> beaconModules,
        List<ModuleSpec> transferModules,
        boolean materialSwapEnabled
) {
    public static final String DEFAULT_RESOURCE = "/data/irontide/config_defaults/core.json";

    /** 取某类模块第 {@code tier} 级的数值（tier 从 1 开始，越界自动夹到有效范围）。 */
    public ModuleSpec module(ModuleType type, int tier) {
        List<ModuleSpec> list = switch (type) {
            case FUEL -> fuelModules;
            case ARMOR -> armorModules;
            case BEACON -> beaconModules;
            case TRANSFER -> transferModules;
        };
        if (list.isEmpty()) {
            return ModuleSpec.EMPTY;
        }
        int index = Math.max(0, Math.min(list.size() - 1, tier - 1));
        return list.get(index);
    }

    /** 列表里第一种材质：任何需要「默认材质」的地方都用它（例如全是不认识的方块）。 */
    public CasingSpec primaryCasing() {
        return casings.isEmpty() ? new CasingSpec(
                ResourceLocation.fromNamespaceAndPath("minecraft", "air"), null, 0.0D, 1.0D, 1.0D, 1) : casings.get(0);
    }

    /**
     * 核心档位序号（T1=1、T2=2 …）。结构定义里的 tier 字符串不在表里时按 1 处理，
     * 宁可放行也不要因为一处笔误整个结构变砖。
     */
    public int tierLevel(String tierId) {
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).id().equalsIgnoreCase(tierId)) {
                return i + 1;
            }
        }
        return 1;
    }

    /**
     * 加权平均：生命倍率与护甲都按各材质占的格数算，而不是「黄铜占比二点插值」。
     * 三种以上材质混搭时，每种材质按自己的格数贡献，安山铺得多就把整体拉回安山。
     */
    public double healthMulFor(Map<CasingSpec, Integer> counts) {
        double total = 0.0D;
        double sum = 0.0D;
        for (Map.Entry<CasingSpec, Integer> entry : counts.entrySet()) {
            int cells = Math.max(0, entry.getValue());
            if (cells == 0) {
                continue;
            }
            total += cells;
            sum += cells * entry.getKey().healthMul();
        }
        if (total <= 0.0D) {
            return primaryCasing().healthMul();
        }
        return sum / total;
    }

    public double armorFor(Map<CasingSpec, Integer> counts) {
        double total = 0.0D;
        double sum = 0.0D;
        for (Map.Entry<CasingSpec, Integer> entry : counts.entrySet()) {
            int cells = Math.max(0, entry.getValue());
            if (cells == 0) {
                continue;
            }
            total += cells;
            sum += cells * entry.getKey().armor();
        }
        double value = total <= 0.0D ? primaryCasing().armor() : sum / total;
        return Math.min(armorCap, Math.max(0.0D, value));
    }

    /** 某一格的期望方块是不是机壳格（安山 / 黄铜 / 钢 / 铜任意一种）。 */
    public boolean isCasingCell(ResourceLocation expected) {
        for (CasingSpec casing : casings) {
            if (casing.id().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    public @Nullable CasingSpec casingById(ResourceLocation id) {
        for (CasingSpec casing : casings) {
            if (casing.id().equals(id)) {
                return casing;
            }
        }
        return null;
    }

    public @Nullable CasingSpec casingForShellType(ShellType type) {
        for (CasingSpec casing : casings) {
            if (casing.shellType() == type) {
                return casing;
            }
        }
        return null;
    }

    /** 只要任一种机壳配置了成型外观，校验器才需要接受 irontide:shell。 */
    public boolean hasFormedCasing() {
        for (CasingSpec casing : casings) {
            if (casing.formed() != null) {
                return true;
            }
        }
        return false;
    }

    /** 校验用：期望方块 -> 它的成型态。 */
    public @Nullable BlockState formedFor(ResourceLocation expected) {
        CasingSpec casing = casingById(expected);
        return casing == null ? null : casing.formed();
    }

    /** 成型用：按当前世界方块决定该换成哪种壳；未配置的材质返回 null。 */
    public @Nullable BlockState formedFor(Block actualBlock) {
        for (CasingSpec casing : casings) {
            if (casing.block() == actualBlock) {
                return casing.formed();
            }
        }
        return null;
    }

    /**
     * 模块数值表。四类模块共用一套 record，各读各的字段——缺字段按 0 处理，
     * 所以老配置少写一项不会加载失败，只是那一项不生效。
     */
    private static List<ModuleSpec> parseModuleList(JsonObject modules, String key) {
        if (modules == null) {
            return List.of();
        }
        JsonElement element = modules.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<ModuleSpec> out = new ArrayList<>(array.size());
        for (JsonElement entry : array) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject object = entry.getAsJsonObject();
            out.add(new ModuleSpec(
                    optInt(object, "capacity"),
                    optDouble(object, "efficiency"),
                    optInt(object, "health"),
                    optDouble(object, "reduction"),
                    optDouble(object, "reward"),
                    optInt(object, "axes")));
        }
        return List.copyOf(out);
    }

    private static int optInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0 : element.getAsInt();
    }

    private static double optDouble(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0.0D : element.getAsDouble();
    }

    public static CoreConfig load(Path file) {
        String json;
        String source;
        if (Files.isRegularFile(file)) {
            try {
                json = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read IronTide config " + file, exception);
            }
            source = file.toString();
        } else {
            json = ConfigResources.read(DEFAULT_RESOURCE);
            source = "jar:" + DEFAULT_RESOURCE;
        }

        try {
            CoreConfig config = parse(json);
            ModMindEntry.LOGGER.info("[IronTide] core.json loaded from {} (tiers={})", source, config.tiers().size());
            return config;
        } catch (RuntimeException exception) {
            ModMindEntry.LOGGER.error("[IronTide] core.json validation failed (source {}): {}", source, exception.getMessage());
            throw exception;
        }
    }

    public static CoreConfig parse(String json) {
        JsonElement rootElement = JsonParser.parseString(json);
        if (!rootElement.isJsonObject()) {
            throw JsonSupport.invalid("core.json", "root must be a JSON object");
        }
        JsonObject root = rootElement.getAsJsonObject();

        JsonObject health = JsonSupport.object(root, "health", "health");
        double healthBase = JsonSupport.number(health, "base", "health.base");
        double minRatioOnUpgrade = JsonSupport.number(health, "minRatioOnUpgrade", "health.minRatioOnUpgrade");
        boolean carryRatioOnUpgrade = JsonSupport.bool(health, "carryRatioOnUpgrade", "health.carryRatioOnUpgrade");

        JsonObject fuel = JsonSupport.object(root, "fuel", "fuel");
        int fuelCapacity = JsonSupport.integer(fuel, "capacity", "fuel.capacity");
        double burnPerSecond = JsonSupport.number(fuel, "burnPerSecond", "fuel.burnPerSecond");
        int noFuelModuleCapacity = JsonSupport.integer(fuel, "noFuelModuleCapacity", "fuel.noFuelModuleCapacity");
        double noFuelModuleEfficiency = JsonSupport.number(fuel, "noFuelModuleEfficiency", "fuel.noFuelModuleEfficiency");
        double lowFuelRatio = JsonSupport.number(fuel, "lowFuelRatio", "fuel.lowFuelRatio");

        JsonObject stress = JsonSupport.object(root, "stress", "stress");
        JsonObject build = JsonSupport.object(root, "build", "build");
        JsonObject modules = JsonSupport.object(root, "modules", "modules");

        List<CasingSpec> casings = parseCasings(root);
        double armorCap = JsonSupport.number(root, "armorCap", "armorCap");

        JsonArray tierArray = JsonSupport.array(root, "tiers", "tiers");
        List<TierSpec> tiers = new ArrayList<>();
        for (int i = 0; i < tierArray.size(); i++) {
            JsonElement tierElement = tierArray.get(i);
            if (!tierElement.isJsonObject()) {
                throw JsonSupport.invalid("tiers[" + i + "]", "must be an object");
            }
            JsonObject tier = tierElement.getAsJsonObject();
            tiers.add(new TierSpec(
                    JsonSupport.string(tier, "id", "tiers[" + i + "].id"),
                    JsonSupport.integer(tier, "edge", "tiers[" + i + "].edge"),
                    JsonSupport.integer(tier, "maxBlocks", "tiers[" + i + "].maxBlocks"),
                    JsonSupport.bool(tier, "blueprint", "tiers[" + i + "].blueprint")));
        }

        if (!(healthBase > 0)) {
            throw JsonSupport.invalid("health.base", "must be > 0, was " + healthBase);
        }
        if (!(burnPerSecond > 0)) {
            throw JsonSupport.invalid("fuel.burnPerSecond", "must be > 0, was " + burnPerSecond);
        }
        if (lowFuelRatio < 0 || lowFuelRatio > 1) {
            throw JsonSupport.invalid("fuel.lowFuelRatio", "must be within [0, 1], was " + lowFuelRatio);
        }
        if (tiers.isEmpty()) {
            throw JsonSupport.invalid("tiers", "must contain at least one tier");
        }
        for (int i = 0; i < tiers.size(); i++) {
            TierSpec tier = tiers.get(i);
            if (tier.edge() <= 0) {
                throw JsonSupport.invalid("tiers[" + i + "].edge", "must be > 0, was " + tier.edge());
            }
            if (tier.maxBlocks() <= 0) {
                throw JsonSupport.invalid("tiers[" + i + "].maxBlocks", "must be > 0, was " + tier.maxBlocks());
            }
            if (i > 0 && tier.edge() <= tiers.get(i - 1).edge()) {
                throw JsonSupport.invalid("tiers[" + i + "].edge",
                        "tiers edge must be strictly increasing; previous edge was " + tiers.get(i - 1).edge()
                                + ", current edge was " + tier.edge());
            }
        }
        if (armorCap < 0 || armorCap > 1) {
            throw JsonSupport.invalid("armorCap", "must be within [0, 1], was " + armorCap);
        }

        boolean materialSwapEnabled = true;
        JsonElement materialSwapElement = root.get("materialSwap");
        if (materialSwapElement != null && !materialSwapElement.isJsonNull()) {
            if (!materialSwapElement.isJsonObject()) {
                throw JsonSupport.invalid("materialSwap", "must be an object");
            }
            materialSwapEnabled = JsonSupport.bool(materialSwapElement.getAsJsonObject(),
                    "enabled", "materialSwap.enabled");
        }

        return new CoreConfig(healthBase, minRatioOnUpgrade, carryRatioOnUpgrade,
                fuelCapacity, burnPerSecond, lowFuelRatio, noFuelModuleCapacity, noFuelModuleEfficiency,
                casings, armorCap, List.copyOf(tiers), stress, build, modules,
                parseModuleList(modules, "fuel"),
                parseModuleList(modules, "armor"),
                parseModuleList(modules, "beacon"),
                parseModuleList(modules, "transfer"),
                materialSwapEnabled);
    }

    /**
     * 解析 casing：新格式是数组（材质数量不限），老格式是 {@code {andesite:{...}, brass:{...}}} 对象。
     * 对象按字段顺序读，保证第一种仍是主材质，老配置不用改就能加载。
     */
    private static List<CasingSpec> parseCasings(JsonObject root) {
        JsonElement element = root.get("casing");
        if (element == null || element.isJsonNull()) {
            throw JsonSupport.invalid("casing", "must be an array or an object");
        }
        List<CasingSpec> out = new ArrayList<>();
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement entry = array.get(i);
                if (!entry.isJsonObject()) {
                    throw JsonSupport.invalid("casing[" + i + "]", "must be an object");
                }
                out.add(parseCasing(entry.getAsJsonObject(), "casing[" + i + "]"));
            }
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    throw JsonSupport.invalid("casing." + entry.getKey(), "must be an object");
                }
                out.add(parseCasing(entry.getValue().getAsJsonObject(), "casing." + entry.getKey()));
            }
        } else {
            throw JsonSupport.invalid("casing", "must be an array or an object");
        }
        if (out.isEmpty()) {
            throw JsonSupport.invalid("casing", "must contain at least one casing");
        }
        for (int i = 0; i < out.size(); i++) {
            for (int j = i + 1; j < out.size(); j++) {
                if (out.get(i).id().equals(out.get(j).id())) {
                    throw JsonSupport.invalid("casing", "duplicate casing id: " + out.get(i).id());
                }
            }
        }
        return List.copyOf(out);
    }

    private static CasingSpec parseCasing(JsonObject casing, String path) {
        String idText = JsonSupport.string(casing, "id", path + ".id");
        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null) {
            throw JsonSupport.invalid(path + ".id", "invalid resource location '" + idText + "'");
        }

        BlockState formed = null;
        JsonElement formedElement = casing.get("formed");
        if (formedElement != null && !formedElement.isJsonNull()) {
            if (!formedElement.isJsonPrimitive() || !formedElement.getAsJsonPrimitive().isString()) {
                throw JsonSupport.invalid(path + ".formed", "must be a block state string");
            }
            formed = parseFormedState(formedElement.getAsString(), path + ".formed");
        }

        double armor = JsonSupport.number(casing, "armor", path + ".armor");
        double healthMul = JsonSupport.number(casing, "healthMul", path + ".healthMul");
        double fuelEfficiency = JsonSupport.number(casing, "fuelEfficiency", path + ".fuelEfficiency");
        if (healthMul <= 0) {
            throw JsonSupport.invalid(path + ".healthMul", "must be > 0, was " + healthMul);
        }
        if (armor < 0) {
            throw JsonSupport.invalid(path + ".armor", "must be >= 0, was " + armor);
        }
        if (fuelEfficiency < 0) {
            throw JsonSupport.invalid(path + ".fuelEfficiency", "must be >= 0, was " + fuelEfficiency);
        }
        int minTier = 1;
        JsonElement minTierElement = casing.get("minTier");
        if (minTierElement != null && !minTierElement.isJsonNull()) {
            minTier = JsonSupport.integer(casing, "minTier", path + ".minTier");
        }
        if (minTier < 1) {
            throw JsonSupport.invalid(path + ".minTier", "must be >= 1, was " + minTier);
        }
        return new CasingSpec(id, formed, armor, healthMul, fuelEfficiency, minTier);
    }

    /** 解析 "irontide:shell[type=brass]" 这种带属性的方块状态。 */
    private static BlockState parseFormedState(String raw, String where) {
        String text = raw.trim();
        if (text.isEmpty()) {
            throw JsonSupport.invalid(where, "must not be empty");
        }
        int bracket = text.indexOf('[');
        String idPart = bracket < 0 ? text : text.substring(0, bracket).trim();
        ResourceLocation location = ResourceLocation.tryParse(idPart);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
            throw JsonSupport.invalid(where, "unknown block: " + idPart);
        }
        Block block = BuiltInRegistries.BLOCK.get(location);
        BlockState state = block.defaultBlockState();
        if (bracket < 0) {
            return state;
        }
        int close = text.lastIndexOf(']');
        if (close < bracket) {
            throw JsonSupport.invalid(where, "missing ']' in: " + raw);
        }
        String propertiesText = text.substring(bracket + 1, close).trim();
        if (!propertiesText.isEmpty()) {
            for (String pair : propertiesText.split(",")) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) {
                    throw JsonSupport.invalid(where, "bad property: " + pair.trim());
                }
                state = applyProperty(state, block, parts[0].trim(), parts[1].trim(), where);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, Block block, String name, String value, String where) {
        Property<?> rawProperty = block.getStateDefinition().getProperty(name);
        if (rawProperty == null) {
            throw JsonSupport.invalid(where, "unknown property: " + name);
        }
        @SuppressWarnings("unchecked")
        Property<T> property = (Property<T>) rawProperty;
        return property.getValue(value)
                .map(candidate -> state.setValue(property, candidate))
                .orElseThrow(() -> JsonSupport.invalid(where,
                        "bad value '" + value + "' for property " + name));
    }
}