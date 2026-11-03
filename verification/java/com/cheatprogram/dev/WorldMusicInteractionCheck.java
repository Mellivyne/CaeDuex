package com.cheatprogram.dev;

import com.cheatprogram.render.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.List;

public final class WorldMusicInteractionCheck {
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
    private static Vector3f center(Matrix4f matrix){return matrix.getTranslation(new Vector3f());}
    private static Vector3f axis(Matrix4f matrix,int index){return matrix.transformDirection(index==0?new Vector3f(1,0,0):new Vector3f(0,1,0));}
    private static void close(Vector3f a,Vector3f b,float tolerance,String message){check(a.distance(b)<tolerance,message+" (distance="+a.distance(b)+")");}
    private static void finite(Matrix4f matrix,String message){float[] values=new float[16];matrix.get(values);for(float value:values)check(Float.isFinite(value),message);}
    private static Matrix4f gallery(Matrix4f anchor,Matrix4f source,float width,float height,float depth,boolean artwork,float coverSize){
        return WorldMusicGeometry.galleryTransform(anchor,source,width,height,depth,4.5f,1,artwork,coverSize);
    }
    private static void galleryRegression(){
        for(int count:new int[]{1,2,101,100000}){
            AlbumSpaceModel scene=new AlbumSpaceModel();scene.reset("gallery",count-1,count);
            var selected=scene.cards().stream().filter(card->card.index()==count-1).findFirst().orElseThrow();
            Matrix4f placed=gallery(new Matrix4f().translation(0,0,-4.5f),selected.matrix(),12,5,5,true,1.45f);
            finite(placed,"Finite gallery cover at large library bounds");
            for(float x:new float[]{-1,1})for(float y:new float[]{-1,1}){
                Vector3f corner=placed.transformPosition(new Vector3f(x,y,0));
                check(corner.z<-.10f,"Default gallery cover must remain wholly in front of the camera");
            }
        }
        AlbumSpaceModel scene=new AlbumSpaceModel();scene.reset("gallery",50,101);
        var cards=scene.cards();var glass=scene.glass();
        var selected=cards.stream().filter(card->card.index()==50).findFirst().orElseThrow();
        // The first three surfaces are the corridor; the remaining lenses correspond
        // exactly to the visible source cards and sit a small distance behind them.
        check(glass.size()==cards.size()+3,"Gallery backing/cover correspondence");
        for(float[] dimensions:new float[][]{{12,5,5},{18,7,8},{7,11,2},{26,3,11}}){
            float width=dimensions[0],height=dimensions[1],depth=dimensions[2];
            Matrix4f anchor=new Matrix4f().translation(3,2,-8).rotateY(.6f);
            for(int i=0;i<cards.size();i++){
                Matrix4f source=cards.get(i).matrix(),backSource=glass.get(i+3).matrix();
                Matrix4f placed=gallery(anchor,source,width,height,depth,true,1.45f);
                Matrix4f backing=gallery(anchor,backSource,width,height,depth,true,1.45f);
                finite(placed,"Finite gallery artwork under customized dimensions");
                finite(backing,"Finite gallery lens under customized dimensions");
                Vector3f x=axis(placed,0),y=axis(placed,1),bx=axis(backing,0),by=axis(backing,1);
                check(Math.abs(x.length()-y.length())<.002f,"Gallery artwork stays square after depth adjustment");
                check(Math.abs(x.dot(y))<.002f*x.length()*y.length(),"Gallery artwork axes stay orthogonal");
                check(Math.abs(x.normalize(new Vector3f()).dot(bx.normalize(new Vector3f())))>.999f,"Backing and cover horizontal axes agree");
                check(Math.abs(y.normalize(new Vector3f()).dot(by.normalize(new Vector3f())))>.999f,"Backing and cover vertical axes agree");
                Vector3f offset=center(backing).sub(center(placed));
                check(Math.abs(offset.dot(x.normalize(new Vector3f())))<.10f&&Math.abs(offset.dot(y.normalize(new Vector3f())))<.10f,"Backing must not drift sideways from the cover");
                check(bx.length()>x.length()&&by.length()>y.length(),"Glass lens still surrounds its cover");
                close(center(placed),center(gallery(anchor,source,width,height,depth,true,1)),.0005f,"Cover size changes must not move its scene anchor");
            }
            // A structural glass plane must be transformed as one affine sheet.
            // Mapping just its centre while leaving its direction vectors unchanged
            // makes long sides cross the camera and detach from the covers.
            for(int i=0;i<3;i++){
                Matrix4f source=glass.get(i).matrix();
                Matrix4f placed=gallery(anchor,source,width,height,depth,false,1.45f);
                finite(placed,"Finite corridor structure");
                for(Vector3f uv:List.of(new Vector3f(-1,-1,0),new Vector3f(1,-1,0),new Vector3f(1,1,0),new Vector3f(-1,1,0),new Vector3f(.27f,-.38f,0))){
                    Vector3f sourcePoint=source.transformPosition(new Vector3f(uv));
                    Matrix4f pointMatrix=new Matrix4f().translation(sourcePoint);
                    Vector3f expected=center(gallery(anchor,pointMatrix,width,height,depth,false,1.45f));
                    close(placed.transformPosition(new Vector3f(uv)),expected,.001f,"The whole glass sheet must use the same scene transform");
                }
            }
            Matrix4f next=cards.stream().filter(card->card.index()==52).findFirst().orElseThrow().matrix();
            Matrix4f previous=cards.stream().filter(card->card.index()==48).findFirst().orElseThrow().matrix();
            Vector3f a=center(gallery(anchor,selected.matrix(),width,height,depth,true,1.45f));
            Vector3f b=center(gallery(anchor,next,width,height,depth,true,1.45f));
            Vector3f c=center(gallery(anchor,previous,width,height,depth,true,1.45f));
            check(a.distance(b)>.20f&&a.distance(c)>.20f,"Neighboring gallery rows must not collapse onto the same depth plane");
            close(new Vector3f(a).sub(c),new Vector3f(b).sub(a),.001f,"Gallery row spacing remains continuous through the viewing position");
        }
    }
    public static void main(String[] args){
        WorldMusicGesture g=new WorldMusicGesture();
        g.press(true,0);check(!g.release(90,280),"Opening tap must not skip the current layout");
        g.press(false,100);g.press(false,180);check(g.release(200,280),"One short press must cycle once despite key repeat");
        g.press(false,300);check(!g.hold(500,280),"Hold threshold");check(g.hold(590,280),"Hold begins interaction");check(!g.release(600,280),"Hold must not switch layout");
        g.press(false,900);check(!g.release(1300,280),"Long press on a stalled frame must not be mistaken for tap");
        g.press(false,1500);g.cancel();check(!g.release(1600,280),"Escape/focus loss cancels pending tap");
        Matrix4f projection=new Matrix4f().perspective((float)Math.toRadians(70),16f/9,.05f,128),inverse=new Matrix4f(projection).invert();
        var far=new AlbumSpaceModel.Card(3,new Matrix4f().translation(0,0,-8));
        var near=new AlbumSpaceModel.Card(8,new Matrix4f().translation(0,0,-4));
        check(WorldMusicGeometry.pick(List.of(far,near),inverse,0,0)==8,"Nearest overlapping artwork wins");
        check(WorldMusicGeometry.pick(List.of(near,far),inverse,0,0)==8,"Picking independent of draw order");
        check(WorldMusicGeometry.pick(List.of(near),inverse,.95f,.95f)==-1,"Empty world must not select a cover");
        check(WorldMusicGeometry.pick(List.of(new AlbumSpaceModel.Card(4,new Matrix4f().translation(0,0,3))),inverse,0,0)==-1,"Behind camera not clickable");
        for(String layout:List.of("floor","stars","spiral","sphere","gallery"))for(int count:new int[]{1,2,101,100000}){
            AlbumSpaceModel scene=new AlbumSpaceModel();scene.reset(layout,count-1,count);scene.update(.05f);
            check(scene.cards().stream().anyMatch(c->c.index()==count-1),"Last track reachable: "+layout);
            for(var card:scene.cards()){
                Matrix4f transformed=WorldMusicGeometry.transform(new Matrix4f().translation(0,0,-7),card.matrix(),-8,12,5,5,7,1);
                float[] entries=new float[16];transformed.get(entries);for(float v:entries)check(Float.isFinite(v),"Finite matrix "+layout);
                Vector3f x=transformed.transformDirection(new Vector3f(1,0,0)),y=transformed.transformDirection(new Vector3f(0,1,0));
                check(Math.abs(x.length()-y.length())<.001,"Preserve album aspect ratio: "+layout);
            }
        }
        Matrix4f view=new Matrix4f().rotateY(.65f),anchor=new Matrix4f().translation(3,1,-9).rotateY(-.65f);
        Matrix4f cover=WorldMusicGeometry.transform(anchor,new Matrix4f().translate(0,0,-3.65f),-3.65f,18,6,8,9,1);
        Matrix4f vp=new Matrix4f(projection).mul(view);Vector3f point=vp.transformProject(new Vector3f(cover.m30(),cover.m31(),cover.m32()));
        check(WorldMusicGeometry.pick(List.of(new AlbumSpaceModel.Card(9,cover)),vp.invert(),point.x,point.y)==9,"Rotated world anchor picking");
        galleryRegression();
        System.out.println("WORLD_MUSIC_PASS tap/hold/repeat/cancel, nearest-depth picking, all spatial layouts, 100000-track bounds, rotated world anchor, artwork proportions, gallery affine geometry/backing alignment/row continuity");
    }
}
