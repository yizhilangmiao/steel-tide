package dev.modmind.irontide.block;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.config.CoreConfig;
import dev.modmind.irontide.config.ModuleSpec;
import dev.modmind.irontide.config.ModuleType;
import dev.modmind.irontide.data.IronTideData;
import dev.modmind.irontide.item.CoreModuleItem;
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
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AndesiteCoreBlockEntity extends GeneratingKineticBlockEntity {
    private static final Set<AndesiteCoreBlockEntity> LOADED = ConcurrentHashMap.newKeySet();
    private static final int TICK_INTERVAL = 20;
    private static final int SAVE_INTERVAL_TICKS = 5;
    /** 成型/还原每 tick 最多改这么多格，T4 的 729 格分 12 tick 完成。 */
    private static final int MATERIAL_SWAP_BATCH = 64;
    /** 只发客户端更新与形状更新，绝不触发邻居更新，避免校验递归。 */
    private static final int MATERIAL_SWAP_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** 满供能输出转速与应力容量；低燃料降到 1/4。数值对应设计 §2.3.1。 */
    private static final float SPEED_FULL = 64.0F;
    private static final float SPEED_LOW = 16.0F;
    private static final float CAPACITY_FULL = 1024.0F;
    private static final float CAPACITY_LOW = 256.0F;

    /**
     * 四种布局下核心的外露方向，与 {@code MultiblockDefinition} 的旋转顺序一致。
     */
    private static final net.minecraft.core.Direction[] LAYOUT_EXIT = {
            net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.EAST,
            net.minecraft.core.Direction.SOUTH,
            net.minecraft.core.Direction.WEST
    };

    // 掉血用原版「僵尸砸铁门」，和 Create 机器的持续嗡嗡声完全分得开；
    // 摧毁保留 Create 汽笛做停机感，末尾补一记铁砧落地。
    private static final SoundEvent CREATE_WHISTLE_LOW = variableEvent("create", "whistle_low");

    private static SoundEvent variableEvent(String namespace, String path) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

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
    /** 结构本身算出的生命上限（不含装甲模块），healthMax = 这个值 + 装甲加成。 */
    private int structureHealthMax;

    /** 正在分批换壳；不写存档，重启后由状态跃迁重新驱动。 */
    private boolean forming;
    private boolean unforming;
    private final ArrayDeque<FormStep> formQueue = new ArrayDeque<>();
    private final ArrayDeque<UnformStep> unformQueue = new ArrayDeque<>();
    private int swapChanged;

    private record FormStep(BlockPos pos, BlockState target, BlockState original) {
    }

    private record UnformStep(BlockPos pos, BlockState fallback) {
    }

    /** 四个模块槽，顺序与 {@link #slotOf(ModuleType)} 一致：燃料 / 装甲 / 信标 / 传输。 */
    private final SimpleContainer moduleSlots = new SimpleContainer(4) {
        @Override
        public void setChanged() {
            AndesiteCoreBlockEntity.this.setChanged();
            AndesiteCoreBlockEntity.this.onModulesChanged();
        }

        @Override
        public boolean canPlaceItem(int index, ItemStack stack) {
            return stack.getItem() instanceof CoreModuleItem module && slotOf(module.moduleType()) == index;
        }
    };

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
    public void addBehaviours(java.util.List<BlockEntityBehaviour> behaviours) {
        // 核心不带传送带/机械臂一类行为，保持空实现
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        serverTick();
    }

    /** 只有结构完整且有燃料才输出旋转；低燃料档降到 16 RPM。 */
    @Override
    public float getGeneratedSpeed() {
        if (state != CoreState.ACTIVE || powerStatus == FuelStatus.STOPPED) {
            return 0.0F;
        }
        return powerStatus == FuelStatus.LOW ? SPEED_LOW : SPEED_FULL;
    }

    @Override
    public float calculateAddedStressCapacity() {
        if (state != CoreState.ACTIVE || powerStatus == FuelStatus.STOPPED) {
            return 0.0F;
        }
        return powerStatus == FuelStatus.LOW ? CAPACITY_LOW : CAPACITY_FULL;
    }

    /** 转速或朝向变了才推一次动力学网络，避免每秒重建。 */
    private void refreshRotation() {
        if (level == null || level.isClientSide) {
            return;
        }
        updateGeneratedRotation();
    }

    /** 按当前布局把输出轴方向写进方块状态，供 {@code IRotate} 判断哪一面能接轴。 */
    private void applyExitDirection(int layoutIndex) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        int index = Math.max(0, Math.min(LAYOUT_EXIT.length - 1, layoutIndex));
        net.minecraft.core.Direction want = LAYOUT_EXIT[index];
        BlockState current = getBlockState();
        if (!current.hasProperty(AndesiteCoreBlock.FACING) || current.getValue(AndesiteCoreBlock.FACING) == want) {
            return;
        }
        serverLevel.setBlock(worldPosition, current.setValue(AndesiteCoreBlock.FACING, want), 3);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            LOADED.add(this);
        }
    }

    @Override
    public void invalidate() {
        // SmartBlockEntity 把 setRemoved() 定成 final，卸载登记改挂在 invalidate
        LOADED.remove(this);
        super.invalidate();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        // SmartBlockEntity 把 saveAdditional/loadAdditional 定成 final，只能走 write/read
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            return;
        }
        tag.putString("structureId", structureId);
        tag.putString("state", state.name());
        tag.putInt("health", health);
        tag.putInt("healthMax", healthMax);
        tag.putInt("structureHealthMax", structureHealthMax);
        tag.putInt("fuel", fuel);
        tag.put("fuelSlot", fuelSlot.createTag(registries));
        tag.put("moduleSlots", moduleSlots.createTag(registries));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            return;
        }
        structureId = tag.getString("structureId");
        state = CoreState.byName(tag.getString("state"));
        health = tag.getInt("health");
        healthMax = tag.getInt("healthMax");
        structureHealthMax = tag.getInt("structureHealthMax");
        if (structureHealthMax <= 0) {
            // 老存档没有这个字段：那时 healthMax 就是结构值，补一份，免得装装甲时被当成 0 重算
            structureHealthMax = Math.max(0, healthMax);
        }
        fuel = Math.max(0, tag.getInt("fuel"));
        fuelSlot.fromTag(tag.getList("fuelSlot", Tag.TAG_COMPOUND), registries);
        moduleSlots.fromTag(tag.getList("moduleSlots", Tag.TAG_COMPOUND), registries);
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
        if (level instanceof ServerLevel serverLevel) {
            processMaterialSwap(serverLevel);
        }
        if (++tickAccumulator < TICK_INTERVAL) {
            return;
        }
        tickAccumulator = 0;

        // 每秒自检一次：/setblock、蓝图、活塞这类非玩家放置不会触发 BlockEvent，
        // 没有这条自检，结构搭满了也要等玩家右键核心才有反应。
        if (state == CoreState.INCOMPLETE && !forming) {
            validateNow();
        }

        pullFromSlot();

        CoreConfig config = IronTideData.config();
        if (state == CoreState.ACTIVE && fuel > 0) {
            // 效率越低烧得越快：容量换效率，是这个模块的核心取舍
            double efficiency = Math.max(0.1D, getBurnEfficiency());
            int burn = (int) Math.round(Math.max(0.0D, config.burnPerSecond()) / efficiency);
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

    public Container getModuleSlots() {
        return moduleSlots;
    }

    /** 模块类型 → 槽位下标。顺序就是面板里从左到右四个槽。 */
    public static int slotOf(ModuleType type) {
        return switch (type) {
            case FUEL -> 0;
            case ARMOR -> 1;
            case BEACON -> 2;
            case TRANSFER -> 3;
        };
    }

    /**
     * 取槽里那枚模块的数值。没装、装错槽、配置没加载都返回 {@link ModuleSpec#EMPTY}，
     * 调用方只需要判断字段是否为 0，不用处理 null。
     */
    public ModuleSpec moduleSpec(ModuleType type) {
        if (!IronTideData.isLoaded()) {
            return ModuleSpec.EMPTY;
        }
        ItemStack stack = moduleSlots.getItem(slotOf(type));
        if (stack.getItem() instanceof CoreModuleItem module && module.moduleType() == type) {
            return IronTideData.config().module(type, module.tier());
        }
        return ModuleSpec.EMPTY;
    }

    /** 装甲模块带来的额外生命上限，没装就是 0。 */
    public int getArmorBonusHealth() {
        return Math.max(0, moduleSpec(ModuleType.ARMOR).armorHealth());
    }

    /** 装甲模块的减伤百分比（0.10 = 10%），与机壳减伤乘算叠加。 */
    public double getArmorReduction() {
        return Math.max(0.0D, Math.min(1.0D, moduleSpec(ModuleType.ARMOR).armorReduction()));
    }

    /** 信标模块的奖励加成（0.10 = +10%），战后结算用。 */
    public double getBeaconReward() {
        return Math.max(0.0D, moduleSpec(ModuleType.BEACON).beaconReward());
    }

    /** 传输模块允许的接轴档位：0 仅水平 / 1 水平+垂直 / 2 任意轴。 */
    public int getTransferAxes() {
        return Math.max(0, Math.min(2, moduleSpec(ModuleType.TRANSFER).transferAxes()));
    }

    /**
     * 模块变化后重算受影响的派生值：装甲改生命上限（按比例保留当前血量），燃料改容量（超上限就压回去）。
     * 槽位内容由容器自己存档，这里不写 NBT。
     */
    private void onModulesChanged() {
        if (!IronTideData.isLoaded()) {
            return;
        }
        if (state == CoreState.ACTIVE) {
            int newMax = baseHealthFor(healthMax, getArmorBonusHealth());
            if (newMax > 0 && newMax != healthMax) {
                double ratio = healthMax > 0 ? (double) health / healthMax : 1.0D;
                healthMax = newMax;
                health = Math.max(1, Math.min(newMax, (int) Math.round(newMax * ratio)));
            }
        }
        int capacity = getFuelCapacity();
        if (fuel > capacity) {
            fuel = capacity;
        }
        updatePowerState(true);
        refreshRotation();
        setChanged();
    }

    /** 生命上限 = 结构算出来的基础值 + 装甲模块加成。 */
    private static int baseHealthFor(int structureMax, int armorBonus) {
        return Math.max(1, structureMax + armorBonus);
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
            // 供能档位决定输出转速，档位一变就得重推动力学网络
            refreshRotation();
        }
    }

    public int getFuel() {
        return fuel;
    }

    public int getFuelCapacity() {
        if (!IronTideData.isLoaded()) {
            return 12000;
        }
        CoreConfig config = IronTideData.config();
        ModuleSpec fuelModule = moduleSpec(ModuleType.FUEL);
        if (fuelModule.fuelCapacity() > 0) {
            return fuelModule.fuelCapacity();
        }
        return Math.max(1, config.noFuelModuleCapacity());
    }

    /** 燃烧效率：装了燃料模块用模块值，没装走应急档（更高效率、更小容量）。 */
    public double getBurnEfficiency() {
        ModuleSpec fuelModule = moduleSpec(ModuleType.FUEL);
        if (fuelModule.fuelEfficiency() > 0.0D) {
            return fuelModule.fuelEfficiency();
        }
        return IronTideData.isLoaded() ? IronTideData.config().noFuelModuleEfficiency() : 1.5D;
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
     * Re-reads the multiblock structure around this controller and updates the minimal state
     * machine. 校验本身不改方块；只有 complete 与 INCOMPLETE 之间的状态跃迁会驱动外壳
     * 材质切换，且切换受 {@link #forming} 守卫与 materialSwap.enabled 总开关约束。
     */
    public @Nullable StructureCheckResult validateNow() {
        if (!(level instanceof ServerLevel serverLevel) || !IronTideData.isLoaded()) {
            return null;
        }
        if (forming) {
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
        boolean activatedNow = false;
        if (result.complete()) {
            if (state == CoreState.DESTROYED) {
                // 已摧毁：结构修好也不会自动复活，等维修系统（后续切片）
            } else if (state != CoreState.ACTIVE) {
                int max = (int) Math.round(config.healthBase() * result.healthMul());
                structureHealthMax = max;
                state = CoreState.ACTIVE;
                healthMax = baseHealthFor(max, getArmorBonusHealth());
                health = healthMax;
                activatedNow = true;
                setChanged();
                ModMindEntry.LOGGER.info(
                        "[IronTide] {} at {} activated: layout={} health={}/{} healthMul={} armor={} shell={} brass={}",
                        definition.id(), worldPosition, result.layoutLabel(), health, healthMax,
                        format(result.healthMul()), format(result.armor()),
                        result.shellCells(), result.brassCells());
            } else if (healthMax <= 0) {
                int max = (int) Math.round(config.healthBase() * result.healthMul());
                structureHealthMax = max;
                healthMax = baseHealthFor(max, getArmorBonusHealth());
                health = healthMax;
                setChanged();
            }
        } else {
            // 先启动还原，再把状态置为 INCOMPLETE；还原队列在 serverTick 分批执行。
            unformStructure(serverLevel, definition, result.layoutIndex());
            if (state != CoreState.INCOMPLETE) {
                state = CoreState.INCOMPLETE;
                setChanged();
                logIncomplete(definition, result);
            }
        }
        if (result.complete()) {
            applyExitDirection(result.layoutIndex());
            if (activatedNow) {
                formStructure(serverLevel, definition, result);
            }
        }
        updatePowerState(false);
        refreshRotation();
        return result;
    }

    public boolean isForming() {
        return forming;
    }

    /**
     * 从 ACTIVE 跃迁时启动换壳队列。只处理 expected 为安山/黄铜机壳的格，并按当前世界
     * 方块决定壳类型；已经是壳的格跳过，绝不把壳本身记成“原方块”。
     */
    private void formStructure(ServerLevel level, MultiblockDefinition definition,
                               StructureCheckResult result) {
        CoreConfig config = IronTideData.config();
        if (forming || !config.materialSwapEnabled() || !config.hasFormedCasing()) {
            return;
        }
        List<MultiblockDefinition.Cell> layout = definition.layouts().get(result.layoutIndex());
        if (layout == null) {
            return;
        }

        formQueue.clear();
        unformQueue.clear();
        for (MultiblockDefinition.Cell cell : layout) {
            if (!isShellCell(cell, config)) {
                continue;
            }
            BlockPos pos = worldPosition.offset(cell.dx(), cell.dy(), cell.dz());
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState current = level.getBlockState(pos);
            if (current.getBlock() instanceof ShellBlock) {
                continue;
            }
            BlockState target = config.formedFor(current.getBlock());
            if (target == null) {
                continue;
            }
            formQueue.add(new FormStep(pos, target, current));
        }
        if (formQueue.isEmpty()) {
            return;
        }
        unforming = false;
        swapChanged = 0;
        forming = true;
    }

    /**
     * 结构不完整时启动还原队列。壳的原状态优先从壳自己的 BE 读；BE 缺失或原状态是空气时
     * 按该格 expected 方块兜底。已经变成空气/别的方块的格不会被还原，玩家刚挖掉的格保持空气。
     */
    private void unformStructure(ServerLevel level, MultiblockDefinition definition, int layoutIndex) {
        CoreConfig config = IronTideData.config();
        if (forming || !config.materialSwapEnabled() || !config.hasFormedCasing()) {
            return;
        }
        formQueue.clear();
        unformQueue.clear();
        fillUnformQueue(level, definition, layoutIndex, config);
        if (unformQueue.isEmpty()) {
            return;
        }
        unforming = true;
        swapChanged = 0;
        forming = true;
    }

    /** 控制器被拆时使用：同步还原所有壳，保证 BE 消失前不再有孤儿壳。 */
    public void unformImmediately() {
        if (!(level instanceof ServerLevel serverLevel) || !IronTideData.isLoaded()) {
            return;
        }
        CoreConfig config = IronTideData.config();
        if (!config.materialSwapEnabled() || !config.hasFormedCasing()) {
            return;
        }
        MultiblockDefinition definition = IronTideData.definitions().get(structureId);
        if (definition == null) {
            definition = IronTideData.definitions().primary();
        }
        if (definition == null) {
            return;
        }

        formQueue.clear();
        unformQueue.clear();
        forming = true;
        unforming = true;
        swapChanged = 0;
        try {
            fillUnformQueue(serverLevel, definition, lastLayoutIndex, config);
            while (!unformQueue.isEmpty()) {
                applyUnformStep(serverLevel, unformQueue.poll());
            }
        } finally {
            forming = false;
            unforming = false;
            int changed = swapChanged;
            swapChanged = 0;
            if (changed > 0) {
                ModMindEntry.LOGGER.info("[IronTide] unformed {} cells at {}", changed, worldPosition);
            }
        }
    }

    private void processMaterialSwap(ServerLevel level) {
        if (!forming) {
            return;
        }
        int processed = 0;
        try {
            while (processed < MATERIAL_SWAP_BATCH && !formQueue.isEmpty()) {
                applyFormStep(level, formQueue.poll());
                processed++;
            }
            while (processed < MATERIAL_SWAP_BATCH && !unformQueue.isEmpty()) {
                applyUnformStep(level, unformQueue.poll());
                processed++;
            }
        } finally {
            if (formQueue.isEmpty() && unformQueue.isEmpty()) {
                forming = false;
                boolean wasUnforming = unforming;
                unforming = false;
                int changed = swapChanged;
                swapChanged = 0;
                if (changed > 0) {
                    if (wasUnforming) {
                        ModMindEntry.LOGGER.info("[IronTide] unformed {} cells at {}", changed, worldPosition);
                    } else {
                        ModMindEntry.LOGGER.info("[IronTide] formed {} cells at {}", changed, worldPosition);
                    }
                }
            }
        }
    }

    private void applyFormStep(ServerLevel level, FormStep step) {
        if (!level.isLoaded(step.pos())) {
            return;
        }
        BlockState current = level.getBlockState(step.pos());
        if (!current.equals(step.original())) {
            return;
        }
        if (!level.setBlock(step.pos(), step.target(), MATERIAL_SWAP_FLAGS)) {
            return;
        }
        if (level.getBlockEntity(step.pos()) instanceof ShellBlockEntity shell) {
            shell.bind(worldPosition, step.original());
        }
        swapChanged++;
    }

    private void applyUnformStep(ServerLevel level, UnformStep step) {
        if (!level.isLoaded(step.pos())) {
            return;
        }
        BlockState current = level.getBlockState(step.pos());
        if (!(current.getBlock() instanceof ShellBlock)) {
            return;
        }
        BlockState restore = step.fallback();
        if (level.getBlockEntity(step.pos()) instanceof ShellBlockEntity shell) {
            BlockState original = shell.releaseOriginalState();
            if (!original.isAir()) {
                restore = original;
            }
        }
        if (restore.isAir()) {
            return;
        }
        if (level.setBlock(step.pos(), restore, MATERIAL_SWAP_FLAGS)) {
            swapChanged++;
        }
    }

    private void fillUnformQueue(ServerLevel level, MultiblockDefinition definition,
                                 int layoutIndex, CoreConfig config) {
        List<List<MultiblockDefinition.Cell>> layouts = definition.layouts();
        int safeIndex = Math.max(0, Math.min(layouts.size() - 1, layoutIndex));
        List<MultiblockDefinition.Cell> fallbackLayout = layouts.get(safeIndex);
        Map<BlockPos, BlockState> fallback = new LinkedHashMap<>();
        for (MultiblockDefinition.Cell cell : fallbackLayout) {
            if (!isShellCell(cell, config)) {
                continue;
            }
            BlockPos pos = worldPosition.offset(cell.dx(), cell.dy(), cell.dz());
            BlockState expectedState = expectedShellState(cell, config);
            if (!expectedState.isAir()) {
                fallback.putIfAbsent(pos, expectedState);
            }
        }

        // 用所有旋转布局的并集找壳：结构已经缺格时最近布局不一定是成型时的布局，
        // 只扫当前布局会漏掉其它朝向的壳。壳不可能长在骨架范围外。
        for (MultiblockDefinition.Cell cell : definition.layoutCells()) {
            if (!isShellCell(cell, config)) {
                continue;
            }
            BlockPos pos = worldPosition.offset(cell.dx(), cell.dy(), cell.dz());
            if (!level.isLoaded(pos) || !(level.getBlockState(pos).getBlock() instanceof ShellBlock)) {
                continue;
            }
            BlockState expectedState = fallback.get(pos);
            if (expectedState == null) {
                expectedState = expectedShellState(cell, config);
            }
            if (!expectedState.isAir()) {
                unformQueue.add(new UnformStep(pos, expectedState));
            }
        }
    }

    private static boolean isShellCell(MultiblockDefinition.Cell cell, CoreConfig config) {
        return cell.expected().equals(config.andesiteCasing().id())
                || cell.expected().equals(config.brassCasing().id());
    }

    private static BlockState expectedShellState(MultiblockDefinition.Cell cell, CoreConfig config) {
        if (cell.expected().equals(config.andesiteCasing().id())) {
            return config.andesiteCasing().block().defaultBlockState();
        }
        if (cell.expected().equals(config.brassCasing().id())) {
            return config.brassCasing().block().defaultBlockState();
        }
        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
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

    /**
     * 用维修套件修被摧毁的核心。结构不完整会失败且不扣套件 —— 免得玩家花了东西还修不好。
     *
     * @return true 表示修好了（调用方据此消耗交互结果）
     */
    public boolean repairFromKit(Player player, ItemStack kit) {
        if (state != CoreState.DESTROYED) {
            player.displayClientMessage(Component.literal("§7核心仍在运转，不需要维修。"), true);
            return false;
        }
        if (!(level instanceof ServerLevel)) {
            return false;
        }
        // 先临时解除 DESTROYED 才能走正常校验流程，校验不过再按回去
        state = CoreState.INCOMPLETE;
        StructureCheckResult result = validateNow();
        if (result == null) {
            // 换壳队列正在推进时校验会被守卫挡住，返回 null；这不算结构坏，别把核心按成 DESTROYED。
            state = CoreState.DESTROYED;
            setChanged();
            player.displayClientMessage(Component.literal("§c维修失败：换壳流程正忙，请稍后再试。"), true);
            return false;
        }
        if (state != CoreState.ACTIVE) {
            state = CoreState.DESTROYED;
            setChanged();
            player.displayClientMessage(Component.literal(
                    "§c维修失败：结构不完整（缺口 " + result.missingCount() + " 格）。"), true);
            return false;
        }

        kit.shrink(1);
        setChanged();
        player.displayClientMessage(Component.literal(
                "§a核心已修复，生命 " + health + " / " + healthMax + "。"), true);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, worldPosition, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.2F, 0.9F);
        }
        ModMindEntry.LOGGER.info("[IronTide] core at {} repaired by {} (health={}/{})",
                worldPosition, player.getName().getString(), health, healthMax);
        return true;
    }

    /** 怪潮攻核伤害。只在 ACTIVE 时生效；打到 0 停机并广播。 */
    public void applyRaidDamage(int amount) {
        if (amount <= 0 || state != CoreState.ACTIVE || health <= 0) {
            return;
        }
        // 装甲模块减伤，向上取整保证至少掉 1 点
        double reduction = getArmorReduction();
        int actual = (int) Math.max(1.0D, Math.round(amount * (1.0D - reduction)));
        health = Math.max(0, health - actual);
        setChanged();
        if (health > 0) {
            playHurtSound(actual);
            return;
        }
        state = CoreState.DESTROYED;
        ModMindEntry.LOGGER.info("[IronTide] core at {} destroyed by raid", worldPosition);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("§4[钢铁潮汐] §c安山核心被摧毁！坐标 "
                            + worldPosition.getX() + ", " + worldPosition.getY() + ", " + worldPosition.getZ()),
                    false);
        }
        playDestroySound();
        updatePowerState(true);
        refreshRotation();
    }

    /**
     * 掉血音效：原版「僵尸砸铁门」的闷响。怪潮时机器一直在响，蒸汽声会被糊掉，
     * 金属被砸的短促低频反而能一下听出来。
     *
     * <p>伤害越大叠的层数越多：单次 ≤11 一层、12~29 两层、≥30 三层，
     * 每层延后 2 tick、音调抬高、音量衰减，听起来像连着砸了好几记。</p>
     */
    private void playHurtSound(int actualDamage) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        double ratio = healthMax > 0 ? (double) health / healthMax : 1.0D;
        ratio = Math.max(0.0D, Math.min(1.0D, ratio));
        float basePitch = (float) (0.85D + 0.25D * ratio);
        float baseVolume = (float) (0.95D + 0.35D * (1.0D - ratio));
        int layers = actualDamage >= 30 ? 3 : actualDamage >= 12 ? 2 : 1;

        for (int i = 0; i < layers; i++) {
            float pitch = basePitch + i * 0.07F;
            float volume = baseVolume * (1.0F - i * 0.18F);
            int delay = i * 2;
            if (delay == 0) {
                serverLevel.playSound(null, worldPosition, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR,
                        SoundSource.BLOCKS, volume, pitch);
            } else {
                serverLevel.getServer().tell(new net.minecraft.server.TickTask(
                        serverLevel.getServer().getTickCount() + delay,
                        () -> serverLevel.playSound(null, worldPosition, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR,
                                SoundSource.BLOCKS, volume, pitch)));
            }
        }
    }

    /** 摧毁音效：Create 汽笛停机 + 10 tick 后一记铁砧砸地收尾。 */
    private void playDestroySound() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.playSound(null, worldPosition, CREATE_WHISTLE_LOW, SoundSource.BLOCKS, 1.6F, 0.75F);
        serverLevel.getServer().tell(new net.minecraft.server.TickTask(
                serverLevel.getServer().getTickCount() + 10,
                () -> serverLevel.playSound(null, worldPosition, SoundEvents.ANVIL_LAND,
                        SoundSource.BLOCKS, 1.4F, 0.55F)));
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
