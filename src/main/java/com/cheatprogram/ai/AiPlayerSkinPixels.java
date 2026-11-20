package com.cheatprogram.ai;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

final class AiPlayerSkinPixels {
    private static final int[][] LEGACY_LIMB_FACES = {
            {20,48,24,52,8,16,4,20}, {24,48,28,52,12,16,8,20}, {16,52,20,64,12,20,8,32},
            {20,52,24,64,8,20,4,32}, {24,52,28,64,4,20,0,32}, {28,52,32,64,16,20,12,32},
            {36,48,40,52,48,16,44,20}, {40,48,44,52,52,16,48,20}, {32,52,36,64,52,20,48,32},
            {36,52,40,64,48,20,44,32}, {40,52,44,64,44,20,40,32}, {44,52,48,64,56,20,52,32}
    };

    private AiPlayerSkinPixels() {}

    static boolean supported(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        return width >= 64 && width <= 4096 && width % 64 == 0 && (height == width || height == width / 2);
    }

    static boolean slim(BufferedImage image) {
        if (image.getWidth() != image.getHeight()) return false;
        int scale = image.getWidth() / 64;
        // Each three-pixel arm omits two columns from the end of its side faces and top/bottom faces.
        // Skin editors may leave those unused texels transparent or fill them with opaque black.
        return padding(image, scale, 54, 20, 12) && padding(image, scale, 46, 52, 12)
                && (padding(image, scale, 50, 16, 4) || padding(image, scale, 42, 48, 4))
                && paddingEdge(image, scale, 54, 20) && paddingEdge(image, scale, 46, 52);
    }

    private static boolean paddingEdge(BufferedImage image, int scale, int x, int y) {
        for (int row = y * scale; row < (y + 12) * scale; row++) {
            if ((image.getRGB(x * scale, row) >>> 24) == 0) return true;
            int adjacent = image.getRGB(x * scale - 1, row);
            if ((adjacent >>> 24) != 0 && adjacent != 0xff000000) return true;
        }
        return false;
    }

    private static boolean padding(BufferedImage image, int scale, int x, int y, int height) {
        for (int row = y * scale; row < (y + height) * scale; row++) {
            for (int col = x * scale; col < (x + 2) * scale; col++) {
                int pixel = image.getRGB(col, row);
                if ((pixel >>> 24) != 0 && pixel != 0xff000000) return false;
            }
        }
        return true;
    }

    static BufferedImage square(BufferedImage original) {
        int width = original.getWidth();
        if (original.getHeight() == width) return original;
        int scale = width / 64;
        BufferedImage result = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(original, 0, 0, null);
            // Legacy skins share right limbs. Mirror those faces into the modern left limb locations.
            for (int[] face : LEGACY_LIMB_FACES) {
                graphics.drawImage(original, face[0] * scale, face[1] * scale, face[2] * scale, face[3] * scale,
                        face[4] * scale, face[5] * scale, face[6] * scale, face[7] * scale, null);
            }
        } finally {
            graphics.dispose();
        }
        return result;
    }
}
