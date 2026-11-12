package dev.modmind.irontide.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modmind.irontide.ModMindEntry;
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

public record CoreConfig(
        double healthBase,
        double minRatioOnUpgrade,
        boolean carryRatioOnUpgrade,
        int fuelCapacity,
        double burnPerSecond,
        double lowFuelRatio,
        int noFuelModuleCapacity,
        double noFuelModuleEfficiency,
        CasingSpec andesiteCasing,
        CasingSpec brassCasing,
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

    public double healthMulFor(double brassRatio) {
        return andesiteCasing.healthMul()
                + brassRatio * (brassCasing.healthMul() - andesiteCasing.healthMul());
    }

    public double armorFor(double brassRatio) {
        double value = andesiteCasing.armor()
                + brassRatio * (brassCasing.armor() - andesiteCasing.armor());
        return Math.min(armorCap, value);
    }

    /** 只要任一种机壳配置了成型外观，校验器才需要接受 irontide:shell。 */
    public boolean hasFormedCasing() {
        return andesiteCasing.formed() != null || brassCasing.formed() != null;
    }

    /** 校验用：期望方块 -> 它的成型态。 */
    public @Nullable BlockState formedFor(ResourceLocation expected) {
        if (expected.equals(andesiteCasing.id())) {
            return andesiteCasing.formed();
        }
        if (expected.equals(brassCasing.id())) {
            return brassCasing.formed();
        }
        return null;
    }

    /** 成型用：按当前世界方块决定该换成哪种壳；未配置的材质返回 null。 */
    public @Nullable BlockState formedFor(Block actualBlock) {
        if (actualBlock == andesiteCasing.block()) {
            return andesiteCasing.formed();
        }
        if (actualBlock == brassCasing.block()) {
            return brassCasing.formed();
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

        JsonObject casing = JsonSupport.object(root, "casing", "casing");
        CasingSpec andesite = parseCasing(JsonSupport.object(casing, "andesite", "casing.andesite"), "casing.andesite");
        CasingSpec brass = parseCasing(JsonSupport.object(casing, "brass", "casing.brass"), "casing.brass");
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
                andesite, brass, armorCap, List.copyOf(tiers), stress, build, modules,
                parseModuleList(modules, "fuel"),
                parseModuleList(modules, "armor"),
                parseModuleList(modules, "beacon"),
                parseModuleList(modules, "transfer"),
                materialSwapEnabled);
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
        return new CasingSpec(id, formed, armor, healthMul, fuelEfficiency);
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