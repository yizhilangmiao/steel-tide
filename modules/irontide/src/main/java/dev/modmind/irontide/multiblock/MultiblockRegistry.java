package dev.modmind.irontide.multiblock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modmind.irontide.ModMindEntry;
import dev.modmind.irontide.config.ConfigResources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class MultiblockRegistry {
    private static final String DEFAULT_CORE_RESOURCE =
            "/data/irontide/config_defaults/multiblocks/core_base_3.json";

    private final Map<String, MultiblockDefinition> definitions;
    private final MultiblockDefinition primary;

    private MultiblockRegistry(Map<String, MultiblockDefinition> definitions, MultiblockDefinition primary) {
        this.definitions = definitions;
        this.primary = primary;
    }

    public MultiblockDefinition get(String id) {
        return definitions.get(id);
    }

    public MultiblockDefinition primary() {
        return primary;
    }

    public Iterable<MultiblockDefinition> all() {
        return definitions.values();
    }

    public static MultiblockRegistry load(Path irontideDirectory) {
        Map<String, MultiblockDefinition> definitions = new LinkedHashMap<>();
        Path directory = irontideDirectory.resolve("multiblocks");
        if (Files.isDirectory(directory)) {
            try (Stream<Path> stream = Files.list(directory)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList()) {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    MultiblockDefinition definition = parse(json, file.toString());
                    register(definitions, definition);
                    ModMindEntry.LOGGER.info("[IronTide] loaded multiblock {} (tier={}, cells={}) from {}",
                            definition.id(), definition.tier(), definition.cells().size(), file);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read IronTide multiblock directory " + directory, exception);
            }
        }

        if (!definitions.containsKey("core_base_3")) {
            String json = ConfigResources.read(DEFAULT_CORE_RESOURCE);
            MultiblockDefinition definition = parse(json, "jar:" + DEFAULT_CORE_RESOURCE);
            register(definitions, definition);
            ModMindEntry.LOGGER.info("[IronTide] loaded multiblock {} (tier={}, cells={}) from {}",
                    definition.id(), definition.tier(), definition.cells().size(), "jar:" + DEFAULT_CORE_RESOURCE);
        }

        if (definitions.isEmpty()) {
            throw new IllegalStateException("IronTide has no multiblock definitions");
        }
        MultiblockDefinition primary = definitions.get("core_base_3");
        if (primary == null) {
            primary = definitions.values().iterator().next();
        }
        return new MultiblockRegistry(Map.copyOf(definitions), primary);
    }

    private static void register(Map<String, MultiblockDefinition> definitions, MultiblockDefinition definition) {
        MultiblockDefinition previous = definitions.putIfAbsent(definition.id(), definition);
        if (previous != null) {
            throw new IllegalStateException("Duplicate IronTide multiblock id '" + definition.id()
                    + "' in " + previous.source() + " and " + definition.source());
        }
    }

    private static MultiblockDefinition parse(String json, String source) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return MultiblockDefinition.parse(root, source);
    }
}