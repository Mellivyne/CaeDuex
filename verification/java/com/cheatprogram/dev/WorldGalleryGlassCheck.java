package com.cheatprogram.dev;
import com.cheatprogram.render.AlbumSpaceModel;
import com.cheatprogram.render.WorldMusicGeometry;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33.*;
import java.nio.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
public class WorldGalleryGlassCheck {
 static final int W=1000,H=460,CW=1280,CH=720;static int vao,vbo;
 static int shader(int type,String code){int s=glCreateShader(type);glShaderSource(s,code);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new AssertionError(glGetShaderInfoLog(s));return s;}
 static int program(String v,String f){int a=shader(GL_VERTEX_SHADER,v),b=shader(GL_FRAGMENT_SHADER,f),p=glCreateProgram();glAttachShader(p,a);glAttachShader(p,b);glBindAttribLocation(p,0,"Position");glBindAttribLocation(p,1,"UV0");glBindAttribLocation(p,2,"Color");glLinkProgram(p);if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new AssertionError(glGetProgramInfoLog(p));glDeleteShader(a);glDeleteShader(b);return p;}
 static void uni(int p,String n,float...v){int loc=glGetUniformLocation(p,n);switch(v.length){case 2->glUniform2f(loc,v[0],v[1]);case 4->glUniform4f(loc,v[0],v[1],v[2],v[3]);}}
 static void matrix(int p,String n,Matrix4f m){float[] v=new float[16];m.get(v);glUniformMatrix4fv(glGetUniformLocation(p,n),false,v);}
 static void quad(int p,Matrix4f m,float alpha){
  float[] data=new float[6*9];float[][] pts={{-1,1,0,0},{-1,-1,0,1},{1,-1,1,1},{-1,1,0,0},{1,-1,1,1},{1,1,1,0}};
  for(int i=0;i<6;i++){var q=m.transform(new Vector4f(pts[i][0],pts[i][1],0,1));int j=i*9;data[j]=q.x;data[j+1]=q.y;data[j+2]=q.z;data[j+3]=pts[i][2];data[j+4]=pts[i][3];data[j+5]=data[j+6]=data[j+7]=1;data[j+8]=alpha;}
  glUseProgram(p);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,data,GL_STREAM_DRAW);glDrawArrays(GL_TRIANGLES,0,6);
 }
 static int texture(int w,int h,boolean background){
  ByteBuffer b=ByteBuffer.allocateDirect(w*h*4);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   int r=background?25+x*140/w:150,g=background?55+y*100/h:120,blue=background?85+(x/60+y/60)%2*35:200;
   b.put((byte)r).put((byte)g).put((byte)blue).put((byte)255);
  }b.flip();int t=glGenTextures();glBindTexture(GL_TEXTURE_2D,t);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,b);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return t;
 }
 static int capture(){
  int t=glGenTextures();glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,t);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,W,H);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return t;
 }
 static void gallery(int program,int backdrop,AlbumSpaceModel scene,Matrix4f anchor){
  glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,backdrop);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,backdrop);glEnable(GL_DEPTH_TEST);glEnable(GL_BLEND);glBlendFuncSeparate(GL_ONE,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);glDepthMask(false);
  var originals=scene.glass();var order=new java.util.ArrayList<Integer>();for(int i=0;i<originals.size();i++)order.add(i);order.sort(java.util.Comparator.comparingDouble(i->originals.get(i).matrix().m32()));for(int i:order)quad(program,WorldMusicGeometry.galleryTransform(anchor,originals.get(i).matrix(),12,5,5,4.5f,1,i>=3,1.45f),.025f);
 }
 static ByteBuffer pixels(){ByteBuffer pixels=ByteBuffer.allocateDirect(W*H*4);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels);return pixels;}
 public static void main(String[] args)throws Exception{
  if(!GLFW.glfwInit())throw new AssertionError("GLFW initialization failed");
  GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,2);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
  long window=GLFW.glfwCreateWindow(128,128,"Offscreen material check",0,0);if(window==0)throw new AssertionError("OpenGL context unavailable");
  try{
   GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
   Path shaders=Path.of(args[0]);String vertex=Files.readString(shaders.resolve("ui.vsh"));
   int glass=program(vertex,Files.readString(shaders.resolve("album_glass.fsh")));
   int cover=program(vertex,"#version 150\nuniform sampler2D Sampler0;in vec2 localPos;out vec4 fragColor;void main(){fragColor=texture(Sampler0,localPos);}");
   vao=glGenVertexArrays();vbo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);for(int a=0;a<3;a++)glEnableVertexAttribArray(a);glVertexAttribPointer(0,3,GL_FLOAT,false,36,0);glVertexAttribPointer(1,2,GL_FLOAT,false,36,12);glVertexAttribPointer(2,4,GL_FLOAT,false,36,20);
   int output=texture(W,H,false),background=texture(CW,CH,true),artwork=texture(64,64,false);
   int fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);int depth=glGenRenderbuffers();glBindRenderbuffer(GL_RENDERBUFFER,depth);glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,W,H);glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depth);if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("FBO incomplete");
   glViewport(0,0,W,H);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glEnable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);
   Matrix4f projection=new Matrix4f().perspective((float)Math.toRadians(70),W/(float)H,.1f,40);
   for(int p:new int[]{glass,cover}){glUseProgram(p);matrix(p,"ModelViewMat",new Matrix4f());matrix(p,"ProjMat",projection);uni(p,"ColorModulator",1,1,1,1);glUniform1i(glGetUniformLocation(p,"Sampler0"),0);}
   // Capture the actual clean world color, before any artwork has been drawn.
   glDisable(GL_DEPTH_TEST);glDepthMask(false);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,background);glUseProgram(cover);matrix(cover,"ProjMat",new Matrix4f());quad(cover,new Matrix4f(),1);matrix(cover,"ProjMat",projection);int clean=capture();
   glEnable(GL_DEPTH_TEST);glDepthMask(true);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,artwork);
   var scene=new AlbumSpaceModel();scene.reset("gallery",0,100);
   Matrix4f anchor=new Matrix4f().translation(0,.5f,-4.5f);
   for(var c:scene.cards())quad(cover,WorldMusicGeometry.galleryTransform(anchor,c.matrix(),12,5,5,4.5f,1,true,1.45f),1);
   ByteBuffer before=pixels();int dirty=capture();
   glUseProgram(glass);uni(glass,"CanvasSize",W,H);glUniform1f(glGetUniformLocation(glass,"WorldSpace"),1);uni(glass,"SceneSize",W,H);uni(glass,"ViewRect",0,0,W,H);uni(glass,"Glass",20,18,0,0);uni(glass,"Rim",3.5f,0,.005f,1);glUniform1i(glGetUniformLocation(glass,"Sampler1"),1);
   gallery(glass,clean,scene,anchor);ByteBuffer pixels=pixels();
   // Replay the old, contaminated capture path as a negative control. It must show the regression.
   glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDepthMask(false);glUseProgram(cover);matrix(cover,"ProjMat",new Matrix4f());glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,dirty);quad(cover,new Matrix4f(),1);matrix(cover,"ProjMat",projection);
   gallery(glass,dirty,scene,anchor);ByteBuffer contaminated=pixels();
   if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL error");
   BufferedImage image=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);int changed=0,transparent=0;
   for(int y=0;y<H;y++)for(int x=0;x<W;x++){int at=(y*W+x)*4;float a=(pixels.get(at+3)&255)/255f;int bx=140+x,by=CH-90-H+y;int r=(pixels.get(at)&255)+(int)((1-a)*(25+bx*140/CW)),g=(pixels.get(at+1)&255)+(int)((1-a)*(55+by*100/CH)),b=(pixels.get(at+2)&255)+(int)((1-a)*(85+(bx/60+by/60)%2*35));image.setRGB(x,H-1-y,0xff000000|Math.min(255,r)<<16|Math.min(255,g)<<8|Math.min(255,b));if(a>.01f)changed++;else transparent++;}
   int covers=0,occluded=0,leaked=0,dirtyLeaked=0;
   for(int i=0;i<W*H;i++){int at=i*4;boolean artworkPixel=(before.get(at)&255)==150&&(before.get(at+1)&255)==120&&(before.get(at+2)&255)==200;
    if(artworkPixel){covers++;if(Math.abs((before.get(at)&255)-(pixels.get(at)&255))>2||Math.abs((before.get(at+1)&255)-(pixels.get(at+1)&255))>2||Math.abs((before.get(at+2)&255)-(pixels.get(at+2)&255))>2)occluded++;}
    else {if((pixels.get(at+2)&255)>160)leaked++;if((contaminated.get(at+2)&255)>160)dirtyLeaked++;}
   }
   if(covers<4000||occluded>Math.max(5,covers/1000))throw new AssertionError("Artwork hidden by gallery glass: "+occluded+" / "+covers);
   if(leaked!=0)throw new AssertionError("Artwork was copied outside its geometry: "+leaked);
   if(dirtyLeaked<20)throw new AssertionError("Negative control failed to reproduce contaminated-glass artwork leak: "+dirtyLeaked);
   System.out.println("WORLD_GALLERY_DEPTH_PASS visible-cover-pixels="+covers+" altered="+occluded);
   System.out.println("WORLD_GALLERY_CLEAN_CAPTURE_PASS leaked="+leaked+" original-bug-control-leaks="+dirtyLeaked);
   ImageIO.write(image,"png",Path.of(args[1]).toFile());System.out.println("WORLD_GALLERY_GLASS_OPENGL_PASS shader-compile-link framebuffer-render clean-backdrop-sampling pixels="+changed+" clear="+transparent);
  }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
 }
}
