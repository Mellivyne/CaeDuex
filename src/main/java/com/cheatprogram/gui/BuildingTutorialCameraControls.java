package com.cheatprogram.gui;

import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.setting.ModeSetting;

/** Shared camera selector for the tutorial and its native preview. */
final class BuildingTutorialCameraControls {
    static final float HEIGHT = 28;

    private BuildingTutorialCameraControls() {}

    static void draw(GuiBase gui, float x, float y, float width, Runnable changed) {
        ModeSetting camera = ClientSettings.INSTANCE.buildingTutorialCamera;
        float gap = 6;
        float headingWidth = Math.min(112, width * .34f);
        String heading = gui.c.ellipsize(GuiBase.MED, camera.name(), 11.5f, headingWidth - gap);
        gui.c.textC(GuiBase.MED, heading, x + 2, y + HEIGHT / 2, 11.5f, Theme.TEXT_2);
        float choiceWidth = (width - headingWidth - gap) / 2;
        float choiceX = x + headingWidth;
        for (String mode : camera.modes) {
            boolean selected = camera.is(mode);
            String label = gui.c.ellipsize(GuiBase.MED, Lang.mode(mode), 10.5f, choiceWidth - 12);
            gui.button("building.camera." + mode, label, null, choiceX, y, choiceWidth, HEIGHT, selected, () -> {
                if (camera.is(mode)) return;
                camera.set(mode);
                changed.run();
            });
            choiceX += choiceWidth + gap;
        }
    }
}
