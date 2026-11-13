package com.cheatprogram.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Loader services used by the shared client code on NeoForge. */
public final class ModPlatform {
    private static final ModPlatform INSTANCE = new ModPlatform();
    private static final Map<Path, Path> ARCHIVE_ROOTS = new HashMap<>();

    private ModPlatform() {}

    public static ModPlatform getInstance() { return INSTANCE; }
    public Path getConfigDir() { return FMLPaths.CONFIGDIR.get(); }
    public Path getGameDir() { return FMLPaths.GAMEDIR.get(); }
    public boolean isDevelopmentEnvironment() { return !FMLEnvironment.isProduction(); }
    public boolean isModLoaded(String id) { return ModList.get().isLoaded(id); }

    public Optional<Container> getModContainer(String id) {
        return ModList.get().getMods().stream().filter(mod -> mod.getModId().equals(id)).findFirst().map(Container::new);
    }

    public List<Container> getAllMods() {
        return ModList.get().getMods().stream().map(Container::new).toList();
    }

    public record Container(IModInfo info) {
        public Metadata getMetadata() { return new Metadata(info); }

        public List<Path> getRootPaths() {
            return List.copyOf(info.getOwningFile().getFile().getContents().getContentRoots());
        }

        public Optional<Path> findPath(String name) {
            // FML filters some META-INF entries from the runtime resource view.
            if ("cheatprogram".equals(info.getModId())) {
                Path root = archiveRoot(info.getOwningFile().getFile().getFilePath());
                Path path = root.resolve(name).normalize();
                if (path.startsWith(root) && Files.isRegularFile(path)) return Optional.of(path);
            }
            return getRootPaths().stream().map(root -> root.resolve(name).normalize())
                    .filter(Files::exists).findFirst();
        }
    }

    private static synchronized Path archiveRoot(Path path) {
        if (Files.isDirectory(path)) return path;
        return ARCHIVE_ROOTS.computeIfAbsent(path, archive -> {
            try {
                return FileSystems.newFileSystem(archive).getPath("/");
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read the installed NeoForge mod archive", failure);
            }
        });
    }

    public record Metadata(IModInfo info) {
        public String getId() { return info.getModId(); }
        public String getName() { return info.getDisplayName(); }
        public Version getVersion() { return new Version(info.getVersion().toString()); }
    }

    public record Version(String value) {
        public String getFriendlyString() { return value; }
    }
}
