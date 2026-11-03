package com.cheatprogram.platform;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Loader services for Forge; generated shared sources do not ship Fabric APIs. */
public final class ModPlatform {
    private static final ModPlatform INSTANCE=new ModPlatform();
    private static final Map<Path,Path> ROOTS=new HashMap<>();
    public static ModPlatform getInstance(){return INSTANCE;}
    public Path getConfigDir(){return FMLPaths.CONFIGDIR.get();}
    public Path getGameDir(){return FMLPaths.GAMEDIR.get();}
    public boolean isDevelopmentEnvironment(){return !FMLEnvironment.production;}
    public boolean isModLoaded(String id){return ModList.get().isLoaded(id);}
    public Optional<Container> getModContainer(String id){
        return ModList.get().getModContainerById(id).map(c->new Container(
            new Metadata(c.getModInfo().getModId(),c.getModInfo().getDisplayName(),new Version(c.getModInfo().getVersion().toString())),
            c.getModInfo().getOwningFile().getFile().getFilePath()));
    }
    public List<Container> getAllMods(){return ModList.get().getMods().stream().map(m->getModContainer(m.getModId()).orElseThrow()).toList();}
    public record Version(String value){public String getFriendlyString(){return value;}}
    public record Metadata(String id,String name,Version version){
        public String getId(){return id;}public String getName(){return name;}public Version getVersion(){return version;}
    }
    public record Container(Metadata metadata,Path archive){
        public Metadata getMetadata(){return metadata;}
        public List<Path> getRootPaths(){return List.of(root(archive));}
        public Optional<Path> findPath(String name){Path base=root(archive),path=base.resolve(name).normalize();return path.startsWith(base)&&Files.exists(path)?Optional.of(path):Optional.empty();}
    }
    private static synchronized Path root(Path archive){
        return ROOTS.computeIfAbsent(archive,path->{
            if(Files.isDirectory(path))return path;
            try{return FileSystems.newFileSystem(path,Map.of()).getPath("/");}
            catch(IOException e){throw new IllegalStateException("Cannot open installed mod resources",e);}
        });
    }
}
