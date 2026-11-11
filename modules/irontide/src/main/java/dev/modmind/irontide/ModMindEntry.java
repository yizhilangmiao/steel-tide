package dev.modmind.irontide;

import com.mojang.logging.LogUtils;
import dev.modmind.irontide.block.AndesiteCoreBlock;
import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.config.ModuleType;
import dev.modmind.irontide.event.IronTideEvents;
import dev.modmind.irontide.item.CoreModuleItem;
import dev.modmind.irontide.menu.CoreMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(ModMindEntry.MOD_ID)
public final class ModMindEntry {
    public static final String MOD_ID = "irontide";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MOD_ID);

    public static final DeferredBlock<AndesiteCoreBlock> ANDESITE_CORE = BLOCKS.register("andesite_core",
            () -> new AndesiteCoreBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)));

    public static final DeferredItem<BlockItem> ANDESITE_CORE_ITEM = ITEMS.register("andesite_core",
            () -> new BlockItem(ANDESITE_CORE.get(), new Item.Properties()));

    // 四类模块 × 三级 = 12 个物品
    public static final DeferredItem<CoreModuleItem> FUEL_MODULE_I = module(ModuleType.FUEL, 1);
    public static final DeferredItem<CoreModuleItem> FUEL_MODULE_II = module(ModuleType.FUEL, 2);
    public static final DeferredItem<CoreModuleItem> FUEL_MODULE_III = module(ModuleType.FUEL, 3);
    public static final DeferredItem<CoreModuleItem> ARMOR_MODULE_I = module(ModuleType.ARMOR, 1);
    public static final DeferredItem<CoreModuleItem> ARMOR_MODULE_II = module(ModuleType.ARMOR, 2);
    public static final DeferredItem<CoreModuleItem> ARMOR_MODULE_III = module(ModuleType.ARMOR, 3);
    public static final DeferredItem<CoreModuleItem> BEACON_MODULE_I = module(ModuleType.BEACON, 1);
    public static final DeferredItem<CoreModuleItem> BEACON_MODULE_II = module(ModuleType.BEACON, 2);
    public static final DeferredItem<CoreModuleItem> BEACON_MODULE_III = module(ModuleType.BEACON, 3);
    public static final DeferredItem<CoreModuleItem> TRANSFER_MODULE_I = module(ModuleType.TRANSFER, 1);
    public static final DeferredItem<CoreModuleItem> TRANSFER_MODULE_II = module(ModuleType.TRANSFER, 2);
    public static final DeferredItem<CoreModuleItem> TRANSFER_MODULE_III = module(ModuleType.TRANSFER, 3);

    /** 按 {@code <type>_module_<tier>} 命名注册一个模块物品，堆叠上限 1（要占槽位）。 */
    private static DeferredItem<CoreModuleItem> module(ModuleType type, int tier) {
        String name = type.name().toLowerCase(java.util.Locale.ROOT) + "_module_" + tier;
        return ITEMS.register(name, () -> new CoreModuleItem(type, tier, new Item.Properties().stacksTo(1)));
    }

    /** 怪潮残骸：守住一波发的通用货币，后续和海图/遗迹挂钩。 */
    public static final DeferredItem<Item> RAID_SCRAP = ITEMS.register("raid_scrap",
            () -> new Item(new Item.Properties()));

    /** 核心维修套件：核心被摧毁后用它右键复活。 */
    public static final DeferredItem<Item> CORE_REPAIR_KIT = ITEMS.register("core_repair_kit",
            () -> new Item(new Item.Properties().stacksTo(16)));

    /** 弹壳：铜板经动量锯切出的半成品，后续装药与弹头拼成完整弹药。 */
    public static final DeferredItem<Item> BULLET_CASING = ITEMS.register("bullet_casing",
            () -> new Item(new Item.Properties()));

    /** 发射药：火药与木炭经搅拌机研磨而成，弹壳的装药。 */
    public static final DeferredItem<Item> PROPELLANT = ITEMS.register("propellant",
            () -> new Item(new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AndesiteCoreBlockEntity>> ANDESITE_CORE_BE =
            BLOCK_ENTITIES.register("andesite_core",
                    () -> BlockEntityType.Builder.of(AndesiteCoreBlockEntity::new, ANDESITE_CORE.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ITEM_GROUP = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.irontide"))
                    .icon(() -> new ItemStack(ANDESITE_CORE.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ANDESITE_CORE.get());
                        output.accept(FUEL_MODULE_I.get());
                        output.accept(FUEL_MODULE_II.get());
                        output.accept(FUEL_MODULE_III.get());
                        output.accept(ARMOR_MODULE_I.get());
                        output.accept(ARMOR_MODULE_II.get());
                        output.accept(ARMOR_MODULE_III.get());
                        output.accept(BEACON_MODULE_I.get());
                        output.accept(BEACON_MODULE_II.get());
                        output.accept(BEACON_MODULE_III.get());
                        output.accept(TRANSFER_MODULE_I.get());
                        output.accept(TRANSFER_MODULE_II.get());
                        output.accept(TRANSFER_MODULE_III.get());
                        output.accept(RAID_SCRAP.get());
                        output.accept(CORE_REPAIR_KIT.get());
                        output.accept(BULLET_CASING.get());
                        output.accept(PROPELLANT.get());
                    })
                    .build());

    public static final DeferredHolder<MenuType<?>, MenuType<CoreMenu>> CORE_MENU = MENUS.register("andesite_core",
            () -> IMenuTypeExtension.create((containerId, inventory, data) -> new CoreMenu(containerId, inventory, data)));

    public ModMindEntry(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        MENUS.register(modEventBus);
        IronTideEvents.register();
        LOGGER.info("[IronTide] initialized");
    }
}
