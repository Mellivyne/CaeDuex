package com.cheatprogram.compat.legacy;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;

/** Uses the native 1.21.1 buffer while keeping the inherited rendering code's vertex type. */
public final class LegacyBufferBuilder extends BufferBuilder implements LegacyVertexConsumer {
    public LegacyBufferBuilder(LegacyByteBufferBuilder memory, VertexFormat.Mode mode, VertexFormat format) {
        super(memory, mode, format);
    }

    @Override public LegacyBufferBuilder addVertex(float x, float y, float z) {
        super.addVertex(x, y, z);
        return this;
    }

    @Override public LegacyBufferBuilder setColor(int red, int green, int blue, int alpha) {
        super.setColor(red, green, blue, alpha);
        return this;
    }

    @Override public LegacyBufferBuilder setColor(int color) {
        super.setColor(color);
        return this;
    }

    @Override public LegacyBufferBuilder setUv(float u, float v) {
        super.setUv(u, v);
        return this;
    }

    @Override public LegacyBufferBuilder setUv1(int u, int v) {
        super.setUv1(u, v);
        return this;
    }

    @Override public LegacyBufferBuilder setUv2(int u, int v) {
        super.setUv2(u, v);
        return this;
    }

    @Override public LegacyBufferBuilder setNormal(float x, float y, float z) {
        super.setNormal(x, y, z);
        return this;
    }
}
