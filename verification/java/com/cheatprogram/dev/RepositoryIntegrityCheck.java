package com.cheatprogram.dev;

import com.cheatprogram.util.RepositoryIntegrity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** In-memory release-integrity checks; never starts Minecraft or opens a world. */
public final class RepositoryIntegrityCheck {
    private static final String PREFIX = "com/cheatprogram/";
    private static int rejected;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> resources) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        resources.forEach((name, bytes) -> result.put(name, bytes.clone()));
        return result;
    }

    private static Map<String, byte[]> fixture() throws Exception {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        StringBuilder manifest = new StringBuilder();
        int number = 0;
        for (String name : RepositoryIntegrity.protectedEntries()) {
            byte[] bytes = utf8("independent release fixture " + number++ + " " + name);
            resources.put(name, bytes);
            manifest.append(digest(bytes)).append("  ").append(name).append('\n');
        }
        resources.put(RepositoryIntegrity.MANIFEST, utf8(manifest.toString()));
        return resources;
    }

    private static RepositoryIntegrity.ResourceReader reader(Map<String, byte[]> resources) {
        return path -> {
            byte[] bytes = resources.get(path);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        };
    }

    private static void rejects(String label, RepositoryIntegrity.ResourceReader reader) {
        try {
            RepositoryIntegrity.verify(reader);
            throw new AssertionError("Accepted invalid installation: " + label);
        } catch (IllegalStateException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isBlank(),
                    "Failure should explain why loading stopped: " + label);
            rejected++;
        }
    }

    private static void rejectsManifest(String label, Map<String, byte[]> original, String manifest) {
        Map<String, byte[]> resources = copy(original);
        resources.put(RepositoryIntegrity.MANIFEST, utf8(manifest));
        rejects(label, reader(resources));
    }

    private static void verifyJar(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            RepositoryIntegrity.ResourceReader raw = name -> {
                var entry = zip.getEntry(name);
                return entry == null ? null : zip.getInputStream(entry);
            };
            RepositoryIntegrity.verify(raw);
            for (String protectedPath : List.of(PREFIX + "util/ProjectRepository.class",
                    PREFIX + "gui/RepositoryBadge.class", PREFIX + "gui/ClickGuiScreen.class")) {
                rejects(path.getFileName() + " missing " + protectedPath,
                        name -> name.equals(protectedPath) ? null : raw.open(name));
                rejects(path.getFileName() + " changed " + protectedPath, name -> {
                    if (!name.equals(protectedPath)) return raw.open(name);
                    byte[] bytes;
                    try (InputStream input = raw.open(name)) {
                        if (input == null) throw new AssertionError("Protected JAR entry must exist");
                        bytes = input.readAllBytes();
                    }
                    check(bytes.length > 0, "Protected class must not be empty");
                    bytes[0] ^= 1;
                    return new ByteArrayInputStream(bytes);
                });
            }
            System.out.println("REPOSITORY_JAR_INTEGRITY_PASS " + path.getFileName()
                    + " valid manifest and 6 rejected in-memory tampering cases");
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> entries = RepositoryIntegrity.protectedEntries();
        check(entries.size() == 5 && Set.copyOf(entries).equals(Set.of(
                PREFIX + "CheatProgram.class", PREFIX + "util/RepositoryIntegrity.class",
                PREFIX + "util/ProjectRepository.class", PREFIX + "gui/RepositoryBadge.class",
                PREFIX + "gui/ClickGuiScreen.class")), "Protect the entry point and attribution path");
        Map<String, byte[]> original = fixture();
        Map<String, byte[]> snapshot = copy(original);
        int[] opened = {0}, closed = {0};
        RepositoryIntegrity.ResourceReader tracking = path -> {
            byte[] bytes = original.get(path);
            if (bytes == null) return null;
            opened[0]++;
            return new ByteArrayInputStream(bytes) {
                @Override public void close() throws IOException {
                    closed[0]++;
                    super.close();
                }
            };
        };
        for (int attempt = 0; attempt < 100; attempt++) RepositoryIntegrity.verify(tracking);
        check(opened[0] == closed[0], "Successful checks must close every resource");
        check(original.keySet().equals(snapshot.keySet()), "Verification must not add or remove resources");
        original.forEach((name, bytes) -> check(Arrays.equals(bytes, snapshot.get(name)),
                "Verification must not modify installation bytes: " + name));

        for (String path : entries) {
            Map<String, byte[]> missing = copy(original);
            missing.remove(path);
            rejects("missing " + path, reader(missing));
            Map<String, byte[]> changed = copy(original);
            changed.get(path)[0] ^= 1;
            rejects("changed " + path, reader(changed));
            rejects("read failure " + path, name -> {
                if (name.equals(path)) throw new IOException("fixture read failure");
                return reader(original).open(name);
            });
        }
        Map<String, byte[]> missingManifest = copy(original);
        missingManifest.remove(RepositoryIntegrity.MANIFEST);
        rejects("missing manifest", reader(missingManifest));
        rejects("manifest read failure", path -> { throw new IOException("fixture unavailable"); });
        String manifest = new String(original.get(RepositoryIntegrity.MANIFEST), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(manifest.lines().toList());
        rejectsManifest("empty manifest", original, "");
        rejectsManifest("duplicate record", original, manifest + lines.getFirst() + "\n");
        rejectsManifest("missing record", original, String.join("\n", lines.subList(1, lines.size())) + "\n");
        rejectsManifest("unknown additional resource", original, manifest + "0".repeat(64) + "  unrelated.class\n");
        rejectsManifest("traversal resource", original, manifest.replace(entries.getFirst(), "../" + entries.getFirst()));
        rejectsManifest("malformed hash", original, "z" + manifest.substring(1));
        rejectsManifest("short hash", original, manifest.substring(1));
        rejectsManifest("wrong digest", original, "0".repeat(64) + manifest.substring(64));
        rejectsManifest("oversized manifest", original, " ".repeat(1024 * 1024));
        RepositoryIntegrity.verify(reader(original));
        for (String argument : args) verifyJar(Path.of(argument));
        System.out.println("REPOSITORY_INTEGRITY_PASS 100 valid checks, " + rejected
                + " invalid installations rejected; streams closed and fixture unchanged");
    }
}
