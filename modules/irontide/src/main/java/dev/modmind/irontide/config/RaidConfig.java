package dev.modmind.irontide.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 怪潮配置（设计 §2.4）。独立于 core.json，改波次表不用碰核心数值。
 * <p>
 * 数值全部驱动状态机，Java 侧不硬编码任何波次/怪物类型（见设计 §3 数据边界）。
 */
public record RaidConfig(
        int cycleDays,
        int warnDay,
        int scoutDay,
        int attackDay,
        List<Wave> waves,
        double headMulPerPlayer,
        double gearMulMax,
        int spawnRadiusMin,
        int spawnRadiusMax,
        int spawnPerBurst,
        int spawnIntervalTicks,
        double attackRange,
        int attackDamagePerSecond,
        /** 攻核全程 DPS 上限；{@code 0} = 不限（老配置缺少该字段时也是 0）。 */
        int attackDpsCap,
        int airHeight,
        List<String> groundRaiders,
        List<String> airRaiders,
        SafeZone safeZone
) {
    /** 单波次定义。 */
    public record Wave(int index, int base, int affixes, int maxAffixLevel) {
    }

    /**
     * 安全区（设计 §2.4 补充）：以核心为中心的立方体。
     * {@code radius} 是水平半宽，{@code halfHeight} 是相对核心的上下高度。
     * 怪物只在 {@code radius} 之外刷新，区内由 {@code lightLevel} 照亮并立 {@code beacons} 根光柱。
     */
    public record SafeZone(
            boolean enabled,
            int radius,
            int halfHeight,
            int lightLevel,
            boolean lightUp,
            int beacons,
            int particleIntervalTicks
    ) {
        public static final SafeZone DISABLED = new SafeZone(false, 25, 25, 7, false, 0, 10);
    }

    public static final String DEFAULT_RESOURCE = "/data/irontide/config_defaults/raids.json";

    public static RaidConfig load(Path file) {
        String json;
        String source;
        if (Files.isRegularFile(file)) {
            try {
                json = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read IronTide raid config " + file, exception);
            }
            source = file.toString();
        } else {
            json = ConfigResources.read(DEFAULT_RESOURCE);
            source = "jar:" + DEFAULT_RESOURCE;
        }

        RaidConfig config = parse(json);
        dev.modmind.irontide.ModMindEntry.LOGGER.info(
                "[IronTide] raids.json loaded from {} (waves={}, cycleDays={})",
                source, config.waves().size(), config.cycleDays());
        return config;
    }

    public static RaidConfig parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw JsonSupport.invalid("raids.json", "cannot parse: " + exception.getMessage());
        }
        if (root == null || !root.isJsonObject()) {
            throw JsonSupport.invalid("raids.json", "root must be a JSON object");
        }

        int cycleDays = JsonSupport.integer(root, "cycleDays", "cycleDays");
        int warnDay = JsonSupport.integer(root, "warnDay", "warnDay");
        int scoutDay = JsonSupport.integer(root, "scoutDay", "scoutDay");
        int attackDay = JsonSupport.integer(root, "attackDay", "attackDay");
        double headMulPerPlayer = JsonSupport.number(root, "headMulPerPlayer", "headMulPerPlayer");
        double gearMulMax = JsonSupport.number(root, "gearMulMax", "gearMulMax");

        JsonObject spawn = JsonSupport.object(root, "spawn", "spawn");
        int spawnRadiusMin = JsonSupport.integer(spawn, "radiusMin", "spawn.radiusMin");
        int spawnRadiusMax = JsonSupport.integer(spawn, "radiusMax", "spawn.radiusMax");
        int spawnPerBurst = JsonSupport.integer(spawn, "perBurst", "spawn.perBurst");
        int spawnIntervalTicks = JsonSupport.integer(spawn, "intervalTicks", "spawn.intervalTicks");
        int airHeight = JsonSupport.integer(spawn, "airHeight", "spawn.airHeight");

        JsonObject attack = JsonSupport.object(root, "attack", "attack");
        double attackRange = JsonSupport.number(attack, "range", "attack.range");
        int attackDamagePerSecond = JsonSupport.integer(attack, "damagePerSecond", "attack.damagePerSecond");
        // 缺省 0 = 不限，让老存档的 raids.json 不改也能继续加载
        int attackDpsCap = attack.has("dpsCap")
                ? JsonSupport.integer(attack, "dpsCap", "attack.dpsCap")
                : 0;

        JsonArray waveArray = JsonSupport.array(root, "waves", "waves");
        List<Wave> waves = new ArrayList<>();
        for (int i = 0; i < waveArray.size(); i++) {
            JsonElement element = waveArray.get(i);
            if (element == null || !element.isJsonObject()) {
                throw JsonSupport.invalid("waves[" + i + "]", "must be an object");
            }
            JsonObject wave = element.getAsJsonObject();
            waves.add(new Wave(
                    JsonSupport.integer(wave, "index", "waves[" + i + "].index"),
                    JsonSupport.integer(wave, "base", "waves[" + i + "].base"),
                    JsonSupport.integer(wave, "affixes", "waves[" + i + "].affixes"),
                    JsonSupport.integer(wave, "maxAffixLevel", "waves[" + i + "].maxAffixLevel")));
        }

        List<String> groundRaiders = stringList(root, "groundRaiders", "groundRaiders");
        List<String> airRaiders = stringList(root, "airRaiders", "airRaiders");
        SafeZone safeZone = parseSafeZone(root);

        if (cycleDays <= 0) {
            throw JsonSupport.invalid("cycleDays", "must be > 0, was " + cycleDays);
        }
        if (attackDay <= 0 || attackDay > cycleDays) {
            throw JsonSupport.invalid("attackDay", "must be within [1, " + cycleDays + "], was " + attackDay);
        }
        if (warnDay > attackDay) {
            throw JsonSupport.invalid("warnDay", "must be <= attackDay, was " + warnDay);
        }
        if (spawnRadiusMin <= 0 || spawnRadiusMax < spawnRadiusMin) {
            throw JsonSupport.invalid("spawn", "radiusMax must be >= radiusMin > 0");
        }
        if (waves.isEmpty()) {
            throw JsonSupport.invalid("waves", "must contain at least one wave");
        }
        for (int i = 0; i < waves.size(); i++) {
            if (waves.get(i).base() <= 0) {
                throw JsonSupport.invalid("waves[" + i + "].base", "must be > 0, was " + waves.get(i).base());
            }
        }
        if (attackRange <= 0) {
            throw JsonSupport.invalid("attack.range", "must be > 0, was " + attackRange);
        }
        if (attackDpsCap < 0) {
            throw JsonSupport.invalid("attack.dpsCap", "must be >= 0 (0 = unlimited), was " + attackDpsCap);
        }

        return new RaidConfig(cycleDays, warnDay, scoutDay, attackDay, List.copyOf(waves),
                headMulPerPlayer, gearMulMax, spawnRadiusMin, spawnRadiusMax, spawnPerBurst,
                spawnIntervalTicks, attackRange, attackDamagePerSecond, attackDpsCap, airHeight,
                List.copyOf(groundRaiders), List.copyOf(airRaiders), safeZone);
    }

    /** 缺省或关闭时返回 {@link SafeZone#DISABLED}，不让老配置因为少了这一段而加载失败。 */
    private static SafeZone parseSafeZone(JsonObject root) {
        JsonElement element = root.get("safeZone");
        if (element == null || !element.isJsonObject()) {
            return SafeZone.DISABLED;
        }
        JsonObject zone = element.getAsJsonObject();
        boolean enabled = bool(zone, "enabled", "safeZone.enabled");
        int radius = JsonSupport.integer(zone, "radius", "safeZone.radius");
        int halfHeight = JsonSupport.integer(zone, "halfHeight", "safeZone.halfHeight");
        int lightLevel = JsonSupport.integer(zone, "lightLevel", "safeZone.lightLevel");
        boolean lightUp = bool(zone, "lightUp", "safeZone.lightUp");
        int beacons = JsonSupport.integer(zone, "beacons", "safeZone.beacons");
        int particleIntervalTicks = JsonSupport.integer(zone, "particleIntervalTicks", "safeZone.particleIntervalTicks");

        if (radius <= 0 || radius > 128) {
            throw JsonSupport.invalid("safeZone.radius", "must be within [1, 128], was " + radius);
        }
        if (halfHeight <= 0 || halfHeight > 128) {
            throw JsonSupport.invalid("safeZone.halfHeight", "must be within [1, 128], was " + halfHeight);
        }
        if (lightLevel < 0 || lightLevel > 15) {
            throw JsonSupport.invalid("safeZone.lightLevel", "must be within [0, 15], was " + lightLevel);
        }
        if (beacons < 0 || beacons > 4) {
            throw JsonSupport.invalid("safeZone.beacons", "must be within [0, 4], was " + beacons);
        }
        if (particleIntervalTicks < 1) {
            throw JsonSupport.invalid("safeZone.particleIntervalTicks",
                    "must be >= 1, was " + particleIntervalTicks);
        }
        return new SafeZone(enabled, radius, halfHeight, lightLevel, lightUp, beacons, particleIntervalTicks);
    }

    private static boolean bool(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw JsonSupport.invalid(path, "must be a boolean");
        }
        return element.getAsBoolean();
    }

    /** 取第 waveIndex 波（1 起）。超出表长时用最后一波封顶。 */
    public Wave wave(int waveIndex) {
        if (waveIndex <= 0) {
            waveIndex = 1;
        }
        return waves.get(Math.min(waveIndex, waves.size()) - 1);
    }

    /** 最终规模 = round(Base × HeadMul × GearMul)（设计 §2.4）。 */
    public int scaledSize(int waveIndex, int onlinePlayers, int coreTier, int maxTier) {
        Wave wave = wave(waveIndex);
        double headMul = 1.0D + headMulPerPlayer * Math.max(0, onlinePlayers - 1);
        double gearRatio = maxTier <= 1 ? 1.0D : (double) (Math.max(1, coreTier) - 1) / (maxTier - 1);
        double gearMul = 1.0D + gearMulMax * Math.min(1.0D, Math.max(0.0D, gearRatio));
        return Math.max(1, (int) Math.round(wave.base() * headMul * gearMul));
    }

    private static List<String> stringList(JsonObject parent, String key, String path) {
        JsonArray array = JsonSupport.array(parent, key, path);
        List<String> values = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw JsonSupport.invalid(path + "[" + i + "]", "must be a string");
            }
            values.add(element.getAsString());
        }
        return values;
    }
}
