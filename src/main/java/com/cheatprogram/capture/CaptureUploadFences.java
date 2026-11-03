package com.cheatprogram.capture;
import org.lwjgl.opengl.GL32;

/** OpenGL fence polls use a zero timeout and never flush/wait for GPU completion. */
public final class CaptureUploadFences implements CaptureUploadQueue.Fences {
    public long insert(){return GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);}
    public boolean ready(long fence){int state=GL32.glClientWaitSync(fence,0,0);return state==GL32.GL_ALREADY_SIGNALED||state==GL32.GL_CONDITION_SATISFIED;}
    public void delete(long fence){GL32.glDeleteSync(fence);}
}
