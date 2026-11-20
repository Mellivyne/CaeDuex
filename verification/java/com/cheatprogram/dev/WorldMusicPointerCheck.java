package com.cheatprogram.dev;

import com.cheatprogram.render.AlbumSpaceModel;
import com.cheatprogram.render.AlbumWallModel;
import com.cheatprogram.render.WorldMusicGeometry;
import com.cheatprogram.render.WorldMusicPointer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Round trips rendered wall coordinates and the mouse spaces used by the real music-page gestures. */
public final class WorldMusicPointerCheck {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void close(float a, float b, float tolerance, String message) { check(Math.abs(a-b) <= tolerance, message+": "+a+" / "+b); }
    private static float[] project(Matrix4f projectionView, Matrix4f wall, float x, float y) {
        Vector3f point = wall.transformPosition(new Vector3f((x-442)/75, (210-y)/75, -6));
        projectionView.transformProject(point);
        return new float[]{point.x, point.y};
    }
    public static void main(String[] args) {
        Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70),16f/9,.05f,256);
        Matrix4f view = new Matrix4f().rotateX(.13f).rotateY(-.22f);
        Matrix4f vp = new Matrix4f(projection).mul(view), inverse = new Matrix4f(vp).invert();
        for (float scale : new float[]{.5f,1,1.45f,3}) {
            Matrix4f wall = new Matrix4f().translation(.6f,.3f,-4.5f).rotateY(.34f).rotateX(-.08f).scale(scale,scale*.9f,1).translate(0,0,6);
            for (float[] source : new float[][]{{442,210},{5,5},{879,415},{-300,700},{1500,-200}}) {
                float[] ndc = project(vp,wall,source[0],source[1]);
                float[] point = WorldMusicPointer.wallPoint(inverse,wall,ndc[0],ndc[1]);
                check(point != null, "Visible wall ray round trip");
                close(point[0],source[0],.004f,"Wall x round trip");close(point[1],source[1],.004f,"Wall y round trip");
            }
        }
        Matrix4f ordinaryWall = new Matrix4f().translation(0,0,-4.5f).translate(0,0,6);
        check(WorldMusicPointer.wallPoint(new Matrix4f(projection).invert(),new Matrix4f().translation(0,0,5).translate(0,0,6),0,0)==null,"Behind-camera wall rejected");
        check(WorldMusicPointer.wallPoint(new Matrix4f(projection).invert(),new Matrix4f().translation(0,0,-4.5f).rotateY((float)Math.PI).translate(0,0,6),0,0)==null,"Back-face wall rejected");
        check(WorldMusicPointer.wallPoint(new Matrix4f(projection).invert(),new Matrix4f().rotateY((float)Math.PI*.5f),0,0)==null,"Parallel wall ray rejected");
        check(WorldMusicPointer.wallPoint(inverse,new Matrix4f().scale(0),0,0)==null,"Singular wall transform rejected");
        check(WorldMusicPointer.wallPoint(inverse,ordinaryWall,Float.NaN,0)==null,"Invalid pointer rejected");
        check(WorldMusicPointer.wallPoint(null,ordinaryWall,0,0)==null,"Unavailable render projection rejected");

        for (boolean zeroToOne : new boolean[]{false,true}) {
            Matrix4f reversed = new Matrix4f().perspective((float)Math.toRadians(70),16f/9,256,.05f,zeroToOne);
            Matrix4f inverseReversed = new Matrix4f(reversed).invert();
            float far = zeroToOne ? 0 : -1;
            float[] sample = project(reversed,ordinaryWall,317,183);
            float[] hit = WorldMusicPointer.wallPoint(inverseReversed,ordinaryWall,sample[0],sample[1],1,far);
            check(hit!=null,"Reversed-depth wall ray");
            close(hit[0],317,.004f,"Reversed-depth wall x");
            close(hit[1],183,.004f,"Reversed-depth wall y");
            var cards=java.util.List.of(
                    new AlbumSpaceModel.Card(0,new Matrix4f().translation(0,0,-7)),
                    new AlbumSpaceModel.Card(1,new Matrix4f().translation(0,0,-4)));
            check(WorldMusicGeometry.pick(cards,inverseReversed,0,0,1,far)==1,"Reversed-depth picking selects the nearest album");
        }


        float[] normal = WorldMusicPointer.gesturePoint(300,400,1920,1080,1920,1080,1);
        float[] dpi = WorldMusicPointer.gesturePoint(200,400.0/1.5,1280,720,1920,1080,1);
        close(normal[0],dpi[0],.0001f,"DPI-independent gesture x");close(normal[1],dpi[1],.0001f,"DPI-independent gesture y");
        float[] ndc1=WorldMusicPointer.ndcPoint(300,400,1920,1080),ndc2=WorldMusicPointer.ndcPoint(200,400.0/1.5,1280,720);
        close(ndc1[0],ndc2[0],.0001f,"DPI-independent picking x");close(ndc1[1],ndc2[1],.0001f,"DPI-independent picking y");
        for (double[] dimensions : new double[][]{{640,480,640,480},{1920,1080,1920,1080},{1280,720,2560,1440},{3840,2160,3840,2160}}) {
            for (double uiScale : new double[]{.7,1,1.5}) {
                float[] start=WorldMusicPointer.gesturePoint(100,100,dimensions[0],dimensions[1],dimensions[2],dimensions[3],uiScale);
                float[] same=WorldMusicPointer.gesturePoint(100,100,dimensions[0],dimensions[1],dimensions[2],dimensions[3],uiScale);
                float[] moved=WorldMusicPointer.gesturePoint(100.25,100.25,dimensions[0],dimensions[1],dimensions[2],dimensions[3],uiScale);
                check(start!=null&&same!=null&&moved!=null,"Finite UI-sized gestures");
                check(Float.floatToIntBits(start[0])==Float.floatToIntBits(same[0])&&Float.floatToIntBits(start[1])==Float.floatToIntBits(same[1]),"Unchanged native cursor remains an unchanged sample");
                check(moved[0]>start[0]&&moved[1]>start[1],"Native subpixel movement not rounded away");
            }
        }
        check(WorldMusicPointer.gesturePoint(-20,1200,1920,1080,1920,1080,1)[0]<0,"Gestures are not clamped at the window edge");
        check(WorldMusicPointer.gesturePoint(0,0,0,1080,1920,1080,1)==null,"Invalid window rejected");
        check(WorldMusicPointer.ndcPoint(Double.NaN,0,1920,1080)==null,"Invalid native cursor rejected");

        AlbumWallModel model = new AlbumWallModel();model.reset(50,120);model.viewport(884,420);
        Matrix4f wall = new Matrix4f().translation(0,.5f,-4.5f).scale(1.45f,1.45f,1).translate(0,0,6);
        float[] ndc=project(projection,wall,317,183);
        float[] point=WorldMusicPointer.wallPoint(new Matrix4f(projection).invert(),wall,ndc[0],ndc[1]);
        float contentX=(point[0]-model.panX())/model.zoom(),contentY=(point[1]-model.panY())/model.zoom();
        model.zoomAt(point[0],point[1],3.5);
        close((point[0]-model.panX())/model.zoom(),contentX,.001f,"Wheel zoom preserves the actual pointed wall x");
        close((point[1]-model.panY())/model.zoom(),contentY,.001f,"Wheel zoom preserves the actual pointed wall y");
        System.out.println("WORLD_MUSIC_POINTER_PASS wall-ray roundtrip, perspective/scale/rotation, DPI-independent gestures, unchanged-sample stability, unclamped drag, zoom anchoring");
    }
}
