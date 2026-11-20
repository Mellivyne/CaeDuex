package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/** Overlay opened while the native preview world continues ticking and rendering. */
public final class BuildingNativePreviewControls extends GuiBase {
    public BuildingNativePreviewControls() {
        super(Lang.t(BuildingWorldPreview.tutorial() ? "ui.building.tutorial" : "ui.building.tour"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        beginFrame();
        R2D.begin(graphics);
        try {
            BuildingNativePreviewPanel.draw(this);
        } finally {
            R2D.end();
            endFrame();
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return dispatchClick(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return dispatchScroll(x, y, vertical);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key != GLFW.GLFW_KEY_ESCAPE && key != GLFW.GLFW_KEY_F8) return false;
        onClose();
        return true;
    }

    @Override
    public void onClose() {
        BuildingWorldPreview.resume();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
