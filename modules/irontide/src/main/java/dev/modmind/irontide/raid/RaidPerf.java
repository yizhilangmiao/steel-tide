package dev.modmind.irontide.raid;

import dev.modmind.irontide.ModMindEntry;
import net.minecraft.server.MinecraftServer;

/**
 * 怪潮性能采样（W4「MSPT 实测」）。
 * <p>
 * 不依赖任何内部性能 API：按 tick 数 + {@link System#nanoTime()} 自己算窗口内的平均
 * 每 tick 毫秒（MSPT）。每 100 tick（5 秒）打一行日志，超过 50ms（等于掉到 20 TPS 以下）打 warn。
 * 只在怪潮期间采样，平时不产生日志。
 */
public final class RaidPerf {
    private static final int WINDOW_TICKS = 100;
    private static final float WARN_MSPT = 50.0F;

    private static long windowStartNanos;
    private static int windowStartTicks;
    private static float lastMspt;
    private static float peakMspt;
    private static int raiderPeak;

    private RaidPerf() {
    }

    public static void begin(MinecraftServer server) {
        reset();
        windowStartNanos = System.nanoTime();
        windowStartTicks = server.getTickCount();
    }

    public static void reset() {
        lastMspt = 0.0F;
        peakMspt = 0.0F;
        raiderPeak = 0;
    }

    /** 每个检查周期调用一次；不足一个窗口就只更新峰值怪数。 */
    public static void sample(MinecraftServer server, int raiders) {
        raiderPeak = Math.max(raiderPeak, raiders);
        int ticks = server.getTickCount();
        int delta = ticks - windowStartTicks;
        if (delta < WINDOW_TICKS) {
            return;
        }
        long now = System.nanoTime();
        float mspt = (float) ((now - windowStartNanos) / 1_000_000.0D / delta);
        lastMspt = mspt;
        peakMspt = Math.max(peakMspt, mspt);
        windowStartNanos = now;
        windowStartTicks = ticks;
        String line = String.format("[IronTide] raid perf mspt=%.1f peak=%.1f raiders=%d", mspt, peakMspt, raiderPeak);
        if (mspt >= WARN_MSPT) {
            ModMindEntry.LOGGER.warn("{} (低于 20 TPS)", line);
        } else {
            ModMindEntry.LOGGER.info(line);
        }
    }

    public static float lastMspt() {
        return lastMspt;
    }

    public static float peakMspt() {
        return peakMspt;
    }

    public static int raiderPeak() {
        return raiderPeak;
    }
}