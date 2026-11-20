package com.cheatprogram.render;
import com.mojang.blaze3d.vertex.MeshData;

import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.cheatprogram.compat.legacy.*;
import com.cheatprogram.compat.legacy.LegacyCoreShaders;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import com.cheatprogram.render.WorldLines.Segment;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** Camera-facing ribbons avoid unsupported wide GL lines in newer core contexts. */
public final class ParkourLines {

    private ParkourLines() { }
    public static void draw(Render3D renderer, List<Segment> lines, float width, boolean throughWalls) {
        draw(renderer,lines,width,0,throughWalls);
    }
    public static void draw(Render3D renderer, List<Segment> lines, float width,float worldWidth, boolean throughWalls) {
        if (lines.isEmpty() || renderer == null || !Float.isFinite(width)||!Float.isFinite(worldWidth)||worldWidth<0) return;
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);float lineWidth=GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        int srcRgb=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        var shader=LegacyRenderSystem.getShader();
        renderer.begin();
        try {
            if (!throughWalls) LegacyRenderSystem.enableDepthTest();
            LegacyRenderSystem.setShader(LegacyCoreShaders.POSITION_COLOR);
            LegacyBufferBuilder buffer = LegacyTesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
double factor=Math.max(1,width)/(Math.max(.1,Math.abs(renderer.projectionMatrix().m11()))*Math.max(1,net.minecraft.client.Minecraft.getInstance().getWindow().getScreenHeight()));
            int drawn=0,visited=0;
            for (int index=0;index<lines.size();index++) {
                Segment line=lines.get(index);
                if(drawn>=16384||++visited>32768)break;
                if(line==null||(line.color()>>>24)==0||!finite(line.from())||!finite(line.to()))continue;
                Vec3 a=line.from().subtract(renderer.camera),b=line.to().subtract(renderer.camera);
                Vec3 direction=b.subtract(a),center=a.add(b).scale(.5);
                Vec3 side=direction.cross(center).normalize();
                if(side.lengthSqr()<1e-8)continue;
                double ha=worldWidth>0?worldWidth*.5:Math.max(.05,a.length())*factor;
                double hb=worldWidth>0?worldWidth*.5:Math.max(.05,b.length())*factor;
                Segment previous=index>0?lines.get(index-1):null,next=index+1<lines.size()?lines.get(index+1):null;
                boolean joinedFrom=previous!=null&&previous.to().distanceToSqr(line.from())<1e-8;
                boolean joinedTo=next!=null&&next.from().distanceToSqr(line.to())<1e-8;
                Vec3 sa=joinedFrom?join(side,edge(previous.from().subtract(renderer.camera),a),ha):side.scale(ha);
                Vec3 sb=joinedTo?join(side,edge(b,next.to().subtract(renderer.camera)),hb):side.scale(hb);
                int fromColor=joinedFrom?previous.color():line.color(),toColor=line.color();
                if(worldWidth>0){
                    // Two joined strips give the ribbon a soft cylindrical light profile.
                    vertex(buffer,a.add(sa),shade(fromColor,.76f),side);vertex(buffer,b.add(sb),shade(toColor,.76f),side);
                    vertex(buffer,b,toColor,side);vertex(buffer,a,fromColor,side);
                    vertex(buffer,a,fromColor,side);vertex(buffer,b,toColor,side);
                    vertex(buffer,b.subtract(sb),shade(toColor,.86f),side);vertex(buffer,a.subtract(sa),shade(fromColor,.86f),side);
                }else{
                    vertex(buffer,a.add(sa),fromColor,side);vertex(buffer,b.add(sb),toColor,side);
                    vertex(buffer,b.subtract(sb),toColor,side);vertex(buffer,a.subtract(sa),fromColor,side);
                }
                drawn++;
            }
            MeshData mesh=buffer.build();
            if(mesh!=null)com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(mesh);
        } finally {
            renderer.end();
            LegacyRenderSystem.depthMask(mask);LegacyRenderSystem.lineWidth(lineWidth);
            if(depth)LegacyRenderSystem.enableDepthTest();else LegacyRenderSystem.disableDepthTest();
            if(blend)LegacyRenderSystem.enableBlend();else LegacyRenderSystem.disableBlend();
            if(cull)LegacyRenderSystem.enableCull();else LegacyRenderSystem.disableCull();
            LegacyRenderSystem.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);
            if(shader==null)LegacyRenderSystem.clearShader();else LegacyRenderSystem.setShader(shader);
        }
    }
    private static Vec3 edge(Vec3 a,Vec3 b){return b.subtract(a).cross(a.add(b).scale(.5)).normalize();}
    private static Vec3 join(Vec3 side,Vec3 other,double radius){
        if(other.lengthSqr()<1e-8)return side.scale(radius);
        if(side.dot(other)<0)other=other.scale(-1);
        Vec3 miter=side.add(other).normalize();double cosine=Math.abs(miter.dot(side));
        return cosine<.25?side.scale(radius):miter.scale(Math.min(radius*2,radius/cosine));
    }
    private static int shade(int color,float light){return (color&0xff000000)|((int)(((color>>16)&255)*light)<<16)|((int)(((color>>8)&255)*light)<<8)|(int)((color&255)*light);}
    private static boolean finite(Vec3 point){return point!=null&&Double.isFinite(point.x)&&Double.isFinite(point.y)&&Double.isFinite(point.z);}
    private static void vertex(LegacyBufferBuilder buffer, Vec3 point, int color, Vec3 normal) {
        buffer.addVertex((float)point.x, (float)point.y, (float)point.z).setColor(color);
    }
}
