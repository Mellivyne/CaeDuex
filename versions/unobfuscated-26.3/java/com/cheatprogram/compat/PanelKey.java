package com.cheatprogram.compat;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.compat.sdl.GLFW;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.render.WorldControlPanel;
import net.minecraft.client.Minecraft;

/** Give the panel binding priority and recover missed SDL events. */
public final class PanelKey {
    private static int binding = GLFW.GLFW_KEY_UNKNOWN;
    private static boolean held;
    private static boolean focused;

    private PanelKey() { }

    public static boolean event(int key, int action) {
        int configured = ClientSettings.INSTANCE.guiKey;
        if (key != configured || GLFW.scan(key) < 0) return false;
        boolean duplicate = binding == key && held;
        binding = configured;
        held = action != GLFW.GLFW_RELEASE;
        Minecraft mc = Minecraft.getInstance();
        if (action != GLFW.GLFW_PRESS || !canOpen(mc)) return false;
        if (!duplicate) open(mc, key);
        return true;
    }

    public static void poll() {
        if (!CheatProgram.initialized()) return;
        Minecraft mc = Minecraft.getInstance();
        int key = ClientSettings.INSTANCE.guiKey;
        if (GLFW.scan(key) < 0) {
            binding = key;
            held = false;
            return;
        }
        long window = mc.getWindow().handle();
        var state = PanelKeyState.read(window, key, mc.isWindowActive());
        boolean pressed = focused && state.focused() && binding == key && state.down() && !held;
        binding = key;
        held = state.down();
        focused = state.focused();
        if (pressed && canOpen(mc)) open(mc, key);
    }

    private static boolean canOpen(Minecraft mc) {
        return !com.cheatprogram.core.Config.restartRequired() && mc.gui.screen() == null
                && mc.gui.overlay() == null && mc.player != null;
    }

    private static void open(Minecraft mc, int key) {
        if (!WorldControlPanel.key(key, GLFW.GLFW_PRESS)) {
            mc.gui.setScreen(new com.cheatprogram.gui.ClickGuiScreen());
        }
    }
}
