package dev.modmind.irontide.menu;

import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;

/**
 * 核心交互面板的数据层。
 *
 * <p>服务端用 {@link CoreData} 直接代理 {@link AndesiteCoreBlockEntity}，客户端只拿一个
 * {@link SimpleContainerData}，值由 {@code ClientboundContainerSetDataPacket} 每秒推一次，
 * 不需要自定义网络包。按钮走原版 {@code handleInventoryButtonClick} 通道。</p>
 */
public class CoreMenu extends AbstractContainerMenu {
    public static final int BUTTON_RECHECK = 0;
    public static final int BUTTON_CLEAR_FUEL = 1;

    public static final int DATA_HEALTH = 0;
    public static final int DATA_HEALTH_MAX = 1;
    public static final int DATA_FUEL = 2;
    public static final int DATA_FUEL_CAPACITY = 3;
    public static final int DATA_POWER_STATUS = 4;
    public static final int DATA_STATE = 5;
    public static final int DATA_MISSING = 6;
    public static final int DATA_LAYOUT = 7;
    public static final int DATA_SIZE = 8;

    private static final int FUEL_SLOT_INDEX = 0;
    private static final int FUEL_SLOT_X = 23;
    private static final int FUEL_SLOT_Y = 66;
    private static final int INVENTORY_X = 23;
    private static final int INVENTORY_Y = 122;
    private static final int HOTBAR_Y = 184;

    private final ContainerLevelAccess access;
    private final ContainerData data;
    private final Container fuelSlot;

    /** 服务端构造：直接绑定方块实体。 */
    public CoreMenu(int containerId, Inventory playerInventory, AndesiteCoreBlockEntity core) {
        this(containerId, playerInventory,
                ContainerLevelAccess.create(core.getLevel(), core.getBlockPos()),
                new CoreData(core),
                core.getFuelSlot());
    }

    /**
     * 客户端构造：位置从网络包读，数据槽由服务端同步填充。
     *
     * <p>buf 可能为 null —— 原版 {@code MenuType#create(int, Inventory)} 这条无数据路径
     * 会传 null 进来。服务端必须用带 extra data 的 openMenu 才不会走到这里，但留着兜底，
     * 免得再出现"连接已丢失"。</p>
     */
    public CoreMenu(int containerId, Inventory playerInventory, @Nullable FriendlyByteBuf buf) {
        this(containerId, playerInventory,
                buf == null ? ContainerLevelAccess.NULL : clientAccess(playerInventory, buf.readBlockPos()),
                new SimpleContainerData(DATA_SIZE),
                new SimpleContainer(1) {
                    @Override
                    public boolean canPlaceItem(int index, ItemStack stack) {
                        return stack.getBurnTime(RecipeType.SMELTING) > 0;
                    }
                });
    }

    private static ContainerLevelAccess clientAccess(Inventory inventory, BlockPos pos) {
        return ContainerLevelAccess.create(inventory.player.level(), pos);
    }

    private CoreMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access,
                     ContainerData data, Container fuelSlot) {
        super(ModMindEntry.CORE_MENU.get(), containerId);
        this.access = access;
        this.data = data;
        this.fuelSlot = fuelSlot;

        addSlot(new Slot(fuelSlot, 0, FUEL_SLOT_X, FUEL_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getBurnTime(RecipeType.SMELTING) > 0;
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
        }

        addDataSlots(data);
    }

    public int value(int index) {
        return data.get(index);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        boolean[] handled = {false};
        access.execute((level, pos) -> {
            if (level.getBlockEntity(pos) instanceof AndesiteCoreBlockEntity core) {
                switch (id) {
                    case BUTTON_RECHECK -> core.validateNow();
                    case BUTTON_CLEAR_FUEL -> core.clearFuel();
                    default -> {
                        return;
                    }
                }
                handled[0] = true;
            }
        });
        return handled[0];
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index == FUEL_SLOT_INDEX) {
            if (!moveItemStackTo(stack, FUEL_SLOT_INDEX + 1, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, FUEL_SLOT_INDEX, FUEL_SLOT_INDEX + 1, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide && access == ContainerLevelAccess.NULL) {
            // 兜底路径：客户端不知道坐标时不做本地判定，交给服务端发关窗包
            return true;
        }
        return stillValid(access, player, ModMindEntry.ANDESITE_CORE.get());
    }

    /** 服务端数据代理：读的时候现取，不发副本。 */
    private static final class CoreData implements ContainerData {
        private final AndesiteCoreBlockEntity core;

        private CoreData(AndesiteCoreBlockEntity core) {
            this.core = core;
        }

        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_HEALTH -> core.getHealth();
                case DATA_HEALTH_MAX -> core.getHealthMax();
                case DATA_FUEL -> core.getFuel();
                case DATA_FUEL_CAPACITY -> core.getFuelCapacity();
                case DATA_POWER_STATUS -> core.getPowerStatus().ordinal();
                case DATA_STATE -> core.getState().ordinal();
                case DATA_MISSING -> core.getLastMissing();
                case DATA_LAYOUT -> core.getLastLayoutIndex();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // 只读：所有值都由方块实体掌握
        }

        @Override
        public int getCount() {
            return DATA_SIZE;
        }
    }

    public static @Nullable AndesiteCoreBlockEntity findCore(ContainerLevelAccess access) {
        AndesiteCoreBlockEntity[] found = {null};
        access.execute((level, pos) -> {
            if (level.getBlockEntity(pos) instanceof AndesiteCoreBlockEntity core) {
                found[0] = core;
            }
        });
        return found[0];
    }
}
