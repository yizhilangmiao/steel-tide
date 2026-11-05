package dev.modmind.irontide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 机械动力风格的按钮：纯色区块 + 硬边高光，hover 时描一圈暖黄边。
 *
 * <p>原版按钮的灰色贴图在 Created 风格面板上格格不入；这里完全自绘，
 * 不依赖任何 png，也就绕开了资源包许可证问题。</p>
 */
public class CreateButton extends Button {
    private static final int BORDER = 0xFF000000;
    private static final int FACE_IDLE = 0xFF9F9F9F;
    private static final int FACE_HOT = 0xFFC6C6C6;
    private static final int HILIGHT_IDLE = 0xFFB8B8B8;
    private static final int HILIGHT_HOT = 0xFFE8E8E8;
    private static final int TEXT_IDLE = 0xFF2F2F2F;
    private static final int TEXT_HOT = 0xFF1A1A1A;
    private static final int OUTLINE_HOT = 0xFFFFD07A;

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

        graphics.fill(x, y, x + w, y + h, BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, hot ? FACE_HOT : FACE_IDLE);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 2, hot ? HILIGHT_HOT : HILIGHT_IDLE);
        graphics.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF6B6B6B);
        if (hot) {
            graphics.renderOutline(x, y, w, h, OUTLINE_HOT);
        }
        graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                x + w / 2, y + (h - 8) / 2 + 1, hot ? TEXT_HOT : TEXT_IDLE);
    }
}
