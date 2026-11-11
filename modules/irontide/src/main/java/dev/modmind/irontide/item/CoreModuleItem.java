package dev.modmind.irontide.item;

import dev.modmind.irontide.config.ModuleType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 核心模块物品。本身不做任何事——插进核心面板才生效，数值全在 {@code core.json} 的 modules 段。
 * 等级 I/II/III 是三个独立物品，不做 NBT 升级。
 */
public class CoreModuleItem extends Item {
    private final ModuleType type;
    private final int tier;

    public CoreModuleItem(ModuleType type, int tier, Properties properties) {
        super(properties);
        this.type = type;
        this.tier = tier;
    }

    public ModuleType moduleType() {
        return type;
    }

    public int tier() {
        return tier;
    }

    public String romanTier() {
        return switch (tier) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> String.valueOf(tier);
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("核心模块 · " + type.label() + " " + romanTier())
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("右键核心打开面板后放入对应槽位").withStyle(ChatFormatting.GRAY));
    }
}
