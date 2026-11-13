package com.cheatprogram.render;
import com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer;

import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.cheatprogram.compat.legacy.*;
import net.minecraft.client.Minecraft;
import com.cheatprogram.compat.legacy.LegacyCoreShaders;
import org.joml.Matrix4f;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** Authored center-line calligraphy, revealed by travelled pen distance, never by a rectangular wipe. */
public final class HandwrittenTitle {
    private record Stroke(float ax,float ay,float bx,float by,float begin,float end,boolean start) { }
    private static final List<Stroke> STROKES=new ArrayList<>();
    private static final float WIDTH;
    private static final float MIN_Y, MAX_Y;
    private static final int[] TRANS_COLORS={0x55cdfc,0xf7a8b8,0xffffff,0xf7a8b8};
    private static final float[] CIRCLE_X=new float[9],CIRCLE_Y=new float[9];
    private static float length;
    static {
        for(int i=0;i<=8;i++){CIRCLE_X[i]=(float)Math.cos(i*Math.PI/4);CIRCLE_Y[i]=(float)Math.sin(i*Math.PI/4);}
        WIDTH=HandwrittenTitlePath.WIDTH;
        MIN_Y=HandwrittenTitlePath.MIN_Y;
        MAX_Y=HandwrittenTitlePath.MAX_Y;
        length=HandwrittenTitlePath.LENGTH;
        for(HandwrittenTitlePath.Stroke stroke:HandwrittenTitlePath.STROKES)
            STROKES.add(new Stroke(stroke.ax(),stroke.ay(),stroke.bx(),stroke.by(),stroke.begin(),stroke.end(),stroke.start()));
    }
    private HandwrittenTitle(){ }
    public static float aspect(){return WIDTH/170f;}
    /** Includes glow and round caps, for keeping the authored path inside the viewport. */
    public static float halfHeight(float width,float thickness,float glow){
        return width/WIDTH*((MAX_Y-MIN_Y)/2+6*thickness*(glow>0?2.1f:1));
    }
    public static void draw(float centerX,float centerY,float width,float progress,float opacity,String palette,float phase,float thickness,float glow){
        if(progress<=0||opacity<=0||width<=0||!Float.isFinite(width)||!R2D.isActive())return;
        float scale=width/WIDTH,x=centerX-width/2,y=centerY-(MIN_Y+MAX_Y)/2*scale,radius=6*scale*thickness;
        Matrix4f pose=R2D.canvasPose();float gui=(float)Minecraft.getInstance().getWindow().getGuiScale();
        var previous=LegacyRenderSystem.getShader();
        try{
            LegacyRenderSystem.setShader(LegacyCoreShaders.POSITION_COLOR);
            for(int pass=2;pass>=0;pass--){
                if(pass>0&&glow<=0)continue;
                float spread=pass==2?2.1f:pass==1?1.45f:1;
                float alpha=opacity*(pass==0?1:pass==1?.18f*glow:.055f*glow);
                LegacyBufferBuilder buffer=LegacyTesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);
                float cutoff=length*Math.max(0,Math.min(1,progress));
                for(Stroke stroke:STROKES){
                    if(stroke.begin>=cutoff)break;
                    float portion=Math.min(1,(cutoff-stroke.begin)/(stroke.end-stroke.begin));
                    float bx=stroke.ax+(stroke.bx-stroke.ax)*portion,by=stroke.ay+(stroke.by-stroke.ay)*portion;
                    float ax=x+stroke.ax*scale,ay=y+stroke.ay*scale,ex=x+bx*scale,ey=y+by*scale;
                    float dx=ex-ax,dy=ey-ay,len=(float)Math.hypot(dx,dy);if(len<.001)continue;
                    float r=radius*spread,nx=-dy/len*r,ny=dx/len*r;
                    int start=color(stroke.ax/WIDTH,palette,phase,alpha),end=color(bx/WIDTH,palette,phase,alpha);
                    vertex(buffer,pose,gui,ax+nx,ay+ny,start);vertex(buffer,pose,gui,ax-nx,ay-ny,start);vertex(buffer,pose,gui,ex-nx,ey-ny,end);
                    vertex(buffer,pose,gui,ax+nx,ay+ny,start);vertex(buffer,pose,gui,ex-nx,ey-ny,end);vertex(buffer,pose,gui,ex+nx,ey+ny,end);
                    if(stroke.start)disk(buffer,pose,gui,ax,ay,r,start);
                    disk(buffer,pose,gui,ex,ey,r,end);
                }
                RenderedBuffer mesh=buffer.build();if(mesh!=null)com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(mesh);
            }
        }finally{if(previous==null)LegacyRenderSystem.clearShader();else LegacyRenderSystem.setShader(previous);}
    }
    private static void disk(LegacyBufferBuilder b,Matrix4f pose,float gui,float x,float y,float radius,int color){
        for(int i=0;i<8;i++){
            vertex(b,pose,gui,x,y,color);vertex(b,pose,gui,x+CIRCLE_X[i]*radius,y+CIRCLE_Y[i]*radius,color);
            vertex(b,pose,gui,x+CIRCLE_X[i+1]*radius,y+CIRCLE_Y[i+1]*radius,color);
        }
    }
    private static void vertex(LegacyBufferBuilder b,Matrix4f pose,float gui,float x,float y,int color){b.addVertex(pose,x/gui,y/gui,0).setColor(color);}
    private static int color(float x,String palette,float phase,float opacity){
        int rgb;
        if(palette.equals("intro_white"))rgb=0xffffff;
        else if(palette.equals("intro_trans")){
            // Five equal spatial bands reproduce the supplied flag; a four-step mirrored cycle avoids blue->blue seams.
            float u=x*5+phase;int step=(int)Math.floor(u);float edge=u-step;
            int a=TRANS_COLORS[Math.floorMod(step,4)],c=TRANS_COLORS[Math.floorMod(step+1,4)];
            float mix=edge<.7f?0:(edge-.7f)/.3f;mix=mix*mix*(3-2*mix);
            rgb=mix(a,c,mix);
        }else rgb=Color.HSBtoRGB(.015f+Math.max(0,Math.min(1,x))*.77f,1,1)&0xffffff;
        return Math.round(Math.min(1,opacity)*255)<<24|rgb;
    }
    private static int mix(int a,int b,float t){int r=Math.round((a>>16&255)*(1-t)+(b>>16&255)*t),g=Math.round((a>>8&255)*(1-t)+(b>>8&255)*t),c=Math.round((a&255)*(1-t)+(b&255)*t);return r<<16|g<<8|c;}
}
