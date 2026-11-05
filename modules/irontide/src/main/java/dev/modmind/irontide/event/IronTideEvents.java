package dev.modmind.irontide.event;

import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.data.IronTideData;
import dev.modmind.irontide.multiblock.StructureValidator;
import dev.modmind.irontide.util.BlockNames;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

public final class IronTideEvents {
    private static int tickCounter;

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
        tickCounter = 0;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
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
        for (AndesiteCoreBlockEntity core : List.copyOf(AndesiteCoreBlockEntity.loaded())) {
            try {
                core.validateNow();
            } catch (RuntimeException exception) {
                ModMindEntry.LOGGER.error("[IronTide] failed to validate controller at {}", core.getBlockPos(), exception);
            }
        }
    }
}