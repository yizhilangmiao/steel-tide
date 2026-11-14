package dev.modmind.irontide.client;

import dev.modmind.irontide.net.SteamHudPayload;

/**
 * 客户端侧的蒸汽值缓存。服务端每 20 tick 推一次，超过 {@link #STALE_MS} 没收到就当没在打怪潮——
 * 断线重连、切维度、退世界都不会留一条冻住的 HUD。
 */
public final class SteamHudClientState {
    private static final long STALE_MS = 5000L;
    /** 收到 active=false 后再留这么久才真正收起，避免开关抖动把血条闪一下。 */
    private static final long LINGER_MS = 4000L;

    /** 受伤闪红持续时间（毫秒）。 */
    private static final long FLASH_MS = 700L;

    private static int health;
    private static int maxHealth;
    private static int wave;
    private static int remaining;
    private static int daysToWave = -1;
    private static int subWave;
    private static int spawned;
    private static int target;
    private static int nextSpawnTicks;
    private static int nextSubWaveTicks;
    private static int hitPulse = Integer.MIN_VALUE;
    private static long flashUntil;
    private static boolean active;
    private static long activeUntil;
    private static long lastUpdate;
    /** 同一波里只许往下掉，服务端数值偶尔抖一下也不会让血条往回弹。 */
    private static int displayHealth;

    private SteamHudClientState() {
    }

    public static void accept(SteamHudPayload payload) {
        if (payload.wave() != wave || payload.maxHealth() != maxHealth) {
            displayHealth = payload.health();
        }
        displayHealth = Math.min(displayHealth, payload.health());

        if (payload.hitPulse() != hitPulse) {
            if (hitPulse != Integer.MIN_VALUE) {
                // 只在脉搏序号真的前进时闪一次，重连收到旧值不会闪
                flashUntil = System.currentTimeMillis() + FLASH_MS;
            }
            hitPulse = payload.hitPulse();
        }

        health = payload.health();
        maxHealth = payload.maxHealth();
        wave = payload.wave();
        remaining = payload.remaining();
        daysToWave = payload.daysToWave();
        subWave = payload.subWave();
        spawned = payload.spawned();
        target = payload.target();
        nextSpawnTicks = payload.nextSpawnTicks();
        nextSubWaveTicks = payload.nextSubWaveTicks();
        lastUpdate = System.currentTimeMillis();
        if (payload.active()) {
            active = true;
            activeUntil = System.currentTimeMillis() + LINGER_MS;
        } else if (System.currentTimeMillis() > activeUntil) {
            active = false;
        }
    }

    public static boolean visible() {
        return active && maxHealth > 0 && System.currentTimeMillis() - lastUpdate < STALE_MS;
    }

    /** 最近还在收包（不要求正在打怪潮）：顶部倒计时用。 */
    public static boolean fresh() {
        return System.currentTimeMillis() - lastUpdate < STALE_MS;
    }

    public static int health() {
        return displayHealth;
    }

    public static int maxHealth() {
        return maxHealth;
    }

    public static int wave() {
        return wave;
    }

    public static int remaining() {
        return remaining;
    }

    public static int daysToWave() {
        return daysToWave;
    }

    public static int subWave() {
        return subWave;
    }

    public static int spawned() {
        return spawned;
    }

    public static int target() {
        return target;
    }

    public static int nextSpawnTicks() {
        return nextSpawnTicks;
    }

    public static int nextSubWaveTicks() {
        return nextSubWaveTicks;
    }

    /** 受伤闪红强度 0..1，按 {@link #FLASH_MS} 线性衰减。 */
    public static float flashRatio() {
        long left = flashUntil - System.currentTimeMillis();
        if (left <= 0L) {
            return 0.0F;
        }
        return Math.min(1.0F, left / (float) FLASH_MS);
    }

    /** 小波进度 0..1（已生成/目标），目标未知时返回 0。 */
    public static float waveProgress() {
        if (target <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, spawned / (float) target));
    }

    /** 非效能：只用来算血条比例，0..1。 */
    public static float ratio() {
        if (maxHealth <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, displayHealth / (float) maxHealth));
    }
}
