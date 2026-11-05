package dev.modmind.irontide;

import com.mojang.logging.LogUtils;
import dev.modmind.irontide.block.AndesiteCoreBlock;
import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.event.IronTideEvents;
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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AndesiteCoreBlockEntity>> ANDESITE_CORE_BE =
            BLOCK_ENTITIES.register("andesite_core",
                    () -> BlockEntityType.Builder.of(AndesiteCoreBlockEntity::new, ANDESITE_CORE.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ITEM_GROUP = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.irontide"))
                    .icon(() -> new ItemStack(ANDESITE_CORE.get()))
                    .displayItems((parameters, output) -> output.accept(ANDESITE_CORE.get()))
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
