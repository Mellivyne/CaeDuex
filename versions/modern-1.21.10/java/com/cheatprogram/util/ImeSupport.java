package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.lwjgl.glfw.GLFWNativeWin32;
import com.sun.jna.Platform;

/** Enable composition-key isolation only while a vanilla or custom text field is focused. */
public final class ImeSupport {
    private ImeSupport(){}
    private static Object owner;
    private static long customAt;
    public static void custom(Object screen){owner=screen;customAt=System.nanoTime();}
    private static EditBox focused(GuiEventListener listener,int depth){
        if(depth>8)return null;
        if(listener instanceof EditBox edit&&edit.isFocused()&&edit.isVisible())return edit;
        if(listener instanceof ContainerEventHandler parent)for(var child:parent.children()){
            EditBox edit=focused(child,depth+1);if(edit!=null)return edit;
        }
        return null;
    }
    public static void tick(){
        if(!Platform.isWindows())return;
        Minecraft mc=Minecraft.getInstance();var screen=mc.screen;var window=mc.getWindow();
        if(window==null)return;
        long hwnd=GLFWNativeWin32.glfwGetWin32Window(window.handle());
        boolean editing=screen!=null&&(focused(screen,0)!=null
            ||owner==screen&&System.nanoTime()-customAt<250_000_000L);
        WindowsIme.update(hwnd,editing);
    }
}
