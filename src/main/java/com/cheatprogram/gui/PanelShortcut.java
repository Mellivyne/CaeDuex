package com.cheatprogram.gui;

import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Config;
import com.cheatprogram.parkour.ParkourClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.glfw.GLFW;

/** The control panel shortcut also works while a CaeDuex page owns keyboard input. */
public final class PanelShortcut {
    private static BuildingBlueprintScreen suspended;
    private static ClientLevel suspendedLevel;

    private PanelShortcut() { }

    public static boolean handle(int key, int action) {
        if (key < 0 || key != ClientSettings.INSTANCE.guiKey) return false;
        var screen = ParkourClient.screen();
        if (action == GLFW.GLFW_REPEAT) return screen instanceof GuiBase || suspended != null && screen == null;
        if (action != GLFW.GLFW_PRESS) return false;
        clearResumeOutsideWorld();
        if (screen == null && suspended != null && !ParkourClient.screenOpen()) {
            BuildingBlueprintScreen previous = suspended;
            clear();
            ParkourClient.open(previous.reopen());
            return true;
        }
        if (MusicPlayerScreen.panelKey(key, action)) return true;
        if (!(screen instanceof GuiBase page)) return false;

        clear();
        if (page instanceof BuildingBlueprintScreen building) {
            page.focused = null;
            page.drag = null;
            Minecraft mc = Minecraft.getInstance();
            suspended = building;
            suspendedLevel = mc.level;
            ParkourClient.open(null);
        } else page.onClose();
        return true;
    }

    public static void clearResumeOutsideWorld() {
        if (suspended == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level != suspendedLevel || Config.restartRequired()) clear();
    }

    private static void clear() {
        suspended = null;
        suspendedLevel = null;
    }
}
