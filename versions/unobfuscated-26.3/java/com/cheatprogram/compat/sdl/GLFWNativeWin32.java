package com.cheatprogram.compat.sdl;

import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/** Resolve the HWND owned by Minecraft's SDL window, never a GLFW handle. */
public final class GLFWNativeWin32 {
    private GLFWNativeWin32() { }
    public static long glfwGetWin32Window(long window){
        return SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(window),SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER,0);
    }
}
