package com.cheatprogram.compat;

import java.net.URI;
import java.nio.file.Path;
import org.lwjgl.sdl.SDLMisc;
import org.lwjgl.sdl.SDLError;

/** SDL opens local folders and platform URLs without a removed Util.OS method. */
public final class DesktopLinks {
    private DesktopLinks() { }
    public static void openPath(Path path) { openUri(path.toAbsolutePath().toUri()); }
    public static void openUri(URI uri) { openUri(uri.toASCIIString()); }
    public static void openUri(String uri) {
        if(!SDLMisc.SDL_OpenURL(uri))throw new IllegalStateException(SDLError.SDL_GetError());
    }
}
