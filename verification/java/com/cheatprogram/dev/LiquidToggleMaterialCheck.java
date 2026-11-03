package com.cheatprogram.dev;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import static org.lwjgl.opengl.GL33.*;

/** Hidden OpenGL fixture: runs the shipped shader without starting Minecraft. */
public final class LiquidToggleMaterialCheck {
    private static final int W = 720, H = 104;
    private static int program;

    private static int shader(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, source); glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == 0) throw new AssertionError(glGetShaderInfoLog(id));
        return id;
    }

    private static ByteBuffer render(float palette, float fill, float time) {
        glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
        glUniform1f(glGetUniformLocation(program, "Palette"), palette);
        glUniform2f(glGetUniformLocation(program, "Flow"), fill, time);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        ByteBuffer pixels = ByteBuffer.allocateDirect(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        if (glGetError() != GL_NO_ERROR) throw new AssertionError("OpenGL render error");
        return pixels;
    }

    private static long difference(ByteBuffer a, ByteBuffer b) {
        long diff = 0;
        for (int i = 0; i < a.capacity(); i++) diff += Math.abs((a.get(i) & 255) - (b.get(i) & 255));
        return diff;
    }

    private static int channel(ByteBuffer frame, int x, int y, int channel) {
        return frame.get((y * W + x) * 4 + channel) & 255;
    }

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW unavailable");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(W, H, "Liquid toggle verification", 0, 0);
        if (window == 0) throw new AssertionError("OpenGL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            Path shaders = Path.of(args[0]);
            int vertex = shader(GL_VERTEX_SHADER, Files.readString(shaders.resolve("ui.vsh")));
            int fragment = shader(GL_FRAGMENT_SHADER, Files.readString(shaders.resolve("liquid_toggle.fsh")));
            program = glCreateProgram(); glAttachShader(program, vertex); glAttachShader(program, fragment);
            glBindAttribLocation(program, 0, "Position"); glBindAttribLocation(program, 1, "UV0"); glBindAttribLocation(program, 2, "Color");
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new AssertionError(glGetProgramInfoLog(program));
            glUseProgram(program);
            if (glGetUniformLocation(program, "Palette") < 0) throw new AssertionError("Palette not linked");
            float[] identity = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
            for (String name : new String[]{"ModelViewMat", "ProjMat"}) glUniformMatrix4fv(glGetUniformLocation(program, name), false, identity);
            glUniform4f(glGetUniformLocation(program, "ColorModulator"), 1,1,1,1);
            glUniform2f(glGetUniformLocation(program, "ScreenSize"), W,H);
            glUniform2f(glGetUniformLocation(program, "Size"), 660,63);
            glUniform4f(glGetUniformLocation(program, "Radius"), 10.5f,10.5f,10.5f,10.5f);
            glUniform4f(glGetUniformLocation(program, "Glass"), 35,35,0,0);
            glUniform4f(glGetUniformLocation(program, "Rim"), 3,0,0,1);
            ByteBuffer backdrop = ByteBuffer.allocateDirect(W * H * 4);
            for (int y=0;y<H;y++) for (int x=0;x<W;x++) {
                int shade = 45 + ((x/28+y/28)%2)*28;
                backdrop.put((byte)shade).put((byte)shade).put((byte)shade).put((byte)255);
            }
            backdrop.flip();
            int texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D,texture);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,backdrop);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glUniform1i(glGetUniformLocation(program,"Sampler0"),0);glUniform1i(glGetUniformLocation(program,"Sampler1"),0);
            int output=glGenTextures();glBindTexture(GL_TEXTURE_2D,output);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
            int fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Framebuffer incomplete");
            glBindTexture(GL_TEXTURE_2D,texture);
            int vao=glGenVertexArrays(),vbo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
            float[] data=new float[54];float[][] points={{-1,1,-30,-20.5f},{-1,-1,-30,83.5f},{1,-1,690,83.5f},{-1,1,-30,-20.5f},{1,-1,690,83.5f},{1,1,690,-20.5f}};
            for(int i=0;i<6;i++){int at=i*9;data[at]=points[i][0];data[at+1]=points[i][1];data[at+3]=points[i][2];data[at+4]=points[i][3];data[at+5]=data[at+6]=data[at+7]=1;data[at+8]=.06f;}
            glBufferData(GL_ARRAY_BUFFER,data,GL_STATIC_DRAW);
            for(int i=0;i<3;i++)glEnableVertexAttribArray(i);
            glVertexAttribPointer(0,3,GL_FLOAT,false,36,0);glVertexAttribPointer(1,2,GL_FLOAT,false,36,12);glVertexAttribPointer(2,4,GL_FLOAT,false,36,20);
            glViewport(0,0,W,H);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);
            ByteBuffer empty=render(0,0,0),emptyTint=render(.75f,0,0),clear=render(0,1,1.9f),tint=render(.75f,1,1.9f),motion=render(.75f,1,2.1f),partial=render(.75f,.5f,.7f);
            if(difference(empty,emptyTint)!=0)throw new AssertionError("Palette tinted unfilled vessel");
            if(difference(render(.75f,1,0),render(.75f,1,(float)(Math.PI*2)))>2000)throw new AssertionError("Discontinuous looping list material");
            if(difference(clear,tint)<50000)throw new AssertionError("Missing colour separation");
            if(difference(tint,motion)<5000)throw new AssertionError("Missing liquid motion");
            int blue=0,pink=0,white=0;
            for(int y=30;y<74;y++)for(int x=40;x<680;x++){
                int red=channel(tint,x,y,0),green=channel(tint,x,y,1),b=channel(tint,x,y,2);
                if(b-red>18&&green-red>8)blue++;
                if(red-green>12&&b-green>3)pink++;
                if(Math.max(red,Math.max(green,b))-Math.min(red,Math.min(green,b))<12&&red>100)white++;
            }
            if(blue<500||pink<500||white<500)throw new AssertionError("Missing moving pigment volumes: "+blue+"/"+pink+"/"+white);
            if(channel(tint,0,0,3)!=0||channel(tint,360,52,3)!=255)throw new AssertionError("Invalid glass coverage");
            BufferedImage image=new BufferedImage(W,H*4,BufferedImage.TYPE_INT_ARGB);
            ByteBuffer[] frames={clear,partial,tint,motion};
            for(int row=0;row<frames.length;row++)for(int y=0;y<H;y++)for(int x=0;x<W;x++){
                ByteBuffer f=frames[row];int at=(y*W+x)*4;
                int argb=(f.get(at+3)&255)<<24|(f.get(at)&255)<<16|(f.get(at+1)&255)<<8|f.get(at+2)&255;
                image.setRGB(x,row*H+H-1-y,argb);
            }
            ImageIO.write(image,"png",Path.of(args[1]).toFile());
            System.out.println("LIQUID_TOGGLE_PASS compile/link, empty-vessel-isolation, three-colour-separation, temporal-motion, coverage");
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }
}
