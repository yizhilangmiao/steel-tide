package dev.modmind.irontide.client;

import dev.modmind.irontide.block.CoreState;
import dev.modmind.irontide.menu.CoreMenu;
import dev.modmind.irontide.multiblock.MultiblockDefinition;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 核心面板。视觉照机械动力（Create）的容器界面走：浅灰标题带、深灰主区、
 * 木纹背包区、亮木快捷栏，全部纯色矩形自绘，不引用任何贴图。
 *
 * <p>配色取自 create-mcGUI 资源包（ARR，只借配色不拷文件）：
 * {@code #C6C6C6} 标题带、{@code #787878} 压线、{@code #575757} 主区、
 * {@code #8B8B8B} 槽面、{@code #926651}/{@code #8B584B} 木纹、{@code #B59370} 快捷栏。</p>
 */
public class CoreScreen extends AbstractContainerScreen<CoreMenu> {
    private static final int PANEL_WIDTH = 208;
    private static final int PANEL_HEIGHT = 230;

    // 与 CoreMenu 里的槽位坐标必须一致
    private static final int FUEL_SLOT_X = 23;
    private static final int FUEL_SLOT_Y = 66;
    private static final int INVENTORY_X = 23;
    private static final int INVENTORY_Y = 122;
    private static final int HOTBAR_Y = 184;

    private static final int TITLE_H = 18;
    private static final int MAIN_END = 112;
    private static final int INV_END = 178;
    private static final int HOTBAR_END = 204;

    private static final int BAR_X = 48;
    private static final int BAR_Y = 69;
    private static final int BAR_WIDTH = 140;
    private static final int BAR_HEIGHT = 10;

    private static final int BUTTON_Y = 208;

    // Create 风格色板
    private static final int BLACK = 0xFF000000;
    private static final int TITLE_BG = 0xFFC6C6C6;
    private static final int TITLE_LINE = 0xFF787878;
    private static final int TITLE_TOP = 0xFFE8E8E8;
    private static final int MAIN_BG = 0xFF575757;
    private static final int MAIN_DARK = 0xFF393939;
    private static final int SLOT_FACE = 0xFF8B8B8B;
    private static final int SLOT_DARK = 0xFF4A4A4A;
    private static final int SLOT_LIGHT = 0xFFC6C6C6;
    private static final int WOOD_LIGHT = 0xFF926651;
    private static final int WOOD_DARK = 0xFF8B584B;
    private static final int WOOD_EDGE = 0xFF5E393E;
    private static final int WOOD_TOP = 0xFFA87060;
    private static final int HOTBAR_BG = 0xFFB59370;
    private static final int HOTBAR_DARK = 0xFF8C5D4B;
    private static final int HOTBAR_LIGHT = 0xFFC9A882;

    private static final int TEXT_DARK = 0xFF3A3A3A;
    private static final int TEXT_LIGHT = 0xFFE8E8E8;
    private static final int TEXT_DIM = 0xFFB0B0B0;
    private static final int TEXT_HEALTH = 0xFFFF9E8A;
    private static final int TEXT_OK = 0xFF7CE07C;
    private static final int TEXT_WARN = 0xFFFFC24D;

    private String feedback = "";
    private long feedbackUntil;

    public CoreScreen(CoreMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(CreateButton.create(Component.literal("复查结构"), button -> press(CoreMenu.BUTTON_RECHECK, "结构已重新校验"),
                leftPos + 12, topPos + BUTTON_Y, 88, 18));
        addRenderableWidget(CreateButton.create(Component.literal("清空燃料"), button -> press(CoreMenu.BUTTON_CLEAR_FUEL, "燃料已清零"),
                leftPos + 108, topPos + BUTTON_Y, 88, 18));
    }

    private void press(int id, String message) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            feedback = message;
            feedbackUntil = Util.getMillis() + 2200L;
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // 底 + 外框
        graphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, BLACK);

        // 标题带
        graphics.fill(x + 1, y + 1, x + PANEL_WIDTH - 1, y + TITLE_H, TITLE_BG);
        graphics.fill(x + 1, y + 1, x + PANEL_WIDTH - 1, y + 2, TITLE_TOP);
        graphics.fill(x + 1, y + TITLE_H - 1, x + PANEL_WIDTH - 1, y + TITLE_H, TITLE_LINE);

        // 主区
        graphics.fill(x + 1, y + TITLE_H, x + PANEL_WIDTH - 1, y + MAIN_END, MAIN_BG);
        graphics.fill(x + 1, y + TITLE_H, x + PANEL_WIDTH - 1, y + TITLE_H + 1, MAIN_DARK);

        // 木纹背包区
        graphics.fill(x + 1, y + MAIN_END, x + PANEL_WIDTH - 1, y + INV_END, WOOD_LIGHT);
        graphics.fill(x + 1, y + MAIN_END, x + PANEL_WIDTH - 1, y + MAIN_END + 1, WOOD_TOP);
        for (int i = 4; i < PANEL_WIDTH - 4; i += 8) {
            graphics.fill(x + i, y + MAIN_END + 1, x + i + 3, y + INV_END - 1, WOOD_DARK);
        }

        // 快捷栏区
        graphics.fill(x + 1, y + INV_END, x + PANEL_WIDTH - 1, y + HOTBAR_END, HOTBAR_BG);
        graphics.fill(x + 1, y + INV_END, x + PANEL_WIDTH - 1, y + INV_END + 1, HOTBAR_LIGHT);
        graphics.fill(x + 1, y + HOTBAR_END - 1, x + PANEL_WIDTH - 1, y + HOTBAR_END, HOTBAR_DARK);

        // 按钮区
        graphics.fill(x + 1, y + HOTBAR_END, x + PANEL_WIDTH - 1, y + PANEL_HEIGHT - 1, MAIN_DARK);

        // 燃料槽（主区上的浅灰内凹槽）
        drawSlot(graphics, x + FUEL_SLOT_X, y + FUEL_SLOT_Y, SLOT_FACE, SLOT_DARK, SLOT_LIGHT);

        // 燃料条（深色内凹 + 填充）
        drawInset(graphics, x + BAR_X, y + BAR_Y, BAR_WIDTH, BAR_HEIGHT, MAIN_DARK, 0xFF202020, SLOT_LIGHT);
        int capacity = Math.max(1, menu.value(CoreMenu.DATA_FUEL_CAPACITY));
        int fuel = menu.value(CoreMenu.DATA_FUEL);
        int filled = (int) Math.round((BAR_WIDTH - 2) * Math.min(1.0D, (double) fuel / capacity));
        if (filled > 0) {
            graphics.fill(x + BAR_X + 1, y + BAR_Y + 1, x + BAR_X + 1 + filled, y + BAR_Y + BAR_HEIGHT - 1,
                    fuelColor(fuel, capacity));
        }

        // 背包（木纹槽）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlot(graphics, x + INVENTORY_X + col * 18, y + INVENTORY_Y + row * 18,
                        WOOD_DARK, WOOD_EDGE, WOOD_TOP);
            }
        }
        // 快捷栏（亮木槽）
        for (int col = 0; col < 9; col++) {
            drawSlot(graphics, x + INVENTORY_X + col * 18, y + HOTBAR_Y,
                    HOTBAR_BG, HOTBAR_DARK, HOTBAR_LIGHT);
        }
    }

    /** Create 风格的槽位：左上一道暗线、右下一道亮线，做出内凹的立体感。 */
    private static void drawSlot(GuiGraphics graphics, int x, int y, int face, int dark, int light) {
        graphics.fill(x, y, x + 16, y + 16, face);
        graphics.fill(x, y, x + 16, y + 1, dark);
        graphics.fill(x, y, x + 1, y + 16, dark);
        graphics.fill(x, y + 15, x + 16, y + 16, light);
        graphics.fill(x + 15, y, x + 16, y + 16, light);
    }

    private static void drawInset(GuiGraphics graphics, int x, int y, int w, int h, int face, int dark, int light) {
        graphics.fill(x, y, x + w, y + h, face);
        graphics.fill(x, y, x + w, y + 1, dark);
        graphics.fill(x, y, x + 1, y + h, dark);
        graphics.fill(x, y + h - 1, x + w, y + h, light);
        graphics.fill(x + w - 1, y, x + w, y + h, light);
    }

    private static int fuelColor(int fuel, int capacity) {
        if (fuel <= 0) {
            return 0xFF4A4A4A;
        }
        double ratio = (double) fuel / capacity;
        if (ratio < 0.15D) {
            return 0xFFB4453C;
        }
        if (ratio < 0.40D) {
            return 0xFFC98A3C;
        }
        return 0xFFE8B04B;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "钢铁潮汐 · 安山核心", 12, 5, TEXT_DARK, false);

        int health = menu.value(CoreMenu.DATA_HEALTH);
        int healthMax = menu.value(CoreMenu.DATA_HEALTH_MAX);
        graphics.drawString(font, "生命 " + health + " / " + healthMax, 12, 25, TEXT_HEALTH, false);

        boolean active = menu.value(CoreMenu.DATA_STATE) == CoreState.ACTIVE.ordinal();
        graphics.drawString(font, active ? "结构 完整（已激活）" : "结构 不完整", 12, 39,
                active ? TEXT_OK : TEXT_WARN, false);
        if (active) {
            graphics.drawString(font, "朝向 " + MultiblockDefinition.layoutLabel(menu.value(CoreMenu.DATA_LAYOUT)),
                    12, 51, TEXT_DIM, false);
        } else {
            graphics.drawString(font, "缺口 " + menu.value(CoreMenu.DATA_MISSING) + " 格", 12, 51, TEXT_DIM, false);
        }

        int capacity = Math.max(1, menu.value(CoreMenu.DATA_FUEL_CAPACITY));
        int fuel = menu.value(CoreMenu.DATA_FUEL);
        int percent = (int) Math.round(fuel * 100.0D / capacity);
        graphics.drawString(font, "燃料 " + fuel + " / " + capacity + "（" + percent + "%）",
                BAR_X, BAR_Y + BAR_HEIGHT + 5, TEXT_LIGHT, false);
        graphics.drawString(font, powerText(menu.value(CoreMenu.DATA_POWER_STATUS)),
                BAR_X, BAR_Y + BAR_HEIGHT + 17, TEXT_DIM, false);

        boolean fresh = Util.getMillis() < feedbackUntil;
        graphics.drawString(font, fresh ? feedback : "左边格子放可燃物，会自动烧成燃料",
                12, 100, fresh ? TEXT_OK : 0xFF909090, false);
    }

    private static String powerText(int ordinal) {
        return switch (ordinal) {
            case 0 -> "供能正常";
            case 1 -> "低功率（燃料低于 15%）";
            default -> "无燃料，已停机";
        };
    }
}
