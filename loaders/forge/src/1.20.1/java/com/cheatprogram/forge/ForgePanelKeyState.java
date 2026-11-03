package com.cheatprogram.forge;

import com.cheatprogram.CheatProgram;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/** Physical modifier state is checked only for the configured panel binding. */
public final class ForgePanelKeyState {
    public record State(boolean focused,boolean down,boolean physical) { }
    private static boolean unavailable;
    private ForgePanelKeyState() { }
    public interface User32 extends StdCallLibrary {
        short GetAsyncKeyState(int key);
        Pointer GetForegroundWindow();
    }
    private static final class Windows {static final User32 API=Native.load("user32",User32.class);}
    public static int virtualKey(int key){return switch(key){case 340->0xA0;case 344->0xA1;case 341->0xA2;case 345->0xA3;case 342->0xA4;case 346->0xA5;default->0;};}
    public static State read(long window,int key,boolean fallbackFocused){
        if(window==0)return new State(false,false,false);
        int virtual=virtualKey(key);
        if(virtual!=0&&Platform.isWindows()&&!unavailable)try{
            long hwnd=GLFWNativeWin32.glfwGetWin32Window(window);
            boolean foreground=hwnd!=0&&Pointer.nativeValue(Windows.API.GetForegroundWindow())==hwnd;
            boolean down=foreground&&(Windows.API.GetAsyncKeyState(virtual)&0x8000)!=0;
            return new State(foreground,down,true);
        }catch(Throwable failure){unavailable=true;CheatProgram.LOGGER.warn("Physical panel-key state unavailable; using GLFW",failure);}
        return new State(fallbackFocused,GLFW.glfwGetKey(window,key)==GLFW.GLFW_PRESS,false);
    }
}
