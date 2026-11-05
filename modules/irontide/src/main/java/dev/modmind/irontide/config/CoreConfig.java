package dev.modmind.irontide.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modmind.irontide.ModMindEntry;
import net.minecraft.resources.ResourceLocation;

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
        JsonObject modules
) {
    public static final String DEFAULT_RESOURCE = "/data/irontide/config_defaults/core.json";

    public double healthMulFor(double brassRatio) {
        return andesiteCasing.healthMul()
                + brassRatio * (brassCasing.healthMul() - andesiteCasing.healthMul());
    }

    public double armorFor(double brassRatio) {
        double value = andesiteCasing.armor()
                + brassRatio * (brassCasing.armor() - andesiteCasing.armor());
        return Math.min(armorCap, value);
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

        return new CoreConfig(healthBase, minRatioOnUpgrade, carryRatioOnUpgrade,
                fuelCapacity, burnPerSecond, lowFuelRatio, noFuelModuleCapacity, noFuelModuleEfficiency,
                andesite, brass, armorCap, List.copyOf(tiers), stress, build, modules);
    }

    private static CasingSpec parseCasing(JsonObject casing, String path) {
        String idText = JsonSupport.string(casing, "id", path + ".id");
        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null) {
            throw JsonSupport.invalid(path + ".id", "invalid resource location '" + idText + "'");
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
        return new CasingSpec(id, armor, healthMul, fuelEfficiency);
    }
}