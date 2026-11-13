package com.cheatprogram.render;

import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/** Shared pen paths for the CaeDuex opening title. */
final class HandwrittenTitlePath {
    record Stroke(float ax, float ay, float bx, float by, float begin, float end, boolean start) { }

    static final List<Stroke> STROKES;
    static final float WIDTH;
    static final float MIN_Y;
    static final float MAX_Y;
    static final float LENGTH;

    static {
        Path2D.Float title = new Path2D.Float();
        float offset = 0;
        for (char letter : "CaeDuex".toCharArray()) {
            Path2D.Float glyph = new Path2D.Float();
            float advance = drawGlyph(letter, glyph);
            title.append(glyph.getPathIterator(AffineTransform.getTranslateInstance(offset, 0)), false);
            offset += advance;
        }
        WIDTH = offset;

        List<Stroke> strokes = new ArrayList<>();
        float length = 0;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float[] point = new float[2];
        float x = 0;
        float y = 0;
        boolean start = true;
        for (PathIterator path = title.getPathIterator(null, 1.25); !path.isDone(); path.next()) {
            int segment = path.currentSegment(point);
            float nextX = point[0];
            float nextY = point[1];
            if (segment == PathIterator.SEG_MOVETO) {
                start = true;
            } else if (segment == PathIterator.SEG_LINETO) {
                float distance = (float) Math.hypot(nextX - x, nextY - y);
                if (distance > .0001f) {
                    strokes.add(new Stroke(x, y, nextX, nextY, length, length + distance, start));
                    length += distance;
                    minY = Math.min(minY, Math.min(y, nextY));
                    maxY = Math.max(maxY, Math.max(y, nextY));
                    start = false;
                }
            }
            x = nextX;
            y = nextY;
        }
        STROKES = List.copyOf(strokes);
        MIN_Y = minY;
        MAX_Y = maxY;
        LENGTH = length;
    }

    private HandwrittenTitlePath() { }

    private static float drawGlyph(char letter, Path2D.Float path) {
        return switch (letter) {
            case 'C' -> {
                path.moveTo(80, 24);
                path.curveTo(50, -10, 2, 22, 0, 60);
                path.curveTo(-2, 100, 40, 108, 72, 82);
                path.curveTo(82, 72, 86, 78, 94, 88);
                yield 94;
            }
            case 'a' -> {
                path.moveTo(0, 88);
                path.curveTo(7, 49, 45, 38, 43, 62);
                path.curveTo(42, 94, 2, 108, 6, 75);
                path.curveTo(13, 50, 34, 45, 45, 52);
                path.curveTo(42, 69, 30, 105, 58, 86);
                yield 58;
            }
            case 'e' -> {
                path.moveTo(0, 86);
                path.curveTo(34, 75, 42, 47, 23, 48);
                path.curveTo(-6, 48, -1, 100, 31, 96);
                path.curveTo(40, 96, 50, 91, 54, 86);
                yield 54;
            }
            case 'D' -> {
                path.moveTo(0, 86);
                path.curveTo(15, 80, 31, 35, 38, 14);
                path.curveTo(33, 41, 28, 75, 17, 97);
                path.curveTo(57, 107, 100, 70, 97, 36);
                path.curveTo(96, 9, 55, 0, 37, 15);
                path.moveTo(20, 96);
                path.curveTo(38, 109, 85, 101, 106, 86);
                yield 106;
            }
            case 'u' -> {
                path.moveTo(0, 86);
                path.curveTo(12, 75, 17, 58, 20, 48);
                path.curveTo(18, 64, 4, 98, 24, 97);
                path.curveTo(39, 96, 47, 69, 52, 49);
                path.curveTo(49, 63, 40, 97, 56, 96);
                path.curveTo(62, 96, 66, 90, 72, 86);
                yield 72;
            }
            case 'x' -> {
                path.moveTo(0, 86);
                path.curveTo(10, 79, 19, 51, 24, 52);
                path.curveTo(33, 50, 29, 94, 46, 94);
                path.curveTo(56, 94, 64, 89, 72, 84);
                path.moveTo(11, 97);
                path.curveTo(27, 89, 43, 59, 53, 48);
                yield 72;
            }
            default -> throw new IllegalArgumentException("Unsupported opening title letter: " + letter);
        };
    }
}
