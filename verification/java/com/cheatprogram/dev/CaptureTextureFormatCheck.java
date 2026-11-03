package com.cheatprogram.dev;
import com.cheatprogram.capture.AppCaptureTextureFormat;
import com.cheatprogram.capture.CaptureUploadQueue;
import com.cheatprogram.capture.CaptureUploadFences;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

/** Hidden native GL fixture: samples the production BGRA mapping, never starts a game. */
public final class CaptureTextureFormatCheck {
 public static void main(String[] args){
  if(!GLFW.glfwInit())throw new AssertionError("GLFW unavailable");long window=0;int texture=0;ByteBuffer raw=MemoryUtil.memAlloc(4),pixel=MemoryUtil.memAlloc(4);
  try{
   GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS,8);
   window=GLFW.glfwCreateWindow(8,8,"Private capture texture check",0,0);if(window==0)throw new AssertionError("Hidden GL context unavailable");
   GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
   GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
   GL11.glViewport(0,0,8,8);GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_BLEND);GL11.glColor4f(1,1,1,1);
   int[][] colors={{0,0,255,0},{255,0,0,0},{0,255,0,0},{33,77,199,0},{255,255,255,1},{0,0,0,0}};
   for(int[] bgra:colors){
    raw.clear();for(int b:bgra)raw.put((byte)b);raw.flip();GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,raw);
    AppCaptureTextureFormat.configure(texture);if(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)!=texture)throw new AssertionError("Changed caller texture binding");
    GL11.glBegin(GL11.GL_QUADS);GL11.glTexCoord2f(0,0);GL11.glVertex2f(-1,-1);GL11.glTexCoord2f(1,0);GL11.glVertex2f(1,-1);GL11.glTexCoord2f(1,1);GL11.glVertex2f(1,1);GL11.glTexCoord2f(0,1);GL11.glVertex2f(-1,1);GL11.glEnd();
    pixel.clear();GL11.glReadPixels(4,4,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
    if((pixel.get(0)&255)!=bgra[2]||(pixel.get(1)&255)!=bgra[1]||(pixel.get(2)&255)!=bgra[0]||(pixel.get(3)&255)!=255)throw new AssertionError("Native BGRA texture sampled with wrong color/alpha");
    for(int i=0;i<4;i++)if((raw.get(i)&255)!=bgra[i])throw new AssertionError("CPU pixel bytes were changed");
   }
   if(GL11.glGetError()!=GL11.GL_NO_ERROR)throw new AssertionError("Native GL error");
   var uploads=new CaptureUploadQueue(new CaptureUploadFences());uploads.used(0);uploads.boundary();GL11.glFinish();
   if(uploads.acquire(1)!=0)throw new AssertionError("Signaled native GPU fence not reusable");uploads.clear();
   System.out.println("CAPTURE_TEXTURE_FORMAT_PASS 6 lossless native BGRA samples, opaque alpha, input bytes and binding retained; hidden GL window only");
  }finally{if(texture!=0)GL11.glDeleteTextures(texture);MemoryUtil.memFree(raw);MemoryUtil.memFree(pixel);if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
