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

    private SteamHudOverlay() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!SteamHudClientState.visible()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }

        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int barHeight = Math.min(MAX_HEIGHT, Math.max(80, screenHeight / 2));
        int top = (screenHeight - barHeight) / 2;
        int bottom = top + barHeight;

        // 外框 + 底槽
        graphics.fill(X - 1, top - 1, X + WIDTH + 1, bottom + 1, OUTLINE);
        graphics.fill(X, top, X + WIDTH, bottom, TROUGH);
        graphics.fill(X, top, X + WIDTH - 1, top + 1, TROUGH_DARK);

        // 填充：从下往上
        float ratio = SteamHudClientState.ratio();
        int fillHeight = Math.round(barHeight * ratio);
        int color = ratio > 0.40F ? FILL_HIGH : ratio > 0.15F ? FILL_MID : FILL_LOW;
        if (fillHeight > 0) {
            graphics.fill(X, bottom - fillHeight, X + WIDTH, bottom, color);
        }

        // 四分之一刻度
        for (int i = 1; i <= 3; i++) {
            int lineY = bottom - barHeight * i / 4;
            graphics.fill(X, lineY, X + WIDTH, lineY + 1, 0x40000000);
        }

        // 文字：标题 / 数值 / 波次
        graphics.drawString(minecraft.font, "蒸汽", X - 1, top - 12, TEXT, false);
        graphics.drawString(minecraft.font,
                SteamHudClientState.health() + "/" + SteamHudClientState.maxHealth(),
                X, bottom + 4, TEXT_DIM, false);
        if (SteamHudClientState.wave() > 0) {
            graphics.drawString(minecraft.font,
                    "第" + SteamHudClientState.wave() + "波 剩" + SteamHudClientState.remaining(),
                    X, bottom + 15, TEXT_DIM, false);
        }
    }
}
