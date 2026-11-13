package com.cheatprogram.compat.legacy;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Bridges the shared glyph vertex code to 1.21.1's implicit vertex completion. */
public interface LegacyVertexConsumer extends VertexConsumer {
    static LegacyVertexConsumer wrap(VertexConsumer output) {
        if (output instanceof LegacyVertexConsumer compatible) return compatible;
        return new LegacyVertexConsumer() {
            @Override public LegacyVertexConsumer addVertex(float x, float y, float z) {
                output.addVertex(x, y, z);
                return this;
            }
            @Override public LegacyVertexConsumer setColor(int red, int green, int blue, int alpha) {
                output.setColor(red, green, blue, alpha);
                return this;
            }
            @Override public LegacyVertexConsumer setUv(float u, float v) {
                output.setUv(u, v);
                return this;
            }
            @Override public LegacyVertexConsumer setUv1(int u, int v) {
                output.setUv1(u, v);
                return this;
            }
            @Override public LegacyVertexConsumer setUv2(int u, int v) {
                output.setUv2(u, v);
                return this;
            }
            @Override public LegacyVertexConsumer setNormal(float x, float y, float z) {
                output.setNormal(x, y, z);
                return this;
            }
        };
    }

    @Override LegacyVertexConsumer addVertex(float x, float y, float z);
    @Override LegacyVertexConsumer setColor(int red, int green, int blue, int alpha);
    @Override LegacyVertexConsumer setUv(float u, float v);
    @Override LegacyVertexConsumer setUv1(int u, int v);
    @Override LegacyVertexConsumer setUv2(int u, int v);
    @Override LegacyVertexConsumer setNormal(float x, float y, float z);

    @Override default LegacyVertexConsumer addVertex(Matrix4f matrix, float x, float y, float z) {
        Vector3f point = matrix.transformPosition(new Vector3f(x, y, z));
        return addVertex(point.x, point.y, point.z);
    }

    @Override default LegacyVertexConsumer setColor(int color) {
        return setColor(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24);
    }

    @Override default LegacyVertexConsumer setColor(float red, float green, float blue, float alpha) {
        return setColor((int) (red * 255), (int) (green * 255), (int) (blue * 255), (int) (alpha * 255));
    }

    default void endVertex() {
        // Adding the next vertex or building the mesh completes the current vertex in 1.21.1.
    }
}
