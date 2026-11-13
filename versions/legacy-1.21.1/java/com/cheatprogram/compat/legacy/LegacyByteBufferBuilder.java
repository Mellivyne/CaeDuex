package com.cheatprogram.compat.legacy;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;

/** Owns the native storage shared by a preview mesh and its builder. */
public final class LegacyByteBufferBuilder extends ByteBufferBuilder {
    public LegacyByteBufferBuilder(int capacity) {
        super(capacity);
    }
}
