package dev.modmind.irontide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 像素风按钮：浅灰面 + 深灰硬边 + 底部投影，配米黄色面板。
 * hover 时面变亮、移开立刻复原，不会像原版按钮那样高亮黏住。
 */
public class CreateButton extends Button {
    private static final int EDGE = 0xFF373737;
    private static final int FACE = 0xFFC6C6C6;
    private static final int FACE_HOT = 0xFFDDDDDD;
    private static final int SHADOW = 0xFF8B8B8B;
    private static final int TEXT = 0xFF373737;

    private CreateButton(Builder builder) {
        super(builder);
    }

    public static CreateButton create(Component label, OnPress onPress, int x, int y, int width, int height) {
        return new CreateButton(Button.builder(label, onPress).bounds(x, y, width, height));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hot = isHoveredOrFocused();
        int x = getX();
        int y = getY();
        int w = width;
        int h = height;

        graphics.fill(x, y, x + w, y + h, EDGE);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 3, hot ? FACE_HOT : FACE);
        graphics.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, SHADOW);
        // 关掉投影：浅灰底上带 dropShadow 会让字看起来重影发糊
        String label = getMessage().getString();
        graphics.drawString(Minecraft.getInstance().font, label,
                x + (w - Minecraft.getInstance().font.width(label)) / 2, y + (h - 8) / 2, TEXT, false);
    }
}
