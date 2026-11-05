package dev.modmind.irontide.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ConfigResources {
    private ConfigResources() {
    }

    public static String read(String resourcePath) {
        try (InputStream stream = ConfigResources.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled IronTide resource " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read bundled IronTide resource " + resourcePath, exception);
        }
    }
}