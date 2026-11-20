package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.render.BuildingPreviewEnvironment;
import com.cheatprogram.render.BuildingPreviewEnvironment.Weather;
import com.cheatprogram.render.Icon;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class BuildingPreviewControls {
    static final float HEIGHT = 54;

    private BuildingPreviewControls() {}

    static void draw(GuiBase gui, float x, float y, float width) {
        draw(gui, x, y, width, BuildingPreviewEnvironment::toggleTerrain, BuildingPreviewEnvironment::setTime,
                BuildingPreviewEnvironment::setWeather);
    }

    static void draw(GuiBase gui, float x, float y, float width, Runnable toggleTerrain,
                     IntConsumer setTime, Consumer<Weather> setWeather) {
        float terrainWidth = width * .26f;
        float presetWidth = Math.min(58, width * .145f);
        boolean flat = BuildingPreviewEnvironment.superflat();
        gui.button("building.scene.terrain", Lang.t(flat ? "ui.building.scene.flat" : "ui.building.scene.void"),
                Icon.LAYOUT, x, y, terrainWidth, 24, flat, toggleTerrain);
        float presetX = x + terrainWidth + 5;
        timePreset(gui, "day", BuildingPreviewEnvironment.DAY, presetX, y, presetWidth, setTime);
        presetX += presetWidth + 4;
        timePreset(gui, "sunset", BuildingPreviewEnvironment.SUNSET, presetX, y, presetWidth, setTime);
        presetX += presetWidth + 4;
        timePreset(gui, "night", BuildingPreviewEnvironment.NIGHT, presetX, y, presetWidth, setTime);
        float barX = presetX + presetWidth + 10;
        drawTimeSlider(gui, barX, y, x + width - barX, setTime);

        float weatherY = y + 30;
        float weatherWidth = Math.min(92, (width - terrainWidth - 13) / 3);
        gui.c.textC(GuiBase.REG, Lang.t("ui.building.scene.weather"), x + 4, weatherY + 12, 11.5f, Theme.TEXT_2);
        float weatherX = x + terrainWidth + 5;
        weatherPreset(gui, "clear", Weather.CLEAR, weatherX, weatherY, weatherWidth, setWeather);
        weatherX += weatherWidth + 4;
        weatherPreset(gui, "rain", Weather.RAIN, weatherX, weatherY, weatherWidth, setWeather);
        weatherX += weatherWidth + 4;
        weatherPreset(gui, "thunder", Weather.THUNDER, weatherX, weatherY, weatherWidth, setWeather);
    }

    private static void timePreset(GuiBase gui, String id, int tick, float x, float y, float width, IntConsumer setTime) {
        gui.button("building.scene." + id, Lang.t("ui.building.scene." + id), null, x, y, width, 24,
                BuildingPreviewEnvironment.time() == tick, () -> setTime.accept(tick));
    }

    private static void weatherPreset(GuiBase gui, String id, Weather weather, float x, float y,
                                      float width, Consumer<Weather> setWeather) {
        gui.button("building.scene." + id, Lang.t("ui.building.scene." + id), null, x, y, width, 24,
                BuildingPreviewEnvironment.weather() == weather, () -> setWeather.accept(weather));
    }

    private static void drawTimeSlider(GuiBase gui, float x, float y, float barWidth, IntConsumer setTime) {
        int time = BuildingPreviewEnvironment.time();
        int minutes = (time * 1440 / 24000 + 360) % 1440;
        String clock = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
        gui.c.textRight(GuiBase.MED, clock, x + barWidth, y + 6, 10.5f, Theme.TEXT);
        float trackY = y + 20;
        float fraction = time / 23999f;
        gui.c.rrect(x, trackY - 1.5f, barWidth, 3, 1.5f, 0x40ffffff);
        gui.c.rrect(x, trackY - 1.5f, barWidth * fraction, 3, 1.5f, Theme.TEXT_2);
        gui.c.circle(x + barWidth * fraction, trackY, 4.2f, Theme.TEXT);
        gui.hit(x - 4, y, barWidth + 8, 26)
                .left(() -> {
                    gui.drag = (px, py) -> setTime.accept(
                            Math.round(23999 * Math.max(0, Math.min(1, (px - x) / barWidth))));
                    gui.drag.drag(gui.mx, gui.my);
                })
                .scroll(amount -> setTime.accept(
                        BuildingPreviewEnvironment.time() + (int) Math.copySign(250, amount)));
    }
}
