package com.cheatprogram.core.setting;

/** Keeps precise text entry bounds independent from the range used by the drag control. */
public final class SliderNumberSetting extends NumberSetting {
    private final double sliderMin, sliderMax;

    public SliderNumberSetting(String id, double initial, double min, double max, double step, String unit,
                               double sliderMin, double sliderMax) {
        super(id, initial, min, max, step, unit);
        this.sliderMin = sliderMin;
        this.sliderMax = sliderMax;
    }

    @Override public double normalized() {
        return Math.clamp((val() - sliderMin) / (sliderMax - sliderMin), 0, 1);
    }

    @Override public void setNormalized(double t) {
        set(sliderMin + (sliderMax - sliderMin) * Math.clamp(t, 0, 1));
    }
}
