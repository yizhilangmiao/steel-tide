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
        /** 单波规模下限：波次表的 base 低于它时按它算（默认 32，保证一波不少于 32 只）。 */
        int minWaveSize,
        int spawnRadiusMin,
        int spawnRadiusMax,
        int spawnPerBurst,
        int spawnIntervalTicks,
        /** 同时存活上限（{@code 0} = 不限）。到顶后暂停刷怪，等现存的清掉再继续，避免峰值同屏。 */
        int spawnMaxAlive,
        /** 寻路半径：半径内交给原版寻路，半径外只做直线移动，省掉远处的寻路开销。 */
        int chaseRadius,
        /** 卡怪超时重刷：连续这么多个 tick 没有「进展」就重刷；0 = 关闭。 */
        int stuckTimeoutTicks,
        /** 同一只怪最多重刷几次；超过就直接 discard，由调度器补刷。 */
        int stuckMaxRespawns,
        /** 剩余来袭怪少于这个数时给每只挂发光（0 = 关闭），方便收尾时找漏网的。 */
        int glowWhenAliveBelow,
        /** 主攻方向的可视扇形张角（度）。0 = 全从同一点来，360 = 四面八方形同随机。 */
        int spawnArcDegrees,
        /** 小波之间的间隔（tick）。5 天一大波、每大波 3 小波。 */
        int spawnWaveGapTicks,
        /** 大波早期的单侧规则。 */
        Early early,
        double attackRange,
        int attackDamagePerSecond,
        /** 攻核全程 DPS 上限；{@code 0} = 不限（老配置缺少该字段时也是 0）。 */
        int attackDpsCap,
        /** 攻核脉冲间隔（tick）。100 = 5 秒一次全局同步脉冲，数字越小压力越大但总量不变。 */
        int attackIntervalTicks,
        /** 攻核怪拆墙规则（按硬度，`r>=27` 可拆）。缺省关闭，老配置行为不变。 */
        Breaker breaker,
        /** 填沟规则。缺省关闭。 */
        Filler filler,
        int airHeight,
        List<String> groundRaiders,
        List<String> airRaiders,
        SafeZone safeZone,
        Defense defense,
        Outpost outpost
) {
    /** 单波次定义。 */
    public record Wave(int index, int base, int affixes, int maxAffixLevel) {
    }

    /** 大波早期的单侧规则：前 N 个大波从小波都只从同一侧来，第一个大波固定一个方向。 */
    public record Early(int singleSideBigWaves, String firstBigWaveSide) {
        public static final Early DEFAULT = new Early(3, "west");
    }

    /** 填沟：怪把 27 格外的深沟用鹅卵石垫平。 */
    public record Filler(boolean enabled, String block, int perRaiderPerWave, int globalPerWave,
                         int perTick, int maxDepth, int maxWidth) {
        public static final Filler DISABLED =
                new Filler(false, "minecraft:cobblestone", 8, 128, 1, 4, 3);
    }

    /** 领地分区半径与空气墙。 */
    public record Defense(int protectedRadius, int defenseRadius, int bufferRadius,
                          double wallLimit, List<String> denyEntities) {
        public static final Defense DEFAULT =
                new Defense(26, 40, 44, 26.35D, List.of());
    }

    /** 前哨定位与刷怪锤。 */
    public record Outpost(int minRadius, int maxRadius, int sameSideDegrees, int spawnRadius) {
        public static final Outpost DEFAULT = new Outpost(44, 60, 45, 8);
    }

    /**
     * 攻核怪拆墙（W4 领地分区）：{@code r>=27} 的方块都能被拆，按硬度累积进度，
     * 只有 {@link dev.modmind.irontide.raid.Territory} 里的永久保护名单（核心、壳、基岩、
     * 命令方块、结构方块）永远拆不动。
     * <p>
     * {@code maxProgress} 是单块上限：按硬度算出来的需求次数不会超过它，
     * 所以黑曜石这类高硬度方块最终仍然可破（只是慢）。
     */
    public record Breaker(boolean enabled, int intervalTicks, int maxPerTick, double range,
                          int maxProgress) {
        public static final Breaker DISABLED = new Breaker(false, 20, 4, 40.0D, 120);
    }

    /**
     * 安全区（设计 §2.4 补充）：以核心为中心的方形分区。
     * {@code radius} 是水平半宽（切比雪夫），{@code halfHeight} 只在 {@code shape=cube} 时作为旧行为的兜底。
     * 怪物只在 {@code radius} 之外刷新，区内可选照亮（{@code lightUp} 默认关）。
     */
    public record SafeZone(
            boolean enabled,
            int radius,
            int halfHeight,
            int lightLevel,
            boolean lightUp,
            int beacons,
            int particleIntervalTicks,
            /** {@code square} = 只按水平切比雪夫判；{@code cube} = 旧行为的立方体。 */
            String shape,
            /** 是否把 26 格内当成不可拆建造区。 */
            boolean protectBlocks,
            /** 绿环颜色（仅日志/文档用，粒子颜色写死为 #3CFF6E）。 */
            String green,
            /** 粒子半径：超出这个距离的玩家收不到。 */
            int visualRange,
            /** 是否启用 26 格空气墙。 */
            boolean airWall
    ) {
        public static final SafeZone DISABLED =
                new SafeZone(false, 25, 25, 7, false, 0, 10, "square", true, "#3CFF6E", 64, true);
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
        // 缺省 32：老配置的波次表偏小，按「一波最少 32 只」兜底
        int minWaveSize = optionalInt(root, "minWaveSize", "minWaveSize", 32);

        JsonObject spawn = JsonSupport.object(root, "spawn", "spawn");
        int spawnRadiusMin = JsonSupport.integer(spawn, "radiusMin", "spawn.radiusMin");
        int spawnRadiusMax = JsonSupport.integer(spawn, "radiusMax", "spawn.radiusMax");
        int spawnPerBurst = JsonSupport.integer(spawn, "perBurst", "spawn.perBurst");
        int spawnIntervalTicks = JsonSupport.integer(spawn, "intervalTicks", "spawn.intervalTicks");
        // 老配置没有这两个键：maxAlive 缺省 0（不限）、chaseRadius 缺省 72（T4 刷怪环角点欧氏 63.6–84.9，
        // 旧值 48 会让角上的怪全程直线移动，是卡深坑的主因之一）
        int spawnMaxAlive = optionalInt(spawn, "maxAlive", "spawn.maxAlive", 0);
        int chaseRadius = optionalInt(spawn, "chaseRadius", "spawn.chaseRadius", 72);
        // 卡怪超时重刷：60 秒无进展就换一只，老配置缺省即开启
        int stuckTimeoutTicks = optionalInt(spawn, "stuckTimeoutTicks", "spawn.stuckTimeoutTicks", 1200);
        int stuckMaxRespawns = optionalInt(spawn, "stuckMaxRespawns", "spawn.stuckMaxRespawns", 3);
        int glowWhenAliveBelow = optionalInt(spawn, "glowWhenAliveBelow", "spawn.glowWhenAliveBelow", 10);
        // 缺省 60°：一波怪挤在一个扇面里，从同一个方向压过来
        int spawnArcDegrees = optionalInt(spawn, "arcDegrees", "spawn.arcDegrees", 60);
        int spawnWaveGapTicks = optionalInt(spawn, "waveGapTicks", "spawn.waveGapTicks", 3600);
        Early early = parseEarly(spawn);
        int airHeight = JsonSupport.integer(spawn, "airHeight", "spawn.airHeight");

        JsonObject attack = JsonSupport.object(root, "attack", "attack");
        double attackRange = JsonSupport.number(attack, "range", "attack.range");
        int attackDamagePerSecond = JsonSupport.integer(attack, "damagePerSecond", "attack.damagePerSecond");
        // 缺省 0 = 不限，让老存档的 raids.json 不改也能继续加载
        int attackDpsCap = attack.has("dpsCap")
                ? JsonSupport.integer(attack, "dpsCap", "attack.dpsCap")
                : 0;
        int attackIntervalTicks = optionalInt(attack, "intervalTicks", "attack.intervalTicks", 100);

        // 拆墙：缺省关闭，老 raids.json 不改也能加载，行为与改动前一致
        Breaker breaker = parseBreaker(root);
        Filler filler = parseFiller(root);

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
        Defense defense = parseDefense(root);
        Outpost outpost = parseOutpost(root);

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
        if (spawnMaxAlive < 0) {
            throw JsonSupport.invalid("spawn.maxAlive", "must be >= 0 (0 = unlimited), was " + spawnMaxAlive);
        }
        if (chaseRadius < 8 || chaseRadius > 128) {
            throw JsonSupport.invalid("spawn.chaseRadius", "must be within [8, 128], was " + chaseRadius);
        }
        if (stuckTimeoutTicks < 0 || stuckTimeoutTicks > 24000) {
            throw JsonSupport.invalid("spawn.stuckTimeoutTicks", "must be within [0, 24000], was " + stuckTimeoutTicks);
        }
        if (stuckMaxRespawns < 0 || stuckMaxRespawns > 20) {
            throw JsonSupport.invalid("spawn.stuckMaxRespawns", "must be within [0, 20], was " + stuckMaxRespawns);
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
        if (minWaveSize < 0) {
            throw JsonSupport.invalid("minWaveSize", "must be >= 0 (0 = no floor), was " + minWaveSize);
        }
        if (glowWhenAliveBelow < 0) {
            throw JsonSupport.invalid("spawn.glowWhenAliveBelow",
                    "must be >= 0 (0 = disabled), was " + glowWhenAliveBelow);
        }
        if (spawnArcDegrees < 0 || spawnArcDegrees > 360) {
            throw JsonSupport.invalid("spawn.arcDegrees", "must be within [0, 360], was " + spawnArcDegrees);
        }
        if (spawnWaveGapTicks < 0) {
            throw JsonSupport.invalid("spawn.waveGapTicks", "must be >= 0, was " + spawnWaveGapTicks);
        }
        if (attackIntervalTicks < 20 || attackIntervalTicks > 600) {
            throw JsonSupport.invalid("attack.intervalTicks",
                    "must be within [20, 600], was " + attackIntervalTicks);
        }
        if (defense.protectedRadius() < 0 || defense.defenseRadius() <= defense.protectedRadius()) {
            throw JsonSupport.invalid("defense",
                    "defenseRadius must be > protectedRadius >= 0");
        }
        if (outpost.maxRadius() < outpost.minRadius() || outpost.spawnRadius() <= 0) {
            throw JsonSupport.invalid("outpost", "maxRadius must be >= minRadius > 0 and spawnRadius > 0");
        }

        return new RaidConfig(cycleDays, warnDay, scoutDay, attackDay, List.copyOf(waves),
                headMulPerPlayer, gearMulMax, minWaveSize, spawnRadiusMin, spawnRadiusMax, spawnPerBurst,
                spawnIntervalTicks, spawnMaxAlive, chaseRadius, stuckTimeoutTicks, stuckMaxRespawns,
                glowWhenAliveBelow, spawnArcDegrees,
                spawnWaveGapTicks, early, attackRange, attackDamagePerSecond, attackDpsCap,
                attackIntervalTicks, breaker, filler, airHeight,
                List.copyOf(groundRaiders), List.copyOf(airRaiders), safeZone, defense, outpost);
    }

    /** 缺省时返回 {@link Breaker#DISABLED}，不让老配置因为少了这一段而加载失败。 */
    private static Breaker parseBreaker(JsonObject root) {
        JsonElement element = root.get("break");
        if (element == null || !element.isJsonObject()) {
            return Breaker.DISABLED;
        }
        JsonObject object = element.getAsJsonObject();
        boolean enabled = !object.has("enabled")
                || JsonSupport.bool(object, "enabled", "break.enabled");
        int intervalTicks = object.has("intervalTicks")
                ? JsonSupport.integer(object, "intervalTicks", "break.intervalTicks") : 20;
        int maxPerTick = object.has("maxPerTick")
                ? JsonSupport.integer(object, "maxPerTick", "break.maxPerTick") : 4;
        double range = object.has("range")
                ? JsonSupport.number(object, "range", "break.range") : 40.0D;
        int maxProgress = object.has("maxProgress")
                ? JsonSupport.integer(object, "maxProgress", "break.maxProgress") : 120;

        if (intervalTicks < 5) {
            throw JsonSupport.invalid("break.intervalTicks", "must be >= 5, was " + intervalTicks);
        }
        if (maxPerTick < 0) {
            throw JsonSupport.invalid("break.maxPerTick", "must be >= 0 (0 = disabled), was " + maxPerTick);
        }
        if (range < 4.0D || range > 64.0D) {
            throw JsonSupport.invalid("break.range", "must be within [4, 64], was " + range);
        }
        if (maxProgress < 1 || maxProgress > 1200) {
            throw JsonSupport.invalid("break.maxProgress", "must be within [1, 1200], was " + maxProgress);
        }
        return new Breaker(enabled, intervalTicks, maxPerTick, range, maxProgress);
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
        // 以下为领地分区新增字段：老 raids.json 没有这些键时按设计默认值兜底，行为不变
        String shapeRaw = zone.has("shape") ? JsonSupport.string(zone, "shape", "safeZone.shape") : "square";
        boolean protectBlocks = optionalBool(zone, "protectBlocks", "safeZone.protectBlocks", true);
        String green = zone.has("green") ? JsonSupport.string(zone, "green", "safeZone.green") : "#3CFF6E";
        int visualRange = optionalInt(zone, "visualRange", "safeZone.visualRange", 64);
        boolean airWall = optionalBool(zone, "airWall", "safeZone.airWall", true);
        String shape = "cube".equalsIgnoreCase(shapeRaw) ? "cube" : "square";

        if (!"cube".equalsIgnoreCase(shapeRaw) && !"square".equalsIgnoreCase(shapeRaw)) {
            throw JsonSupport.invalid("safeZone.shape", "must be 'square' or 'cube', was " + shapeRaw);
        }
        if (visualRange < 16 || visualRange > 256) {
            throw JsonSupport.invalid("safeZone.visualRange", "must be within [16, 256], was " + visualRange);
        }

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
        return new SafeZone(enabled, radius, halfHeight, lightLevel, lightUp, beacons, particleIntervalTicks,
                shape, protectBlocks, green, visualRange, airWall);
    }

    /** 缺省键用既成默认值，保证老存档的 raids.json 不改也能继续加载。 */
    private static int optionalInt(JsonObject parent, String key, String path, int fallback) {
        return parent.has(key) ? JsonSupport.integer(parent, key, path) : fallback;
    }

    private static boolean optionalBool(JsonObject parent, String key, String path, boolean fallback) {
        return parent.has(key) ? JsonSupport.bool(parent, key, path) : fallback;
    }

    /** 大波早期的单侧规则；缺省时保持「前 3 个大波单侧、第一个大波从西边来」。 */
    private static Early parseEarly(JsonObject spawn) {
        JsonElement element = spawn.get("early");
        if (element == null || !element.isJsonObject()) {
            return Early.DEFAULT;
        }
        JsonObject object = element.getAsJsonObject();
        int singleSideBigWaves = optionalInt(object, "singleSideBigWaves",
                "spawn.early.singleSideBigWaves", 3);
        String firstBigWaveSide = object.has("firstBigWaveSide")
                ? JsonSupport.string(object, "firstBigWaveSide", "spawn.early.firstBigWaveSide") : "west";
        if (singleSideBigWaves < 0) {
            throw JsonSupport.invalid("spawn.early.singleSideBigWaves",
                    "must be >= 0, was " + singleSideBigWaves);
        }
        return new Early(singleSideBigWaves, firstBigWaveSide);
    }

    /** 填沟：缺省或缺少该段时返回 {@link Filler#DISABLED}，老配置行为不变。 */
    private static Filler parseFiller(JsonObject root) {
        JsonElement element = root.get("fill");
        if (element == null || !element.isJsonObject()) {
            return Filler.DISABLED;
        }
        JsonObject object = element.getAsJsonObject();
        boolean enabled = optionalBool(object, "enabled", "fill.enabled", true);
        String block = object.has("block")
                ? JsonSupport.string(object, "block", "fill.block") : "minecraft:cobblestone";
        int perRaiderPerWave = optionalInt(object, "perRaiderPerWave", "fill.perRaiderPerWave", 8);
        int globalPerWave = optionalInt(object, "globalPerWave", "fill.globalPerWave", 128);
        int perTick = optionalInt(object, "perTick", "fill.perTick", 1);
        int maxDepth = optionalInt(object, "maxDepth", "fill.maxDepth", 4);
        int maxWidth = optionalInt(object, "maxWidth", "fill.maxWidth", 3);

        if (perRaiderPerWave < 0) {
            throw JsonSupport.invalid("fill.perRaiderPerWave",
                    "must be >= 0 (0 = disabled), was " + perRaiderPerWave);
        }
        if (globalPerWave < 0) {
            throw JsonSupport.invalid("fill.globalPerWave", "must be >= 0, was " + globalPerWave);
        }
        if (perTick < 1) {
            throw JsonSupport.invalid("fill.perTick", "must be >= 1, was " + perTick);
        }
        if (maxDepth < 1 || maxDepth > 8) {
            throw JsonSupport.invalid("fill.maxDepth", "must be within [1, 8], was " + maxDepth);
        }
        if (maxWidth < 1 || maxWidth > 8) {
            throw JsonSupport.invalid("fill.maxWidth", "must be within [1, 8], was " + maxWidth);
        }
        return new Filler(enabled, block, perRaiderPerWave, globalPerWave, perTick, maxDepth, maxWidth);
    }

    /** 领地分区半径与空气墙；缺段时用 {@link Defense#DEFAULT}。 */
    private static Defense parseDefense(JsonObject root) {
        JsonElement element = root.get("defense");
        if (element == null || !element.isJsonObject()) {
            return Defense.DEFAULT;
        }
        JsonObject object = element.getAsJsonObject();
        int protectedRadius = optionalInt(object, "protectedRadius", "defense.protectedRadius", 26);
        int defenseRadius = optionalInt(object, "defenseRadius", "defense.defenseRadius", 40);
        int bufferRadius = optionalInt(object, "bufferRadius", "defense.bufferRadius", 44);
        double wallLimit = object.has("wallLimit")
                ? JsonSupport.number(object, "wallLimit", "defense.wallLimit") : 26.35D;
        List<String> denyEntities = object.has("denyEntities")
                ? stringList(object, "denyEntities", "defense.denyEntities") : List.of();

        if (protectedRadius < 0) {
            throw JsonSupport.invalid("defense.protectedRadius", "must be >= 0, was " + protectedRadius);
        }
        if (defenseRadius <= protectedRadius) {
            throw JsonSupport.invalid("defense.defenseRadius",
                    "must be > protectedRadius, was " + defenseRadius);
        }
        if (bufferRadius < defenseRadius) {
            throw JsonSupport.invalid("defense.bufferRadius",
                    "must be >= defenseRadius, was " + bufferRadius);
        }
        if (wallLimit <= 0.0D || wallLimit > 128.0D) {
            throw JsonSupport.invalid("defense.wallLimit", "must be within (0, 128], was " + wallLimit);
        }
        return new Defense(protectedRadius, defenseRadius, bufferRadius, wallLimit, List.copyOf(denyEntities));
    }

    /** 前哨定位；缺段时用 {@link Outpost#DEFAULT}。 */
    private static Outpost parseOutpost(JsonObject root) {
        JsonElement element = root.get("outpost");
        if (element == null || !element.isJsonObject()) {
            return Outpost.DEFAULT;
        }
        JsonObject object = element.getAsJsonObject();
        int minRadius = optionalInt(object, "minRadius", "outpost.minRadius", 44);
        int maxRadius = optionalInt(object, "maxRadius", "outpost.maxRadius", 60);
        int sameSideDegrees = optionalInt(object, "sameSideDegrees", "outpost.sameSideDegrees", 45);
        int spawnRadius = optionalInt(object, "spawnRadius", "outpost.spawnRadius", 8);

        if (minRadius <= 0) {
            throw JsonSupport.invalid("outpost.minRadius", "must be > 0, was " + minRadius);
        }
        if (sameSideDegrees < 0 || sameSideDegrees > 180) {
            throw JsonSupport.invalid("outpost.sameSideDegrees",
                    "must be within [0, 180], was " + sameSideDegrees);
        }
        if (spawnRadius <= 0) {
            throw JsonSupport.invalid("outpost.spawnRadius", "must be > 0, was " + spawnRadius);
        }
        return new Outpost(minRadius, maxRadius, sameSideDegrees, spawnRadius);
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

    /** 该波的生效基础数：波次表的 base 与 minWaveSize 取大（保证「一波不少于 N 只」）。 */
    public int effectiveBase(int waveIndex) {
        return Math.max(wave(waveIndex).base(), Math.max(0, minWaveSize));
    }

    /** 最终规模 = round(Base × HeadMul × GearMul)（设计 §2.4），Base 已套用 minWaveSize 下限。 */
    public int scaledSize(int waveIndex, int onlinePlayers, int coreTier, int maxTier) {
        double headMul = 1.0D + headMulPerPlayer * Math.max(0, onlinePlayers - 1);
        double gearRatio = maxTier <= 1 ? 1.0D : (double) (Math.max(1, coreTier) - 1) / (maxTier - 1);
        double gearMul = 1.0D + gearMulMax * Math.min(1.0D, Math.max(0.0D, gearRatio));
        return Math.max(1, (int) Math.round(effectiveBase(waveIndex) * headMul * gearMul));
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
