package com.cheatprogram.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Native cursor coordinates, UI-sized gestures and exact wall-plane gestures have separate spaces. */
public final class WorldMusicPointer {
    private WorldMusicPointer() { }

    /** GLFW cursor/window dimensions are logical units; normalized picking is independent of framebuffer DPI. */
    public static float[] ndcPoint(double rawX, double rawY, double windowWidth, double windowHeight) {
        if (!finite(rawX, rawY, windowWidth, windowHeight) || windowWidth <= 0 || windowHeight <= 0) return null;
        float x = (float)(rawX / windowWidth * 2 - 1), y = (float)(1 - rawY / windowHeight * 2);
        return Float.isFinite(x) && Float.isFinite(y) ? new float[]{x, y} : null;
    }

    /** Matches GuiBase.layoutPanel(1120,780); uiScale is a fraction (1.0 means 100%). No drag-edge clamp. */
    public static float[] gesturePoint(double rawX, double rawY, double windowWidth, double windowHeight,
                                       double framebufferWidth, double framebufferHeight, double uiScale) {
        if (!finite(rawX, rawY, windowWidth, windowHeight, framebufferWidth, framebufferHeight, uiScale)
                || windowWidth <= 0 || windowHeight <= 0 || framebufferWidth <= 0 || framebufferHeight <= 0 || uiScale <= 0) return null;
        float width = (float)framebufferWidth, height = (float)framebufferHeight;
        float automatic = Math.min(width / 1060f, height / 760f);
        float scale = Math.max(.55f, automatic * (float)uiScale);
        scale = Math.min(scale, Math.max(.2f, (width - 32) / 1120));
        float panelHeight = Math.min(780, Math.max(120, (height - 32) / scale));
        float originX = Math.round((width - 1120 * scale) / 2), originY = Math.round((height - panelHeight * scale) / 2);
        float x = (float)((rawX / windowWidth * framebufferWidth - originX) / scale);
        float y = (float)((rawY / windowHeight * framebufferHeight - originY) / scale);
        return Float.isFinite(x) && Float.isFinite(y) ? new float[]{x, y} : null;
    }

    /**
     * Intersect the cursor ray with the exact wallFrame's source plane z=-6, then undo
     * worldCards' original ((px-442)/75,(210-py)/75) conversion. The result can extend
     * past the wall viewport while dragging; only the layout model constrains its pan.
     */
    public static float[] wallPoint(Matrix4f inverseViewProjection, Matrix4f wallFrame, float ndcX, float ndcY) {
        if (inverseViewProjection == null || wallFrame == null || !Float.isFinite(ndcX) || !Float.isFinite(ndcY)
                || !inverseViewProjection.isFinite() || !wallFrame.isFinite()) return null;
        float determinant = wallFrame.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1e-9f) return null;
        Vector3f near = inverseViewProjection.transformProject(new Vector3f(ndcX, ndcY, -1));
        Vector3f far = inverseViewProjection.transformProject(new Vector3f(ndcX, ndcY, 1));
        if (!near.isFinite() || !far.isFinite()) return null;
        Matrix4f inverseWall = new Matrix4f(wallFrame).invert();
        Vector3f origin = inverseWall.transformPosition(new Vector3f(near));
        Vector3f direction = inverseWall.transformDirection(far.sub(near, new Vector3f()));
        // A front-facing sheet has its normal towards the camera; reject the back and near-parallel rays.
        if (!origin.isFinite() || !direction.isFinite() || direction.z >= -1e-7f) return null;
        float distance = (-6 - origin.z) / direction.z;
        if (!Float.isFinite(distance) || distance < 0) return null;
        float x = (origin.x + direction.x * distance) * 75 + 442;
        float y = 210 - (origin.y + direction.y * distance) * 75;
        return Float.isFinite(x) && Float.isFinite(y) ? new float[]{x, y} : null;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
