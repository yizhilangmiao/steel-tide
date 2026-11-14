package dev.modmind.irontide.event;

import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.command.IronTideCommand;
import dev.modmind.irontide.data.IronTideData;
import dev.modmind.irontide.multiblock.StructureValidator;
import dev.modmind.irontide.raid.RaidScheduler;
import dev.modmind.irontide.raid.SafeZone;
import dev.modmind.irontide.raid.ZoneFlattener;
import dev.modmind.irontide.util.BlockNames;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

public final class IronTideEvents {
    private static int tickCounter;
    /** 空气墙钩子：每 2 tick 跑一次，不沿用 RaidScheduler 的 20 tick 主循环。 */
    private static int wallCounter;

    private IronTideEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onServerStopped);
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onEntityPlace);
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onMobSpawn);
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onLivingDrops);
        NeoForge.EVENT_BUS.addListener(IronTideEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(IronTideCommand::register);
    }

    /** 记录玩家死亡点，供 {@code /irontide back} 使用（不依赖 FTB Essentials）。 */
    private static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            IronTideCommand.rememberDeath(player);
        }
    }

    /**
     * 怪潮怪物掉硬币。硬币没有任何合成配方（Numismatics 本就不给），
     * 所以这是玩家唯一的"印钞"之外的正规来源之一（另一个是商店买卖）。
     * 没装 Numismatics 时静默跳过。
     */
    private static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (!RaidScheduler.isRaider(mob.getUUID())) {
            return;
        }
        int wave = RaidScheduler.currentWave();
        var random = level.random;
        int spurs = 1 + (random.nextInt(3) == 0 ? 1 : 0);
        dropCoin(level, mob, event, "spur", spurs);
        if (wave >= 5 && random.nextFloat() < 0.10F) {
            dropCoin(level, mob, event, "bevel", 1);
        }
    }

    private static void dropCoin(ServerLevel level, Mob mob, LivingDropsEvent event, String path, int count) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("numismatics", path));
        if (item == Items.AIR) {
            return;
        }
        ItemEntity drop = new ItemEntity(level, mob.getX(), mob.getY() + 0.5D, mob.getZ(),
                new ItemStack(item, count));
        drop.setDefaultPickUpDelay();
        event.getDrops().add(drop);
    }

    /**
     * 安全区内一律不刷怪。这是"安全区"的硬保证——光照只是视觉，拦在这里才真的不刷。
     */
    private static void onMobSpawn(MobSpawnEvent.PositionCheck event) {
        if (!SafeZone.isActive()) {
            return;
        }
        int x = (int) Math.floor(event.getX());
        int y = (int) Math.floor(event.getY());
        int z = (int) Math.floor(event.getZ());
        if (SafeZone.contains(x, y, z)) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        IronTideData.load(FMLPaths.CONFIGDIR.get());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        BlockNames.load(event.getServer());
        revalidateAll();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        AndesiteCoreBlockEntity.clearLoaded();
        RaidScheduler.reset();
        tickCounter = 0;
        wallCounter = 0;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (IronTideData.isLoaded()) {
            RaidScheduler.tick(event.getServer());
            if (++wallCounter >= 2) {
                wallCounter = 0;
                RaidScheduler.tickAirWall(event.getServer());
            }
            // 成型后的地形平整：每 tick 推进 64 列，不在跑时只做一次布尔判断
            ZoneFlattener.tick();
        }
        if (++tickCounter < 100) {
            return;
        }
        tickCounter = 0;
        revalidateAll();
    }

    private static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        MinecraftServer server = level.getServer();
        server.execute(() -> StructureValidator.revalidateAround(level, pos));
    }

    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        MinecraftServer server = level.getServer();
        // BreakEvent fires before the block is removed, so revalidate after this interaction.
        server.execute(() -> StructureValidator.revalidateAround(level, pos));
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        level.getServer().execute(() -> {
            if (!IronTideData.isLoaded()) {
                return;
            }
            for (AndesiteCoreBlockEntity core : List.copyOf(AndesiteCoreBlockEntity.loaded())) {
                if (core.getLevel() == level) {
                    core.validateNow();
                }
            }
        });
    }

    private static void revalidateAll() {
        if (!IronTideData.isLoaded()) {
            return;
        }
        AndesiteCoreBlockEntity.pruneLoaded();
        for (AndesiteCoreBlockEntity core : List.copyOf(AndesiteCoreBlockEntity.loaded())) {
            try {
                core.validateNow();
            } catch (RuntimeException exception) {
                ModMindEntry.LOGGER.error("[IronTide] failed to validate controller at {}", core.getBlockPos(), exception);
            }
        }
    }
}