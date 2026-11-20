package com.cheatprogram.render;

/** Settings for the isolated blueprint scene. No live world time or terrain is changed. */
public final class BuildingPreviewEnvironment {
    public static final int DAY = 6000;
    public static final int SUNSET = 12000;
    public static final int NIGHT = 18000;

    public enum Weather { CLEAR, RAIN, THUNDER }

    private static boolean superflat;
    private static volatile int time = DAY;
    private static volatile Weather weather = Weather.CLEAR;

    private BuildingPreviewEnvironment() {}

    public static boolean superflat() { return superflat; }
    public static void toggleTerrain() { superflat = !superflat; }
    public static int time() { return time; }
    public static void setTime(int ticks) { time = Math.floorMod(ticks, 24000); }
    public static Weather weather() { return weather; }
    public static void setWeather(Weather value) { weather = value; }

    public static float daylight() {
        float sun = (float) Math.cos((time - DAY) * (Math.PI * 2 / 24000));
        float blend = Math.max(0, Math.min(1, (sun + .16f) / .43f));
        return blend * blend * (3 - 2 * blend);
    }
}
