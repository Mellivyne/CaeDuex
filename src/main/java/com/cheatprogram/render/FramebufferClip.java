package com.cheatprogram.render;

/** Pixel-space scissor bounded to its destination, including completely invisible intersections. */
public record FramebufferClip(int x, int y, int width, int height) {
    /** Canvas rectangles use a top-left origin; intersect before converting to framebuffer coordinates. */
    public static FramebufferClip topLeft(float x, float y, float width, float height, int targetWidth, int targetHeight, FramebufferClip parent) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(height)
                || width <= 0 || height <= 0) return new FramebufferClip(0, 0, 0, 0);
        double boundX = Math.max(0, targetWidth), boundY = Math.max(0, targetHeight);
        int left = (int) Math.clamp(Math.floor(x), 0, boundX), top = (int) Math.clamp(Math.floor(y), 0, boundY);
        int right = (int) Math.clamp(Math.ceil((double) x + width), 0, boundX);
        int bottom = (int) Math.clamp(Math.ceil((double) y + height), 0, boundY);
        if (parent != null) {
            left = Math.max(left, parent.x); top = Math.max(top, parent.y);
            right = Math.min(right, parent.x + parent.width); bottom = Math.min(bottom, parent.y + parent.height);
        }
        return new FramebufferClip(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    public static FramebufferClip intersect(int x, int y, int width, int height, int targetWidth, int targetHeight) {
        long boundX = Math.max(0, targetWidth), boundY = Math.max(0, targetHeight);
        long left = Math.clamp((long) x, 0L, boundX), bottom = Math.clamp((long) y, 0L, boundY);
        long right = Math.clamp((long) x + Math.max(0, width), 0L, boundX);
        long top = Math.clamp((long) y + Math.max(0, height), 0L, boundY);
        return new FramebufferClip((int) left, (int) bottom, (int) Math.max(0L, right - left), (int) Math.max(0L, top - bottom));
    }

    public boolean empty() {
        return width <= 0 || height <= 0;
    }
}
