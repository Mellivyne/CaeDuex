package com.cheatprogram.render;

import org.joml.Matrix4f;

/** Keeps fixed world text upright and readable on the camera's side of its plane. */
public final class SubtitlePlane {
    private SubtitlePlane() { }

    public static void faceViewer(Matrix4f plane) {
        float facing = -(plane.m20() * plane.m30() + plane.m21() * plane.m31() + plane.m22() * plane.m32());
        if (facing < 0) plane.rotateY((float) Math.PI);
    }
}
