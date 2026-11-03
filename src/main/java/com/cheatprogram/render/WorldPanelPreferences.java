package com.cheatprogram.render;

import com.cheatprogram.core.Config;
import com.google.gson.Gson;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;

/** Local interaction consent and binding, independent from a shared configuration. */
public final class WorldPanelPreferences {
    public boolean acknowledged;
    public int interactionKey=GLFW.GLFW_KEY_RIGHT_CONTROL;
    private static final Gson JSON=new Gson();
    private static final Path FILE=Config.directory().resolve("world-panel.json");
    public static WorldPanelPreferences load(){
        WorldPanelPreferences p=new WorldPanelPreferences();
        try{if(Files.isRegularFile(FILE)&&Files.size(FILE)<4096){var saved=JSON.fromJson(Files.readString(FILE),WorldPanelPreferences.class);if(saved!=null)p=saved;}}catch(Exception ignored){ }
        p.validateKey(-1);
        return p;
    }
    /** Validate against live settings only after they have been created and loaded. */
    public void validateKey(int guiKey){
        if(interactionKey<32||interactionKey>348||interactionKey==guiKey)
            interactionKey=guiKey==GLFW.GLFW_KEY_RIGHT_CONTROL?GLFW.GLFW_KEY_RIGHT_ALT:GLFW.GLFW_KEY_RIGHT_CONTROL;
    }
    public void save(){synchronized(Config.persistenceLock()){
        if(!Config.persistenceAllowed())return;
        try{Files.createDirectories(FILE.getParent());Path tmp=FILE.resolveSibling("world-panel.json.tmp");Files.writeString(tmp,JSON.toJson(this));
            try{Files.move(tmp,FILE,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(tmp,FILE,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception ignored){ }
    }}
}
