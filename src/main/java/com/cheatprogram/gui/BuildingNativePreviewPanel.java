package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;

import java.util.Locale;

/** Small in-world controls panel for a native building visit or demonstration. */
final class BuildingNativePreviewPanel {
    private static final float WIDTH = 480;
    private static final float MARGIN = 18;

    private BuildingNativePreviewPanel() {}

    static void draw(GuiBase gui) {
        boolean tutorial = BuildingWorldPreview.tutorial();
        boolean atMark = BuildingWorldPreview.inPlace();
        float height = tutorial ? 304 : 184;
        float preferredScale = gui.fixedPanelScale(WIDTH, height) * 1.12f;
        float fitScale = Math.min(R2D.width() / (WIDTH + 32), R2D.height() / (height + 40));
        float scale = Math.max(.2f, Math.min(preferredScale, fitScale));
        gui.c.set(scale, Math.round((R2D.width() - WIDTH * scale) / 2),
                Math.round(Math.max(4, R2D.height() - (height + 16) * scale)));
        gui.updateMouse();

        Backdrop.capture(Theme.frost());
        R2D.applyState();
        gui.c.shadow(0, 5, WIDTH, height, 19, 21, Anim.black(.24f));
        gui.c.lens(0, 0, WIDTH, height, 19, Theme.lift(.10f), Theme.sheetGlass(scale));

        gui.c.textC(GuiBase.BOLD, Lang.t(tutorial ? "ui.building.tutorial" : "ui.building.tour"),
                MARGIN, 26, 18, Theme.TEXT);
        String status = BuildingWorldPreview.status();
        if (status != null && !status.isBlank()) {
            gui.c.textC(GuiBase.REG, gui.c.ellipsize(GuiBase.REG, status, 14, WIDTH - 2 * MARGIN),
                    MARGIN, 51, 14, Theme.TEXT_2);
        }

        if (atMark) {
            gui.c.textC(GuiBase.MED, Lang.t("ui.building.tutorialSite"), MARGIN, 84, 13, Theme.TEXT);
            gui.c.textC(GuiBase.REG, Lang.t("ui.building.tutorialSitePreview"), MARGIN, 108, 11.5f, Theme.TEXT_2);
        } else {
            BuildingPreviewControls.draw(gui, MARGIN, 73, WIDTH - 2 * MARGIN,
                    BuildingWorldPreview::restartTerrain, BuildingWorldPreview::useTime, BuildingWorldPreview::useWeather);
        }
        if (tutorial) {
            boolean paused = BuildingWorldPreview.paused();
            gui.button("nativeBuilding.pause",
                    Lang.t(paused ? "ui.building.tutorialResume" : "ui.building.tutorialPause"),
                    null, MARGIN, 143, 112, 28, paused, BuildingWorldPreview::togglePause);
            drawSpeed(gui, 151, 141, WIDTH - MARGIN - 151);
            BuildingTutorial.drawProgressToggle(gui, "nativeBuilding.tutorialHud", MARGIN, 184, WIDTH - 2 * MARGIN);
            BuildingTutorialCameraControls.draw(gui, MARGIN, 223, WIDTH - 2 * MARGIN, BuildingWorldPreview::cameraModeChanged);
        }
        float footer = height - 42;
        gui.button("nativeBuilding.exit", Lang.t("ui.back"), Icon.BACK,
                MARGIN, footer, 114, 28, false, BuildingWorldPreview::exit);
        gui.button("nativeBuilding.resume", Lang.t(tutorial ? "ui.building.tutorialResume"
                        : "ui.building.native.continueTour"), Icon.NAVIGATION,
                WIDTH - MARGIN - 128, footer, 128, 28, true, BuildingWorldPreview::resume);
    }

    private static void drawSpeed(GuiBase gui, float x, float y, float width) {
        float speed = BuildingWorldPreview.speed();
        float fraction = BuildingWorldPreview.speedFraction(speed);
        gui.c.textC(GuiBase.REG, Lang.t("ui.building.tutorialSpeed"), x, y + 6, 11.5f, Theme.TEXT_2);
        gui.c.textRight(GuiBase.MED, String.format(Locale.ROOT, "%.2f\u00d7", speed),
                x + width, y + 6, 11.5f, Theme.TEXT);
        float trackY = y + 24;
        gui.c.rrect(x, trackY - 1.5f, width, 3, 1.5f, 0x42ffffff);
        gui.c.rrect(x, trackY - 1.5f, width * fraction, 3, 1.5f, Theme.TEXT_2);
        gui.c.circle(x + width * fraction, trackY, 4.5f, Theme.TEXT);
        gui.hit(x - 4, y + 12, width + 8, 24).left(() -> {
            gui.drag = (px, py) -> BuildingWorldPreview.setSpeed(
                    BuildingWorldPreview.speedAt((px - x) / width));
            gui.drag.drag(gui.mx, gui.my);
        });
    }
}
