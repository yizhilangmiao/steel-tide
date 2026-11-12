package dev.modmind.irontide.client;

import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.block.CoreState;
import dev.modmind.irontide.menu.CoreMenu;
import dev.modmind.irontide.multiblock.MultiblockDefinition;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 核心面板：深棕标题带 + 米黄主体 + 像素风按钮，纯色自绘，不引用任何贴图。
 *
 * <p>配色按实际运行截图校准：标题带 {@code #4C3426}、压线 {@code #C08F55}、
 * 主体 {@code #E6D6AC}、燃料条底 {@code #8B7355}／填充 {@code #D7AA5E}。</p>
 */
public class CoreScreen extends AbstractContainerScreen<CoreMenu> {
    private static final int PANEL_WIDTH = 208;
    private static final int PANEL_HEIGHT = 230;

    // 与 CoreMenu 里的槽位坐标必须一致
    private static final int FUEL_SLOT_X = 23;
    private static final int FUEL_SLOT_Y = 68;
    private static final int MODULE_SLOT_X = 23;
    private static final int MODULE_SLOT_Y = 104;
    private static final int MODULE_SLOT_STEP = 20;
    private static final int MODULE_SLOT_COUNT = 4;
    private static final int INVENTORY_X = 23;
    private static final int INVENTORY_Y = 128;
    private static final int HOTBAR_Y = 188;

    private static final int TITLE_H = 18;
    private static final int INV_END = 182;

    private static final int BAR_X = 45;
    private static final int BAR_Y = 71;
    private static final int BAR_WIDTH = 140;
    private static final int BAR_HEIGHT = 10;

    private static final int BUTTON_Y = 208;

    private static final int OUTLINE = 0xFF2A1A0E;
    private static final int TITLE_BG = 0xFF4C3426;
    private static final int TITLE_TEXT = 0xFFE6D6AC;
    private static final int ACCENT_LINE = 0xFFC08F55;
    private static final int MAIN_BG = 0xFFE6D6AC;
    private static final int SLOT_FACE = 0xFFD7C59D;
    private static final int SLOT_DARK = 0xFFB9A580;
    private static final int SLOT_LIGHT = 0xFFF3E6C2;
    private static final int BAG_BG = 0xFFD7C59D;
    private static final int BAG_FACE = 0xFFCBAE7E;
    private static final int BAG_DARK = 0xFFB0956A;
    private static final int BAG_LIGHT = 0xFFE8D8B0;
    private static final int BAR_BG = 0xFF8B7355;
    private static final int BAR_EDGE = 0xFF6B5137;

    private static final int TEXT_MAIN = 0xFF3A2A18;
    private static final int TEXT_HEALTH = 0xFFA83A24;
    private static final int TEXT_OK = 0xFF3E7030;
    private static final int TEXT_WARN = 0xFFA05A18;
    private static final int TEXT_DIM = 0xFF6B5441;

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

        graphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, OUTLINE);
        graphics.fill(x + 1, y + 1, x + PANEL_WIDTH - 1, y + TITLE_H, TITLE_BG);
        graphics.fill(x + 1, y + TITLE_H, x + PANEL_WIDTH - 1, y + TITLE_H + 1, ACCENT_LINE);
        graphics.fill(x + 1, y + TITLE_H + 1, x + PANEL_WIDTH - 1, y + INV_END, MAIN_BG);

        // 背包区（略深一档，做出分区）
        graphics.fill(x + 1, y + INV_END, x + PANEL_WIDTH - 1, y + PANEL_HEIGHT - 1, BAG_BG);

        drawInset(graphics, x + FUEL_SLOT_X, y + FUEL_SLOT_Y, 16, 16, SLOT_FACE, SLOT_DARK, SLOT_LIGHT);

        // 四个模块槽：燃料 / 装甲 / 信标 / 传输
        for (int i = 0; i < MODULE_SLOT_COUNT; i++) {
            drawInset(graphics, x + MODULE_SLOT_X + i * MODULE_SLOT_STEP, y + MODULE_SLOT_Y,
                    16, 16, SLOT_FACE, SLOT_DARK, SLOT_LIGHT);
        }

        drawInset(graphics, x + BAR_X, y + BAR_Y, BAR_WIDTH, BAR_HEIGHT, BAR_BG, BAR_EDGE, SLOT_LIGHT);
        int capacity = Math.max(1, menu.value(CoreMenu.DATA_FUEL_CAPACITY));
        int fuel = menu.value(CoreMenu.DATA_FUEL);
        int filled = (int) Math.round((BAR_WIDTH - 2) * Math.min(1.0D, (double) fuel / capacity));
        if (filled > 0) {
            graphics.fill(x + BAR_X + 1, y + BAR_Y + 1, x + BAR_X + 1 + filled, y + BAR_Y + BAR_HEIGHT - 1,
                    fuelColor(fuel, capacity));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawInset(graphics, x + INVENTORY_X + col * 18, y + INVENTORY_Y + row * 18,
                        16, 16, BAG_FACE, BAG_DARK, BAG_LIGHT);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawInset(graphics, x + INVENTORY_X + col * 18, y + HOTBAR_Y,
                    16, 16, BAG_FACE, BAG_DARK, BAG_LIGHT);
        }
    }

    /** 内凹槽：左上一道暗线、右下一道亮线。 */
    private static void drawInset(GuiGraphics graphics, int x, int y, int w, int h, int face, int dark, int light) {
        graphics.fill(x, y, x + w, y + h, face);
        graphics.fill(x, y, x + w, y + 1, dark);
        graphics.fill(x, y, x + 1, y + h, dark);
        graphics.fill(x, y + h - 1, x + w, y + h, light);
        graphics.fill(x + w - 1, y, x + w, y + h, light);
    }

    private static int fuelColor(int fuel, int capacity) {
        if (fuel <= 0) {
            return 0xFF6B5137;
        }
        double ratio = (double) fuel / capacity;
        if (ratio < 0.15D) {
            return 0xFFA8483A;
        }
        if (ratio < 0.40D) {
            return 0xFFC08F3A;
        }
        return 0xFFD7AA5E;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "钢铁潮汐 · 安山核心", 12, 5, TITLE_TEXT, false);

        int health = menu.value(CoreMenu.DATA_HEALTH);
        int healthMax = menu.value(CoreMenu.DATA_HEALTH_MAX);
        graphics.drawString(font, "生命 " + health + " / " + healthMax, 12, 26, TEXT_HEALTH, false);

        // 四态展示：未成型 / 运转中（含燃料不足）/ 停机 / 已摧毁
        CoreState state = CoreState.values()[Math.max(0, Math.min(CoreState.values().length - 1,
                menu.value(CoreMenu.DATA_STATE)))];
        int power = menu.value(CoreMenu.DATA_POWER_STATUS);
        boolean active = state == CoreState.ACTIVE;
        String statusLine;
        int statusColor;
        if (state == CoreState.DESTROYED) {
            statusLine = "状态 已摧毁（需要维修）";
            statusColor = TEXT_HEALTH;
        } else if (!active) {
            statusLine = "状态 未成型";
            statusColor = TEXT_WARN;
        } else if (power == AndesiteCoreBlockEntity.FuelStatus.STOPPED.ordinal()) {
            statusLine = "状态 停机（无燃料）";
            statusColor = TEXT_WARN;
        } else if (power == AndesiteCoreBlockEntity.FuelStatus.LOW.ordinal()) {
            statusLine = "状态 运转中 · 燃料不足";
            statusColor = TEXT_WARN;
        } else {
            statusLine = "状态 运转中";
            statusColor = TEXT_OK;
        }
        graphics.drawString(font, statusLine, 12, 42, statusColor, false);
        if (active) {
            graphics.drawString(font, "朝向 " + MultiblockDefinition.layoutLabel(menu.value(CoreMenu.DATA_LAYOUT)),
                    12, 56, TEXT_DIM, false);
        } else {
            graphics.drawString(font, "缺口 " + menu.value(CoreMenu.DATA_MISSING) + " 格", 12, 56, TEXT_DIM, false);
        }

        int capacity = Math.max(1, menu.value(CoreMenu.DATA_FUEL_CAPACITY));
        int fuel = menu.value(CoreMenu.DATA_FUEL);
        int percent = (int) Math.round(fuel * 100.0D / capacity);
        graphics.drawString(font, "燃料 " + fuel + " / " + capacity + "（" + percent + "%）· "
                        + powerText(menu.value(CoreMenu.DATA_POWER_STATUS)),
                12, 90, TEXT_MAIN, false);
        graphics.drawString(font, "模块", 108, MODULE_SLOT_Y + 4, TEXT_DIM, false);

        boolean fresh = Util.getMillis() < feedbackUntil;
        graphics.drawString(font, fresh ? feedback : "手持可燃物右键核心可投料",
                12, 120, fresh ? TEXT_OK : 0xFF7A6547, false);
    }

    private static String powerText(int ordinal) {
        return switch (ordinal) {
            case 0 -> "供能正常";
            case 1 -> "低功率（燃料低于 15%）";
            default -> "无燃料，已停机";
        };
    }
}
