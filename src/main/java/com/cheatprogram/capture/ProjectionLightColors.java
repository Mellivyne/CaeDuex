package com.cheatprogram.capture;

import com.cheatprogram.mixin.NativeImagePixelsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;

/** A small color grid sampled from the BGRA image already held by the texture uploader. */
public final class ProjectionLightColors {
    public static final int GRID = 3;
    private static final int SAMPLES = 6;
    private static final boolean BIG_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
    private static final double[] LINEAR = new double[256];

    static {
        for (int i = 0; i < LINEAR.length; i++) {
            double channel = i / 255.0;
            LINEAR[i] = channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
        }
    }

    private ProjectionLightColors() { }

    static int[] sample(NativeImage image) {
        long pixels = ((NativeImagePixelsAccessor) (Object) image).cheatprogram$pixels();
        int width = image.getWidth(), height = image.getHeight();
        int[] colors = new int[GRID * GRID];
        for (int row = 0; row < GRID; row++) {
            for (int column = 0; column < GRID; column++) {
                double red = 0, green = 0, blue = 0;
                for (int y = 0; y < SAMPLES; y++) {
                    int py = samplePosition(row, y, height);
                    for (int x = 0; x < SAMPLES; x++) {
                        int px = samplePosition(column, x, width);
                        int bgra = MemoryUtil.memGetInt(pixels + 4L * (py * width + px));
                        if (BIG_ENDIAN) bgra = Integer.reverseBytes(bgra);
                        red += LINEAR[bgra >> 16 & 255];
                        green += LINEAR[bgra >> 8 & 255];
                        blue += LINEAR[bgra & 255];
                    }
                }
                colors[row * GRID + column] = encode(red) << 16 | encode(green) << 8 | encode(blue);
            }
        }
        return colors;
    }

    private static int samplePosition(int cell, int sample, int length) {
        return (int) ((cell * SAMPLES + sample + .5) * length / (GRID * SAMPLES));
    }

    private static int encode(double sum) {
        double channel = sum / (SAMPLES * SAMPLES);
        return (int) Math.round(255 * (channel <= .0031308 ? channel * 12.92 : 1.055 * Math.pow(channel, 1 / 2.4) - .055));
    }
}
