package com.cheatprogram.util;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * One startup check of the installed attribution components. This detects a
 * modified/incomplete release, not an unmodifiable program or a license rule.
 * It performs no network requests and never terminates an already loaded world.
 */
public final class RepositoryIntegrity {
    public static final String MANIFEST = "META-INF/cheatprogram/repository.sha256";
    private static final int MAX_MANIFEST_BYTES = 4096;
    private static final List<String> ENTRIES = List.of(
            "com/cheatprogram/CheatProgram.class",
            "com/cheatprogram/util/RepositoryIntegrity.class",
            "com/cheatprogram/util/ProjectRepository.class",
            "com/cheatprogram/gui/RepositoryBadge.class",
            "com/cheatprogram/gui/ClickGuiScreen.class");

    @FunctionalInterface
    public interface ResourceReader {
        InputStream open(String path) throws IOException;
    }

    private RepositoryIntegrity() { }

    public static List<String> protectedEntries() { return ENTRIES; }

    /** Call before loading settings, installing shutdown hooks or entering a world. */
    public static void verifyInstalled() {
        FabricLoader loader = FabricLoader.getInstance();
        // Loom development runs use class directories and in-memory remapping.
        // Distributable JARs are checked and sealed after their final ABI remap.
        if (loader.isDevelopmentEnvironment()) return;
        var container = loader.getModContainer("cheatprogram").orElseThrow(
                () -> failure("The installed mod container is missing.", null));
        verify(path -> {
            var file = container.findPath(path);
            return file.isPresent() ? Files.newInputStream(file.get()) : null;
        });
    }

    public static void verify(ResourceReader reader) {
        try {
            Map<String, String> expected = new HashMap<>();
            try (InputStream input = reader.open(MANIFEST)) {
                if (input == null) throw new IOException("Integrity manifest is missing.");
                byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Integrity manifest is oversized.");
                String text = new String(bytes, StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    if (line.isEmpty()) continue;
                    if (line.length() < 67 || !line.substring(0, 64).matches("[0-9a-f]{64}")
                            || !line.substring(64, 66).equals("  "))
                        throw new IOException("Integrity manifest has an invalid entry.");
                    String name = line.substring(66);
                    if (!ENTRIES.contains(name) || expected.putIfAbsent(name, line.substring(0, 64)) != null)
                        throw new IOException("Integrity manifest has an unexpected or repeated entry.");
                }
            }
            if (expected.size() != ENTRIES.size()) throw new IOException("Integrity manifest is incomplete.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (String name : ENTRIES) {
                digest.reset();
                try (InputStream input = reader.open(name)) {
                    if (input == null) throw new IOException("A project-link component is missing: " + name);
                    for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(expected.get(name)))
                    throw new IOException("A project-link component was changed: " + name);
            }
        } catch (IOException | NoSuchAlgorithmException error) {
            throw failure(error.getMessage(), error);
        }
    }

    private static IllegalStateException failure(String detail, Throwable cause) {
        return new IllegalStateException("Cheat Program could not verify its control-panel project link. "
                + "Startup stopped before loading a world. Restore the complete matching JAR from "
                + "https://github.com/Mellivyne/cheat-program/releases. " + detail, cause);
    }
}
