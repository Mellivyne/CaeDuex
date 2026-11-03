package com.cheatprogram.render;

/** Distinguishes a tap from a hold, independent of keyboard repeat and frame rate. */
public final class WorldMusicGesture {
    private boolean pressed, opened, held;
    private long started;
    public void press(boolean openedOnPress, long now) {
        if (pressed) return;
        pressed = true; opened = openedOnPress; held = false; started = now;
    }
    public boolean hold(long now, long threshold) {
        if (pressed && now - started >= threshold) held = true;
        return pressed && held;
    }
    public boolean release(long now, long threshold) {
        hold(now, threshold);
        boolean cycle = pressed && !opened && !held;
        cancel(); return cycle;
    }
    public boolean pressed() { return pressed; }
    public void cancel() { pressed = opened = held = false; }
}
