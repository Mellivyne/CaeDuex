package com.cheatprogram.dev;

import com.cheatprogram.render.WorldAlbumDepthLayer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL33.*;

/** Exercises real framebuffer attachments: overlay occlusion must not clear or write game depth. */
public final class WorldAlbumDepthCheck {
    private static final int SIZE=64;
    private static int program,vao;
    private static int shader(int type,String source){int id=glCreateShader(type);glShaderSource(id,source);glCompileShader(id);if(glGetShaderi(id,GL_COMPILE_STATUS)==0)throw new AssertionError(glGetShaderInfoLog(id));return id;}
    private static void draw(float depth,float r,float g,float b){glUseProgram(program);glUniform1f(glGetUniformLocation(program,"Depth"),depth);glUniform3f(glGetUniformLocation(program,"Tint"),r,g,b);glBindVertexArray(vao);glDrawArrays(GL_TRIANGLES,0,3);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void run(boolean texture,int samples,int depthFormat){
        int source=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,source);
        int color,depth=glGenRenderbuffers();
        if(texture){
            color=glGenTextures();int target=samples>0?GL_TEXTURE_2D_MULTISAMPLE:GL_TEXTURE_2D;glBindTexture(target,color);
            if(samples>0)glTexImage2DMultisample(target,samples,GL_RGBA8,SIZE,SIZE,true);
            else {glTexImage2D(target,0,GL_RGBA8,SIZE,SIZE,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);glTexParameteri(target,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(target,GL_TEXTURE_MAG_FILTER,GL_NEAREST);}
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,target,color,0);
        }else{
            color=glGenRenderbuffers();glBindRenderbuffer(GL_RENDERBUFFER,color);
            if(samples>0)glRenderbufferStorageMultisample(GL_RENDERBUFFER,samples,GL_RGBA8,SIZE,SIZE);else glRenderbufferStorage(GL_RENDERBUFFER,GL_RGBA8,SIZE,SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,color);
        }
        glBindRenderbuffer(GL_RENDERBUFFER,depth);
        if(samples>0)glRenderbufferStorageMultisample(GL_RENDERBUFFER,samples,depthFormat,SIZE,SIZE);else glRenderbufferStorage(GL_RENDERBUFFER,depthFormat,SIZE,SIZE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depth);
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"Source framebuffer incomplete");
        glViewport(0,0,SIZE,SIZE);glDisable(GL_SCISSOR_TEST);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);glDisable(GL_BLEND);
        glClearDepth(.25);glClearColor(.1f,.2f,.3f,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        try(WorldAlbumDepthLayer layer=new WorldAlbumDepthLayer()){
            check(layer.begin(),"Album layer must accept color "+(texture?"texture":"renderbuffer")+" samples="+samples);
            check(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=source,"Overlay must own a separate framebuffer");
            check(glGetInteger(GL_SAMPLES)==samples,"Overlay depth samples must match source color");
            draw(0,1,0,0); // depth .5 passes the independent cleared depth even though the game has .25.
            draw(.5f,0,1,0); // a second album behind it must fail its depth test.
            glBindFramebuffer(GL_FRAMEBUFFER,source);
            check(glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)==depth,"Game depth attachment replaced");
            draw(0,0,0,1); // .5 must still fail against the game's preserved .25 depth.
            int resolve=glGenFramebuffers(),result=glGenTextures();glBindTexture(GL_TEXTURE_2D,result);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,SIZE,SIZE,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,resolve);glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,result,0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glBlitFramebuffer(0,0,SIZE,SIZE,0,0,SIZE,SIZE,GL_COLOR_BUFFER_BIT,GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER,resolve);ByteBuffer pixel=ByteBuffer.allocateDirect(4);glReadPixels(SIZE/2,SIZE/2,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            check((pixel.get(0)&255)>250&&(pixel.get(1)&255)<3&&(pixel.get(2)&255)<3,"Overlay occlusion failed or game depth was cleared/written");
            check(glGetError()==GL_NO_ERROR,"Framebuffer operations produced a GL error");
            glBindFramebuffer(GL_FRAMEBUFFER,source);
            check(layer.begin(true),"Normal world mode must inherit depth format="+depthFormat);
            draw(0,1,1,0); // .5 remains hidden by inherited world depth .25.
            glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,resolve);glBlitFramebuffer(0,0,SIZE,SIZE,0,0,SIZE,SIZE,GL_COLOR_BUFFER_BIT,GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER,resolve);glReadPixels(SIZE/2,SIZE/2,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            check((pixel.get(0)&255)>250&&(pixel.get(1)&255)<3,"Inherited world depth did not occlude an album");
            glBindFramebuffer(GL_FRAMEBUFFER,source);check(layer.begin(true),"Reuse inherited depth");
            draw(-.75f,1,0,1); // writes .125 into only the private depth.
            glBindFramebuffer(GL_FRAMEBUFFER,source);draw(-.5f,0,1,0); // .25 must still pass in the untouched original world depth.
            glBindFramebuffer(GL_READ_FRAMEBUFFER,source);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,resolve);glBlitFramebuffer(0,0,SIZE,SIZE,0,0,SIZE,SIZE,GL_COLOR_BUFFER_BIT,GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER,resolve);glReadPixels(SIZE/2,SIZE/2,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            check((pixel.get(0)&255)<3&&(pixel.get(1)&255)>250&&(pixel.get(2)&255)<3,"Normal album rendering corrupted original world depth");
            check(glGetError()==GL_NO_ERROR,"Depth inheritance GL error");
            glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(resolve);glDeleteTextures(result);
        }
        glDeleteFramebuffers(source);glDeleteRenderbuffers(depth);if(texture)glDeleteTextures(color);else glDeleteRenderbuffers(color);
    }
    public static void main(String[] args){
        check(GLFW.glfwInit(),"GLFW unavailable");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,2);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window=GLFW.glfwCreateWindow(64,64,"Album depth verification",0,0);check(window!=0,"No OpenGL context");
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();vao=glGenVertexArrays();program=glCreateProgram();
            glAttachShader(program,shader(GL_VERTEX_SHADER,"#version 150\nuniform float Depth;void main(){vec2 p=vec2((gl_VertexID==1)?3.0:-1.0,(gl_VertexID==2)?3.0:-1.0);gl_Position=vec4(p,Depth,1.0);}"));
            glAttachShader(program,shader(GL_FRAGMENT_SHADER,"#version 150\nuniform vec3 Tint;out vec4 fragColor;void main(){fragColor=vec4(Tint,1.0);}"));glLinkProgram(program);check(glGetProgrami(program,GL_LINK_STATUS)!=0,glGetProgramInfoLog(program));
            int samples=Math.min(4,glGetInteger(GL_MAX_SAMPLES));
            for(int format:new int[]{GL_DEPTH_COMPONENT24,GL_DEPTH32F_STENCIL8})for(boolean texture:new boolean[]{true,false}){
                run(texture,0,format);if(samples>0)run(texture,samples,format);
            }
            System.out.println("WORLD_ALBUM_DEPTH_PASS independent album occlusion; game depth attachment/content preserved; texture/renderbuffer and MSAA color targets; inherited 24-bit and 32F/stencil depth");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
