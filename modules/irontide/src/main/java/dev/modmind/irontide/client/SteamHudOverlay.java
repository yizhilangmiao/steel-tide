package dev.modmind.irontide.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 屏幕最左侧的「蒸汽值」竖条。只在怪潮期间出现，数据由 {@link SteamHudClientState} 提供。
 * <p>
 * 纯色自绘，不依赖贴图；配色沿用核心面板：深棕外框 + 米黄描字 + 橙金填充，
 * 低于 40% 转深橙、低于 15% 转砖红。
 */
public final class SteamHudOverlay {
    private static final int X = 6;
    private static final int WIDTH = 14;
    private static final int MAX_HEIGHT = 150;

    private static final int OUTLINE = 0xFF2A1A0E;
    private static final int TROUGH = 0xFF8B7355;
    private static final int TROUGH_DARK = 0xFF6B5137;
    private static final int FILL_HIGH = 0xFFD7AA5E;
    private static final int FILL_MID = 0xFFC08F3A;
    private static final int FILL_LOW = 0xFFA8483A;
    private static final int TEXT = 0xFFE6D6AC;
    private static final int TEXT_DIM = 0xFFA08A6A;
    /** 大波固定 3 小波，与服务端 payload 同源。 */
    private static final int SUB_WAVE_SLOTS = dev.modmind.irontide.net.SteamHudPayload.SUB_WAVES;

    private SteamHudOverlay() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        renderFlash(graphics, minecraft);
        renderTopPanel(graphics, minecraft);
        renderSteamBar(graphics, minecraft);
    }

    private static void renderFlash(GuiGraphics graphics, Minecraft minecraft) {
        float flash = SteamHudClientState.flashRatio();
        if (flash <= 0.0F) {
            return;
        }
        int alpha = (int) (Math.min(1.0F, flash) * 120.0F) << 24;
        int color = alpha | 0x8A1A10;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int edge = 6;
        graphics.fill(0, 0, width, edge, color);
        graphics.fill(0, height - edge, width, height, color);
        graphics.fill(0, 0, edge, height, color);
        graphics.fill(width - edge, 0, width, height, color);
    }

    private static void renderTopPanel(GuiGraphics graphics, Minecraft minecraft) {
        if (!SteamHudClientState.fresh()) {
            return;
        }
        boolean combat = SteamHudClientState.visible();
        int days = SteamHudClientState.daysToWave();
        if (!combat && days < 0) {
            return;
        }
        int width = minecraft.getWindow().getGuiScaledWidth();
        String headline = days < 0 ? "怪潮状态未知"
                : days <= 0 ? "今晚怪潮" : "距下一次怪潮 " + days + " 天";
        centered(graphics, minecraft, headline, width / 2, 6, TEXT);
        if (!combat) {
            return;
        }
        int y = 18;
        String waveLine = "第 " + SteamHudClientState.wave() + " 波 · 小波 "
                + SteamHudClientState.subWave() + "/" + SUB_WAVE_SLOTS
                + " · 生成 " + SteamHudClientState.spawned() + "/" + SteamHudClientState.target()
                + " · 存活 " + SteamHudClientState.remaining();
        centered(graphics, minecraft, waveLine, width / 2, y, TEXT);

        int barWidth = 160;
        int barX = width / 2 - barWidth / 2;
        int barY = y + 11;
        graphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 4, OUTLINE);
        graphics.fill(barX, barY, barX + barWidth, barY + 3, TROUGH_DARK);
        int filled = Math.round(barWidth * SteamHudClientState.waveProgress());
        if (filled > 0) {
            graphics.fill(barX, barY, barX + filled, barY + 3, FILL_HIGH);
        }

        int nextSpawn = SteamHudClientState.nextSpawnTicks();
        int nextSubWave = SteamHudClientState.nextSubWaveTicks();
        String countdown;
        if (nextSubWave > 0) {
            countdown = "下一小波 " + seconds(nextSubWave) + "s";
        } else if (nextSpawn > 0) {
            countdown = "下一批 " + seconds(nextSpawn) + "s";
        } else {
            countdown = "本小波已刷完";
        }
        centered(graphics, minecraft, countdown, width / 2, barY + 7, TEXT_DIM);
    }

    private static void renderSteamBar(GuiGraphics graphics, Minecraft minecraft) {
        if (!SteamHudClientState.visible()) {
            return;
        }
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int barHeight = Math.min(MAX_HEIGHT, Math.max(80, screenHeight / 2));
        int top = (screenHeight - barHeight) / 2;
        int bottom = top + barHeight;

        graphics.fill(X - 1, top - 1, X + WIDTH + 1, bottom + 1, OUTLINE);
        graphics.fill(X, top, X + WIDTH, bottom, TROUGH);
        graphics.fill(X, top, X + WIDTH - 1, top + 1, TROUGH_DARK);

        float ratio = SteamHudClientState.ratio();
        int fillHeight = Math.round(barHeight * ratio);
        int color = ratio > 0.40F ? FILL_HIGH : ratio > 0.15F ? FILL_MID : FILL_LOW;
        if (fillHeight > 0) {
            graphics.fill(X, bottom - fillHeight, X + WIDTH, bottom, color);
        }

        for (int i = 1; i <= 3; i++) {
            int lineY = bottom - barHeight * i / 4;
            graphics.fill(X, lineY, X + WIDTH, lineY + 1, 0x40000000);
        }

        graphics.drawString(minecraft.font, "蒸汽", X - 1, top - 12, TEXT, false);
        graphics.drawString(minecraft.font,
                SteamHudClientState.health() + "/" + SteamHudClientState.maxHealth(),
                X, bottom + 4, TEXT_DIM, false);
    }

    private static void centered(GuiGraphics graphics, Minecraft minecraft, String text, int centerX, int y,
                                 int color) {
        int width = minecraft.font.width(text);
        graphics.drawString(minecraft.font, text, centerX - width / 2, y, color, true);
    }

    private static String seconds(int ticks) {
        return String.valueOf(Math.max(0, (ticks + 19) / 20));
    }
}
