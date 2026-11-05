package dev.modmind.irontide.block;

import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.config.CoreConfig;
import dev.modmind.irontide.data.IronTideData;
import dev.modmind.irontide.multiblock.MultiblockDefinition;
import dev.modmind.irontide.multiblock.StructureCheckResult;
import dev.modmind.irontide.multiblock.StructureValidator;
import dev.modmind.irontide.util.BlockNames;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AndesiteCoreBlockEntity extends BlockEntity {
    private static final Set<AndesiteCoreBlockEntity> LOADED = ConcurrentHashMap.newKeySet();
    private static final int TICK_INTERVAL = 20;
    private static final int SAVE_INTERVAL_TICKS = 5;

    /** 供能状态：结构完整且有燃料才算正常。应力输出尚未接入，目前只影响显示与后续档位。 */
    public enum FuelStatus {
        NORMAL, LOW, STOPPED
    }

    private String structureId = "";
    private CoreState state = CoreState.INCOMPLETE;
    private int health;
    private int healthMax;
    private int fuel;
    private int tickAccumulator;
    /** 面板里的 1 格燃料槽，放进去的可燃物每次 tick 被折算成燃料点数。 */
    private final SimpleContainer fuelSlot = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            AndesiteCoreBlockEntity.this.setChanged();
        }

        @Override
        public boolean canPlaceItem(int index, ItemStack stack) {
            return stack.getBurnTime(RecipeType.SMELTING) > 0;
        }
    };
    private int dirtyAccumulator;
    private FuelStatus powerStatus = FuelStatus.STOPPED;
    private int lastMissing;
    private int lastLayoutIndex;

    public AndesiteCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModMindEntry.ANDESITE_CORE_BE.get(), pos, state);
    }

    public static Set<AndesiteCoreBlockEntity> loaded() {
        return LOADED;
    }

    public static void clearLoaded() {
        LOADED.clear();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            LOADED.add(this);
        }
    }

    @Override
    public void setRemoved() {
        LOADED.remove(this);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("structureId", structureId);
        tag.putString("state", state.name());
        tag.putInt("health", health);
        tag.putInt("healthMax", healthMax);
        tag.putInt("fuel", fuel);
        tag.put("fuelSlot", fuelSlot.createTag(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        structureId = tag.getString("structureId");
        state = CoreState.byName(tag.getString("state"));
        health = tag.getInt("health");
        healthMax = tag.getInt("healthMax");
        fuel = Math.max(0, tag.getInt("fuel"));
        fuelSlot.fromTag(tag.getList("fuelSlot", Tag.TAG_COMPOUND), registries);
        powerStatus = FuelStatus.STOPPED;
    }

    public void onPlaced() {
        if (!(level instanceof ServerLevel) || !IronTideData.isLoaded()) {
            return;
        }
        if (structureId.isEmpty()) {
            MultiblockDefinition primary = IronTideData.definitions().primary();
            if (primary != null) {
                structureId = primary.id();
            }
        }
        validateNow();
    }

    /**
     * 每秒一次的燃料结算。结构不完整时停机、不耗燃料（见设计 §2.1）。
     * 燃料每秒按 config.fuel.burnPerSecond 扣减，耗尽后归零但不改结构状态。
     */
    public void serverTick() {
        if (!IronTideData.isLoaded()) {
            return;
        }
        if (++tickAccumulator < TICK_INTERVAL) {
            return;
        }
        tickAccumulator = 0;

        pullFromSlot();

        CoreConfig config = IronTideData.config();
        if (state == CoreState.ACTIVE && fuel > 0) {
            int burn = (int) Math.round(Math.max(0.0D, config.burnPerSecond()));
            if (burn > 0) {
                fuel = Math.max(0, fuel - burn);
                if (++dirtyAccumulator >= SAVE_INTERVAL_TICKS) {
                    dirtyAccumulator = 0;
                    setChanged();
                }
            }
        }
        updatePowerState(true);
    }

    /**
     * 从槽位取燃料：只有整块烧完也装得下时才消耗，避免余量被截断浪费掉。
     * 例如容量 12000 剩 800 时，煤炭 1600 不会被吃掉，等燃料降下来再自动烧。
     */
    private void pullFromSlot() {
        ItemStack stack = fuelSlot.getItem(0);
        if (stack.isEmpty()) {
            return;
        }
        int room = getFuelCapacity() - fuel;
        if (room <= 0) {
            return;
        }
        int burn = burnTimeOf(stack);
        if (burn <= 0 || burn > room) {
            return;
        }
        stack.shrink(1);
        fuel += burn;
        fuelSlot.setChanged();
        updatePowerState(true);
    }

    public Container getFuelSlot() {
        return fuelSlot;
    }

    /**
     * 投入可燃物。返回 false 表示该物品不是燃料，调用方应回落到默认交互。
     *
     * @param wholeStack true 一次投满整组，false 只投 1 个
     */
    public boolean feedFuel(ItemStack stack, Player player, boolean wholeStack) {
        if (!IronTideData.isLoaded()) {
            return false;
        }
        int burnTime = burnTimeOf(stack);
        if (burnTime <= 0) {
            return false;
        }
        CoreConfig config = IronTideData.config();
        int capacity = Math.max(1, config.fuelCapacity());
        String name = stack.getHoverName().getString();

        int room = capacity - fuel;
        if (room <= 0) {
            player.displayClientMessage(Component.literal(
                    "[钢铁潮汐] 燃料已满（" + fuel + " / " + capacity + "）"), true);
            return true;
        }
        int want = wholeStack ? stack.getCount() : 1;
        int fit = Math.min(want, room / burnTime);
        if (fit <= 0) {
            player.displayClientMessage(Component.literal(
                    "[钢铁潮汐] 剩余空间不足 1 个燃料（" + fuel + " / " + capacity + "）"), true);
            return true;
        }

        fuel += fit * burnTime;
        if (!player.getAbilities().instabuild) {
            stack.shrink(fit);
        }
        setChanged();
        updatePowerState(true);
        player.displayClientMessage(Component.literal(
                "[钢铁潮汐] 投入 " + fit + " × " + name + "（+" + (fit * burnTime) + "），燃料 "
                        + fuel + " / " + capacity + "｜" + powerStatusText()), true);
        return true;
    }

    /**
     * 1.21.1 NeoForge 的燃烧值走 FURNACE_FUELS 数据包映射，
     * IItemExtension#getBurnTime 默认实现就是查这张表，煤炭 = 1600、木板 = 300。
     */
    private int burnTimeOf(ItemStack stack) {
        return Math.max(0, stack.getBurnTime(RecipeType.SMELTING));
    }

    private void updatePowerState(boolean logChange) {
        if (!IronTideData.isLoaded()) {
            return;
        }
        CoreConfig config = IronTideData.config();
        int capacity = Math.max(1, config.fuelCapacity());
        if (fuel > capacity) {
            fuel = capacity;
        }
        FuelStatus next;
        if (fuel <= 0) {
            next = FuelStatus.STOPPED;
        } else if (fuel < capacity * config.lowFuelRatio()) {
            next = FuelStatus.LOW;
        } else {
            next = FuelStatus.NORMAL;
        }
        if (next != powerStatus) {
            FuelStatus previous = powerStatus;
            powerStatus = next;
            setChanged();
            if (logChange) {
                ModMindEntry.LOGGER.info("[IronTide] {} fuel status {} -> {} (fuel={}/{}, threshold={})",
                        worldPosition, previous, next, fuel, capacity, format(config.lowFuelRatio()));
            }
        }
    }

    public int getFuel() {
        return fuel;
    }

    public int getFuelCapacity() {
        if (!IronTideData.isLoaded()) {
            return 12000;
        }
        return Math.max(1, IronTideData.config().fuelCapacity());
    }

    /** 供面板「清空燃料」按钮使用：立刻归零并重算供能状态。 */
    public void clearFuel() {
        fuel = 0;
        setChanged();
        updatePowerState(true);
    }

    public int getLastMissing() {
        return lastMissing;
    }

    public int getLastLayoutIndex() {
        return lastLayoutIndex;
    }

    public FuelStatus getPowerStatus() {
        return powerStatus;
    }

    public String powerStatusText() {
        return switch (powerStatus) {
            case NORMAL -> "供能正常";
            case LOW -> "低功率（燃料低于 15%）";
            case STOPPED -> "无燃料，已停机";
        };
    }

    /**
     * Re-reads the multiblock structure around this controller and updates the minimal
     * state machine. Never edits blocks; an incomplete structure only changes the state.
     */
    public @Nullable StructureCheckResult validateNow() {
        if (!(level instanceof ServerLevel serverLevel) || !IronTideData.isLoaded()) {
            return null;
        }
        CoreConfig config = IronTideData.config();
        MultiblockDefinition definition = IronTideData.definitions().get(structureId);
        if (definition == null) {
            definition = IronTideData.definitions().primary();
            if (definition == null) {
                return null;
            }
            structureId = definition.id();
        }

        StructureCheckResult result = StructureValidator.check(serverLevel, worldPosition, definition, config);
        lastMissing = result.missingCount();
        lastLayoutIndex = result.layoutIndex();
        if (result.complete()) {
            if (state != CoreState.ACTIVE) {
                int max = (int) Math.round(config.healthBase() * result.healthMul());
                state = CoreState.ACTIVE;
                healthMax = max;
                health = max;
                setChanged();
                ModMindEntry.LOGGER.info(
                        "[IronTide] {} at {} activated: layout={} health={}/{} healthMul={} armor={} shell={} brass={}",
                        definition.id(), worldPosition, result.layoutLabel(), health, healthMax,
                        format(result.healthMul()), format(result.armor()),
                        result.shellCells(), result.brassCells());
            } else if (healthMax <= 0) {
                healthMax = (int) Math.round(config.healthBase() * result.healthMul());
                health = healthMax;
                setChanged();
            }
        } else if (state != CoreState.INCOMPLETE) {
            state = CoreState.INCOMPLETE;
            setChanged();
            logIncomplete(definition, result);
        }
        updatePowerState(false);
        return result;
    }

    private void logIncomplete(MultiblockDefinition definition, StructureCheckResult result) {
        String examples = result.missing().stream()
                .limit(3)
                .map(m -> "(" + m.pos().getX() + "," + m.pos().getY() + "," + m.pos().getZ() + ")="
                        + BlockNames.text(m.expected()))
                .collect(Collectors.joining(", "));
        Map<ResourceLocation, Long> counts = result.missing().stream()
                .collect(Collectors.groupingBy(StructureCheckResult.Missing::expected,
                        LinkedHashMap::new, Collectors.counting()));
        String summary = counts.entrySet().stream()
                .map(entry -> BlockNames.text(entry.getKey()) + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
        ModMindEntry.LOGGER.info(
                "[IronTide] {} at {} incomplete: layout={} missing {}/{} [{}] examples: [{}]",
                definition.id(), worldPosition, result.layoutLabel(), result.missingCount(),
                result.totalCells(), summary, examples);
    }

    public void reportStatus(Player player) {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        StructureCheckResult result = validateNow();
        if (result == null) {
            player.sendSystemMessage(Component.literal("[钢铁潮汐] 结构定义或配置尚未载入，无法校验"));
            return;
        }

        MultiblockDefinition definition = result.definition();
        CoreConfig config = IronTideData.config();
        if (result.complete()) {
            player.sendSystemMessage(Component.literal(
                    "[钢铁潮汐] 结构完整 / " + definition.tier() + " 已激活"));
            player.sendSystemMessage(Component.literal(
                    "[钢铁潮汐] 生命：" + health + " / " + healthMax
                            + "（基础 " + formatWhole(config.healthBase())
                            + " × 机壳倍率 " + format(result.healthMul()) + "）"));
            player.sendSystemMessage(Component.literal(
                    "[钢铁潮汐] 机壳：安山 " + result.andesiteCells() + " / 黄铜 " + result.brassCells()
                            + "（共 " + result.shellCells() + " 格），护甲 " + format(result.armor())
                            + "（slice 1 仅显示，未接入伤害减免）"));
        } else {
            player.sendSystemMessage(Component.literal(
                    "[钢铁潮汐] 结构不完整：缺少 " + result.missingCount() + " / " + result.totalCells() + " 格"));
            if (definition.layouts().size() > 1) {
                player.sendSystemMessage(Component.literal(
                        "[钢铁潮汐] 已按最接近的朝向 " + result.layoutLabel() + " 计算缺口（结构可四向旋转）"));
            }
            List<StructureCheckResult.Missing> missing = result.missing();
            for (int i = 0; i < Math.min(3, missing.size()); i++) {
                StructureCheckResult.Missing cell = missing.get(i);
                player.sendSystemMessage(Component.literal(
                                "[钢铁潮汐] 缺失示例：(" + cell.pos().getX() + ", " + cell.pos().getY() + ", "
                                        + cell.pos().getZ() + ") 需要 ")
                        .append(BlockNames.chat(cell.expected())));
            }
            if (healthMax > 0) {
                player.sendSystemMessage(Component.literal(
                        "[钢铁潮汐] 当前状态：INCOMPLETE，生命保持 " + health + " / " + healthMax));
            } else {
                player.sendSystemMessage(Component.literal(
                        "[钢铁潮汐] 当前状态：INCOMPLETE，尚未激活"));
            }
        }

        int capacity = Math.max(1, config.fuelCapacity());
        int percent = (int) Math.round(fuel * 100.0D / capacity);
        player.sendSystemMessage(Component.literal(
                "[钢铁潮汐] 燃料：" + fuel + " / " + capacity + "（" + percent + "%）｜" + powerStatusText()));
        if (state == CoreState.ACTIVE) {
            player.sendSystemMessage(Component.literal(
                    "[钢铁潮汐] 手持可燃物右键核心可投入燃料（潜行右键只投 1 个）"));
        }
    }

    public String getStructureId() {
        return structureId;
    }

    public CoreState getState() {
        return state;
    }

    public int getHealth() {
        return health;
    }

    public int getHealthMax() {
        return healthMax;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String formatWhole(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
