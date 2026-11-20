package com.cheatprogram.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/** A screen position in blocks relative to the blueprint's minimum corner. */
public record BuildingProjection(double x, double y, double z, double width, double yaw) {
    private static final Set<String> KEYS = Set.of("x", "y", "z", "width", "yaw");

    public static BuildingProjection read(JsonObject data, int width, int height, int depth) {
        if (!data.keySet().containsAll(KEYS)) throw new IllegalArgumentException("Invalid projection fields");
        return new BuildingProjection(read(data, "x", 0, width), read(data, "y", 0, height),
                read(data, "z", 0, depth), read(data, "width", .25, 200), read(data, "yaw", -180, 180));
    }

    private static double read(JsonObject data, String key, double min, double max) {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid projection number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number < min || number > max) {
            throw new IllegalArgumentException("Projection number out of bounds");
        }
        return number;
    }

    public JsonObject save() {
        JsonObject data = new JsonObject();
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        data.addProperty("width", width);
        data.addProperty("yaw", yaw);
        return data;
    }
}
