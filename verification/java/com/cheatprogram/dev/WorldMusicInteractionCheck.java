package com.cheatprogram.dev;

import com.cheatprogram.render.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.List;

public final class WorldMusicInteractionCheck {
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
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
        for(String layout:List.of("stars","sphere"))for(int count:new int[]{1,2,101,100000}){
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
        System.out.println("WORLD_MUSIC_PASS tap/hold/repeat/cancel, nearest-depth picking, all spatial layouts, 100000-track bounds, rotated world anchor, artwork proportions");
    }
}
