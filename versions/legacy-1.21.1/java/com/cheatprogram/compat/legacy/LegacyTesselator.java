package com.cheatprogram.compat.legacy;

import com.mojang.blaze3d.vertex.VertexFormat;

public final class LegacyTesselator {
    private static final LegacyTesselator INSTANCE = new LegacyTesselator();
    private LegacyByteBufferBuilder memory;

    private LegacyTesselator() {}

    public static LegacyTesselator getInstance() {
        return INSTANCE;
    }

    public LegacyBufferBuilder begin(VertexFormat.Mode mode, VertexFormat format) {
        if (memory == null) memory = new LegacyByteBufferBuilder(32768);
        return new LegacyBufferBuilder(memory, mode, format);
    }
}
