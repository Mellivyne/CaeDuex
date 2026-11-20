package com.cheatprogram.core;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Validated offline codes for individual particle and building presets. */
public final class ScopedPresetCodec {
    public static final int MAX_CODE = 12 * 1024 * 1024;
    public static final int MAX_PACKED = 8 * 1024 * 1024;
    public static final int MAX_PLAIN = 16 * 1024 * 1024;

    public enum Scope {
        PARTICLES("particleeffects"), BUILDING("buildingblueprints");

        public final String module;

        Scope(String module) {
            this.module = module;
        }
    }

    public record Decoded(Scope scope, JsonObject data) {}

    private ScopedPresetCodec() {}

    public static String encode(Scope scope, JsonObject data) throws IOException {
        validate(scope, data);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("scope", scope.module);
        root.add("data", data.deepCopy());
        root.add("assets", new JsonObject());

        byte[] plain = root.toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_PLAIN) throw bad("tooLarge");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(buffer)) {
            zip.write(plain);
        }
        byte[] packed = buffer.toByteArray();
        if (packed.length > MAX_PACKED) throw bad("tooLarge");
        String code = "CPS1." + Base64.getUrlEncoder().withoutPadding().encodeToString(packed) + "." + digest(packed);
        if (code.length() > MAX_CODE) throw bad("tooLarge");
        return code;
    }

    public static Decoded decode(Scope expected, String source) throws IOException {
        if (source == null || source.length() > MAX_CODE) throw bad("tooLarge");
        String[] parts = source.strip().split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("CPS1")
                || !parts[1].matches("[A-Za-z0-9_-]+") || !parts[2].matches("[a-f0-9]{64}")) {
            throw bad("invalid");
        }
        try {
            byte[] packed = Base64.getUrlDecoder().decode(parts[1]);
            if (packed.length > MAX_PACKED) throw bad("tooLarge");
            if (!MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.US_ASCII),
                    digest(packed).getBytes(StandardCharsets.US_ASCII))) throw bad("checksum");

            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
                byte[] chunk = new byte[8192];
                for (int count; (count = zip.read(chunk)) != -1; ) {
                    if (plain.size() + count > MAX_PLAIN) throw bad("tooLarge");
                    plain.write(chunk, 0, count);
                }
            }
            JsonObject root = parse(plain.toByteArray());
            only(root, Set.of("version", "scope", "data", "assets"));
            if (!root.has("version") || root.get("version").getAsDouble() != 1) throw bad("invalid");
            if (!root.has("scope") || !expected.module.equals(root.get("scope").getAsString())) throw bad("wrongScope");
            JsonObject assets = root.getAsJsonObject("assets");
            if (assets == null) throw bad("invalid");
            if (assets.size() != 0) throw bad("wrongScope");
            JsonObject data = root.getAsJsonObject("data");
            validate(expected, data);
            return new Decoded(expected, data);
        } catch (IOException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("ui.scopedShare.")) throw failure;
            throw bad("invalid");
        } catch (RuntimeException failure) {
            throw bad("invalid");
        }
    }

    private static void validate(Scope scope, JsonObject data) throws IOException {
        if (scope == null) throw bad("invalid");
        only(data, switch (scope) {
            case PARTICLES -> Set.of("settings", "project");
            case BUILDING -> Set.of("settings", "building");
        });
        if (!data.has("settings") || !data.get("settings").isJsonObject()
                || data.getAsJsonObject("settings").size() > 128) throw bad("invalid");
    }

    private static JsonObject parse(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = readJson(reader, 0, new int[1]);
            if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) throw bad("invalid");
            return value.getAsJsonObject();
        }
    }

    private static JsonElement readJson(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 40 || ++nodes[0] > 200_000) throw bad("tooLarge");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > 256 || object.has(name)) throw bad("invalid");
                    object.add(name, readJson(reader, depth + 1, nodes));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1, nodes));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                double value = Double.parseDouble(reader.nextString());
                if (!Double.isFinite(value)) throw bad("invalid");
                yield new JsonPrimitive(value);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw bad("invalid");
        };
    }

    public static void only(JsonObject object, Set<String> allowed) throws IOException {
        if (object == null) throw bad("invalid");
        for (String key : object.keySet()) if (!allowed.contains(key)) throw bad("wrongScope");
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static IOException bad(String reason) {
        return new IOException("ui.scopedShare." + reason);
    }
}
