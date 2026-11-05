package dev.modmind.irontide.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class JsonSupport {
    private JsonSupport() {
    }

    public static IllegalArgumentException invalid(String field, String detail) {
        return new IllegalArgumentException("Invalid IronTide config field '" + field + "': " + detail);
    }

    public static JsonObject object(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonObject()) {
            throw invalid(path, "missing or not an object");
        }
        return element.getAsJsonObject();
    }

    public static JsonArray array(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonArray()) {
            throw invalid(path, "missing or not an array");
        }
        return element.getAsJsonArray();
    }

    public static String string(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid(path, "missing or not a string");
        }
        return element.getAsString();
    }

    public static int integer(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(path, "missing or not a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)) {
            throw invalid(path, "must be an integer, was " + value);
        }
        return (int) value;
    }

    public static double number(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(path, "missing or not a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw invalid(path, "must be finite, was " + value);
        }
        return value;
    }

    public static boolean bool(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw invalid(path, "missing or not a boolean");
        }
        return element.getAsBoolean();
    }
}