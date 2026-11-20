package com.cheatprogram.media.player;

import java.awt.image.BufferedImage;

/** Extracts a representative color from the displayed cover crop on an artwork worker. */
final class ArtworkColors {
    static final int DEFAULT = 0xff5c526b;

    private ArtworkColors() { }

    static int dominant(BufferedImage image, int x, int y, int width, int height) {
        int[] counts = new int[4096];
        int[] reds = new int[4096];
        int[] greens = new int[4096];
        int[] blues = new int[4096];
        int stepX = Math.max(1, (width + 63) / 64);
        int stepY = Math.max(1, (height + 63) / 64);
        for (int row = y + stepY / 2; row < y + height; row += stepY) {
            for (int column = x + stepX / 2; column < x + width; column += stepX) {
                int pixel = image.getRGB(column, row);
                if ((pixel >>> 24) < 128) continue;
                int red = (pixel >>> 16) & 255;
                int green = (pixel >>> 8) & 255;
                int blue = pixel & 255;
                int bucket = ((red >>> 4) << 8) | ((green >>> 4) << 4) | (blue >>> 4);
                counts[bucket]++;
                reds[bucket] += red;
                greens[bucket] += green;
                blues[bucket] += blue;
            }
        }

        int bestCount = 0, bestQuality = -1, bestColor = DEFAULT;
        for (int bucket = 0; bucket < counts.length; bucket++) {
            int count = counts[bucket];
            if (count == 0 || count < bestCount) continue;
            int red = reds[bucket] / count;
            int green = greens[bucket] / count;
            int blue = blues[bucket] / count;
            int darkest = Math.min(red, Math.min(green, blue));
            int brightest = Math.max(red, Math.max(green, blue));
            int quality = 2 * (brightest - darkest) + 255 - Math.abs(brightest + darkest - 255);
            if (count > bestCount || quality > bestQuality) {
                bestCount = count;
                bestQuality = quality;
                bestColor = 0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
        return bestColor;
    }
}
