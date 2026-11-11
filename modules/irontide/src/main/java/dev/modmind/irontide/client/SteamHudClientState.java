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

    private static int health;
    private static int maxHealth;
    private static int wave;
    private static int remaining;
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

        health = payload.health();
        maxHealth = payload.maxHealth();
        wave = payload.wave();
        remaining = payload.remaining();
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

    /** 非效能：只用来算血条比例，0..1。 */
    public static float ratio() {
        if (maxHealth <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, displayHealth / (float) maxHealth));
    }
}
