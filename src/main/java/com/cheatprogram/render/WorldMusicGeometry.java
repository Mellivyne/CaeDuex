package com.cheatprogram.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.List;

/** World rendering and picking consume the same final album transforms. */
public final class WorldMusicGeometry {
    private WorldMusicGeometry() { }

    /** The entire mosaic grows together; cover enlargement must not consume its neighboring tiles. */
    public static Matrix4f wallFrame(Matrix4f anchor, float width, float height, float depth, float distance, float reveal, float coverSize) {
        float scale = Math.min(width / 12f, height / 5f) * coverSize * Math.max(.001f, reveal);
        return new Matrix4f(anchor).scale(scale, scale, Math.max(.05f, depth / 5f)).translate(0, 0, 6);
    }

    public static Matrix4f wallTransform(Matrix4f anchor, Matrix4f sheet, float width, float height, float depth,
                                          float distance, float reveal, float coverSize, int index, int selected, int hovered) {
        // Focus transitions intentionally cross in the 2D mosaic. Give them a stable physical draw order.
        float worldLayer = Math.floorMod(index, 40) * .002f + (index == selected ? .16f : index == hovered ? .10f : 0);
        float layer = worldLayer / Math.max(.05f, depth / 5f);
        return wallFrame(anchor, width, height, depth, distance, reveal, coverSize)
                .mul(new Matrix4f(sheet).m32(-6 + layer));
    }

    /** A larger floor reserves larger tile spacing, without clamping multiple rows to the same Z. */
    public static Matrix4f floorTransform(Matrix4f anchor, Matrix4f sheet, float width, float height, float depth,
                                           float distance, float reveal, float coverSize) {
        float size = Math.min(width / 12f, height / 5f) * coverSize;
        float spreadX = Math.max(width / 12f * coverSize, size) * 1.55f;
        float spreadY = Math.max(height / 5f * coverSize, size);
        float spreadZ = Math.max(depth / 5f * coverSize, size) * 1.55f;
        Matrix4f local = new Matrix4f(sheet);
        local.m30(sheet.m30() * spreadX);
        local.m31((sheet.m31() + 1.75f) * spreadY - 1.75f);
        local.m32((sheet.m32() + 6.2f) * spreadZ);
        local.scale(size * Math.max(.001f, reveal));
        return new Matrix4f(anchor).mul(local);
    }
    public static Matrix4f transform(Matrix4f anchor, Matrix4f card, float pivot, float width, float height, float depth, float distance, float reveal) {
        Matrix4f local = new Matrix4f(card);
        // Compress the scene's depth before scaling, keeping its nearest plane in front of the viewer.
        local.m32(Math.min(distance * .60f, (local.m32() - pivot) * depth / 10f));
        local.m30(local.m30() * width / 12f); local.m31(local.m31() * height / 5f);
        // Preserve cover aspect ratio; scene width/height control spread, not artwork stretching.
        float coverScale = Math.min(width / 12f, height / 5f);
        local.scale(coverScale * Math.max(.001f, reveal));
        return new Matrix4f(anchor).mul(local);
    }

    /** A gallery is one connected space: its plane origins and axes must share the same affine map. */
    public static Matrix4f galleryTransform(Matrix4f anchor, Matrix4f sheet, float width, float height, float depth,
                                             float distance, float reveal, boolean artwork, float coverSize) {
        if (!Float.isFinite(distance) || distance <= 0) throw new IllegalArgumentException("Invalid gallery distance");
        float sx = width / 12f, sy = height / 5f, sz = depth / 5f;
        Matrix4f mapped = new Matrix4f().scaling(sx, sy, sz).translate(0, 0, 6).mul(sheet);
        float entrance = Math.max(.001f, reveal);
        if (artwork) {
            // Preserve square artwork and its backing lens even when the room's axes have different scales.
            Vector3f right = mapped.getColumn(0, new Vector3f()).normalize();
            Vector3f up = mapped.getColumn(1, new Vector3f());
            up.sub(new Vector3f(right).mul(up.dot(right))).normalize();
            Vector3f normal = new Vector3f(right).cross(up).normalize();
            float size = sheet.getColumn(0, new Vector3f()).length() * Math.min(sx, sy) * coverSize * entrance;
            right.mul(size); up.mul(size); normal.mul(size);
            mapped.m00(right.x).m01(right.y).m02(right.z);
            mapped.m10(up.x).m11(up.y).m12(up.z);
            mapped.m20(normal.x).m21(normal.y).m22(normal.z);
        } else mapped.scale(entrance);
        return new Matrix4f(anchor).mul(mapped);
    }
    public static int pick(List<AlbumSpaceModel.Card> cards, Matrix4f inverseViewProjection, float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return -1;
        Vector3f near = inverseViewProjection.transformProject(new Vector3f(x, y, -1));
        Vector3f far = inverseViewProjection.transformProject(new Vector3f(x, y, 1));
        Vector3f ray = far.sub(near, new Vector3f()).normalize();
        float nearest = Float.POSITIVE_INFINITY; int selected = -1;
        for (AlbumSpaceModel.Card card : cards) {
            if (Math.abs(card.matrix().determinant()) < 1e-8f) continue;
            Matrix4f inverse = new Matrix4f(card.matrix()).invert();
            Vector3f origin = inverse.transformPosition(new Vector3f(near));
            Vector3f direction = inverse.transformDirection(new Vector3f(ray));
            if (Math.abs(direction.z) < 1e-7f) continue;
            float t = -origin.z / direction.z;
            if (t < 0 || t >= nearest) continue;
            if (Math.abs(origin.x + direction.x * t) <= 1 && Math.abs(origin.y + direction.y * t) <= 1) {
                nearest = t; selected = card.index();
            }
        }
        return selected;
    }
}
