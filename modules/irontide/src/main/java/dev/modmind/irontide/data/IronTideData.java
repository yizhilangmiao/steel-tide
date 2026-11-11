package dev.modmind.irontide.data;

import dev.modmind.irontide.config.CoreConfig;
import dev.modmind.irontide.config.RaidConfig;
import dev.modmind.irontide.multiblock.MultiblockRegistry;

import java.nio.file.Path;

public final class IronTideData {
    private static CoreConfig config;
    private static RaidConfig raidConfig;
    private static MultiblockRegistry definitions;

    private IronTideData() {
    }

    public static void load(Path configRoot) {
        Path irontideDirectory = configRoot.resolve("irontide");
        CoreConfig loadedConfig = CoreConfig.load(irontideDirectory.resolve("core.json"));
        RaidConfig loadedRaidConfig = RaidConfig.load(irontideDirectory.resolve("raids.json"));
        MultiblockRegistry loadedDefinitions = MultiblockRegistry.load(irontideDirectory);
        config = loadedConfig;
        raidConfig = loadedRaidConfig;
        definitions = loadedDefinitions;
    }

    public static boolean isLoaded() {
        return config != null && raidConfig != null && definitions != null;
    }

    public static CoreConfig config() {
        if (config == null) {
            throw new IllegalStateException("IronTide config has not been loaded");
        }
        return config;
    }

    public static RaidConfig raidConfig() {
        if (raidConfig == null) {
            throw new IllegalStateException("IronTide raid config has not been loaded");
        }
        return raidConfig;
    }

    public static MultiblockRegistry definitions() {
        if (definitions == null) {
            throw new IllegalStateException("IronTide multiblock definitions have not been loaded");
        }
        return definitions;
    }
}
