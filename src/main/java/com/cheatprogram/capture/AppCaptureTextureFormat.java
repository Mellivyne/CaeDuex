package com.cheatprogram.capture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL33;

/** Sample lossless Windows BGRA bytes directly, without a full-frame CPU swizzle. */
public final class AppCaptureTextureFormat {
    private AppCaptureTextureFormat(){}
    public static void configure(int texture){
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try{
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL33.GL_TEXTURE_SWIZZLE_R,GL11.GL_BLUE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL33.GL_TEXTURE_SWIZZLE_G,GL11.GL_GREEN);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL33.GL_TEXTURE_SWIZZLE_B,GL11.GL_RED);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL33.GL_TEXTURE_SWIZZLE_A,GL11.GL_ONE);
        }finally{GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous);}
    }
}
