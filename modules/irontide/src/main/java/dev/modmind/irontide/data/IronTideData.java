package dev.modmind.irontide.data;

import dev.modmind.irontide.config.CoreConfig;
import dev.modmind.irontide.multiblock.MultiblockRegistry;

import java.nio.file.Path;

public final class IronTideData {
    private static CoreConfig config;
    private static MultiblockRegistry definitions;

    private IronTideData() {
    }

    public static void load(Path configRoot) {
        Path irontideDirectory = configRoot.resolve("irontide");
        CoreConfig loadedConfig = CoreConfig.load(irontideDirectory.resolve("core.json"));
        MultiblockRegistry loadedDefinitions = MultiblockRegistry.load(irontideDirectory);
        config = loadedConfig;
        definitions = loadedDefinitions;
    }

    public static boolean isLoaded() {
        return config != null && definitions != null;
    }

    public static CoreConfig config() {
        if (config == null) {
            throw new IllegalStateException("IronTide config has not been loaded");
        }
        return config;
    }

    public static MultiblockRegistry definitions() {
        if (definitions == null) {
            throw new IllegalStateException("IronTide multiblock definitions have not been loaded");
        }
        return definitions;
    }
}