package dev.modmind.irontide.client;

import dev.modmind.irontide.ModMindEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 「退出世界卡在保存中」诊断探针。
 *
 * <p>退出单人世界时，客户端会停在 {@code menu.savingLevel}（保存世界中）这个界面上自旋，
 * 一直到集成服务器线程结束（vanilla: Minecraft#disconnect 里的
 * {@code while (!integratedServer.isShutdown()) runTick(false)}）。正常情况一秒内就结束；
 * 超过 20 秒说明某个 mod 把 Server thread 卡在 stopServer() 尾部，原版不会有任何日志，
 * 所以这里主动把 Server thread / Render thread 的堆栈写进日志，方便事后定位。</p>
 *
 * <p>只在卡住时工作，正常退出世界不产生任何日志。</p>
 */
@EventBusSubscriber(modid = ModMindEntry.MOD_ID, value = Dist.CLIENT)
public final class StuckShutdownWatchdog {
    /** 20 秒 = 400 tick，正常退出世界远小于这个值。 */
    private static final int WAIT_TICKS_BEFORE_DUMP = 400;
    /** 一次卡死最多抓 3 次，避免刷爆日志。 */
    private static final int MAX_DUMPS = 3;

    private static int waitingTicks;
    private static int dumps;

    private StuckShutdownWatchdog() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isWaitingOnSavingScreen(minecraft)) {
            waitingTicks = 0;
            dumps = 0;
            return;
        }
        if (++waitingTicks < WAIT_TICKS_BEFORE_DUMP) {
            return;
        }
        waitingTicks = 0;
        if (dumps >= MAX_DUMPS) {
            return;
        }
        dumps++;
        dumpThreads(dumps);
    }

    private static boolean isWaitingOnSavingScreen(Minecraft minecraft) {
        if (!(minecraft.screen instanceof GenericMessageScreen)) {
            return false;
        }
        String title = minecraft.screen.getTitle().getString();
        String savingTitle = Component.translatable("menu.savingLevel").getString();
        if (!savingTitle.equals(title)) {
            return false;
        }
        return minecraft.getSingleplayerServer() != null && !minecraft.getSingleplayerServer().isShutdown();
    }

    private static void dumpThreads(int round) {
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        List<Thread> targets = new ArrayList<>();
        StringBuilder inventory = new StringBuilder("[IronTide] 退出世界卡住超过 20 秒（第 ")
                .append(round)
                .append(" 次抓取），线程清单：");
        for (Thread thread : traces.keySet()) {
            String name = thread.getName();
            inventory.append(name).append('=').append(thread.getState()).append(" | ");
            if ("Server thread".equals(name) || "Render thread".equals(name)) {
                targets.add(thread);
            }
        }
        ModMindEntry.LOGGER.error(inventory.toString());
        for (Thread thread : targets) {
            StringBuilder builder = new StringBuilder("[IronTide] 卡住线程 ")
                    .append(thread.getName())
                    .append(" state=")
                    .append(thread.getState());
            StackTraceElement[] stack = traces.get(thread);
            if (stack != null) {
                for (StackTraceElement element : stack) {
                    builder.append("\n\tat ").append(element);
                }
            }
            ModMindEntry.LOGGER.error(builder.toString());
        }
    }
}