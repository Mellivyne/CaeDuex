package com.cheatprogram.dev;

import com.cheatprogram.render.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;

/** Exercises the animated production layouts, not a second hand-written layout. */
public final class WorldMusicPackingCheck {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Vector3f center(Matrix4f m){return m.getTranslation(new Vector3f());}
    private static Vector3f[] axes(Matrix4f m){
        Vector3f x=m.getColumn(0,new Vector3f()),y=m.getColumn(1,new Vector3f());
        return new Vector3f[]{x,y,new Vector3f(x).cross(y).normalize().mul(.0005f)};
    }
    private static boolean intersect(Matrix4f a,Matrix4f b){
        Vector3f[] aa=axes(a),bb=axes(b);Vector3f delta=center(b).sub(center(a));
        List<Vector3f> tests=new ArrayList<>();
        for(Vector3f axis:aa)tests.add(new Vector3f(axis).normalize());
        for(Vector3f axis:bb)tests.add(new Vector3f(axis).normalize());
        for(Vector3f x:aa)for(Vector3f y:bb){Vector3f n=new Vector3f(x).cross(y);if(n.lengthSquared()>1e-12f)tests.add(n.normalize());}
        for(Vector3f n:tests){float radius=0;for(Vector3f axis:aa)radius+=Math.abs(n.dot(axis));for(Vector3f axis:bb)radius+=Math.abs(n.dot(axis));if(Math.abs(n.dot(delta))>radius+.0001f)return false;}
        return true;
    }
    private static Matrix4f wallSource(AlbumWallModel.Card c){return new Matrix4f().translate((c.x()+c.size()/2-442)/75,(210-c.y()-c.size()/2)/75,-6+c.emphasis()*.12f).scale(c.size()/150);}
    public static void main(String[] args){
        Matrix4f anchor=new Matrix4f().translation(0,.5f,-4.5f);
        int frames=0,pairs=0;
        for(float[] room:new float[][]{{12,5,5},{4,2,.5f},{28,14,12},{20,3,1}})for(float size:new float[]{.5f,1,1.45f,3}){
            AlbumSpaceModel floor=new AlbumSpaceModel();floor.reset("floor",50,101);
            for(int next:new int[]{50,51,57,49}){
                floor.select(next);
                for(int frame=0;frame<24;frame++){
                    floor.update(1/60f);var source=floor.cards();List<Matrix4f> placed=new ArrayList<>();
                    for(var card:source){Matrix4f m=WorldMusicGeometry.floorTransform(anchor,card.matrix(),room[0],room[1],room[2],4.5f,1,size);check(m.isFinite(),"Floor finite");placed.add(m);}
                    for(int i=0;i<placed.size();i++)for(int j=i+1;j<placed.size();j++){
                        pairs++;check(!intersect(placed.get(i),placed.get(j)),"Floor intersection: "+source.get(i).index()+" / "+source.get(j).index()+" size="+size+" room="+Arrays.toString(room)+" frame="+frame);
                    }
                    frames++;
                }
            }
            AlbumWallModel wall=new AlbumWallModel();wall.reset(50,101);wall.viewport(884,420);
            for(int next:new int[]{50,51,57,49}){
                wall.select(next);
                for(int frame=0;frame<24;frame++){
                    wall.update(1/60f);List<AlbumWallModel.Card> cards=wall.visible(0);Map<Integer,Matrix4f> placed=new HashMap<>();
                    for(var card:cards)placed.put(card.index(),WorldMusicGeometry.wallTransform(anchor,wallSource(card),room[0],room[1],room[2],4.5f,1,size,card.index(),wall.selected(),51));
                    for(int i=0;i<cards.size();i++)for(int j=i+1;j<cards.size();j++){
                        Matrix4f a=placed.get(cards.get(i).index()),b=placed.get(cards.get(j).index());
                        check(!intersect(a,b),"Wall coplanar overlap during focus animation");
                    }
                    Matrix4f frameMatrix=WorldMusicGeometry.wallFrame(anchor,room[0],room[1],room[2],4.5f,1,size);
                    for(var card:cards){Vector3f point=frameMatrix.transformPosition(new Vector3f((card.x()+card.size()/2-442)/75,(210-card.y()-card.size()/2)/75,-6));Vector3f actual=center(placed.get(card.index()));check(Math.abs(point.x-actual.x)<.0001f&&Math.abs(point.y-actual.y)<.0001f,"Wall pointer frame must match cover centres");}
                }
            }
        }
        // Enlargement scales wall origins as well as tile size; the original mosaic seam survives at rest.
        AlbumWallModel wall=new AlbumWallModel();wall.reset(50,101);wall.viewport(884,420);
        for(var card:wall.visible(0)){
            Matrix4f small=WorldMusicGeometry.wallTransform(new Matrix4f(),wallSource(card),12,5,5,4.5f,1,1,card.index(),50,-1);
            Matrix4f big=WorldMusicGeometry.wallTransform(new Matrix4f(),wallSource(card),12,5,5,4.5f,1,1.45f,card.index(),50,-1);
            check(Math.abs(big.m30()-small.m30()*1.45f)<.0001f&&Math.abs(big.m31()-small.m31()*1.45f)<.0001f,"Wall spacing scales with covers");
        }
        AlbumSpaceModel rotated=new AlbumSpaceModel();rotated.reset("floor",50,101);
        for(float dx:new float[]{-1200,-500,0,500,1200,3000})for(float dy:new float[]{-250,150}){
            rotated.begin(0,0);rotated.drag(dx,dy);rotated.release(dx,dy);
            for(int frame=0;frame<12;frame++){
                rotated.update(1/60f);var source=rotated.cards();List<Matrix4f> placed=new ArrayList<>();
                for(var card:source)placed.add(WorldMusicGeometry.floorTransform(anchor,card.matrix(),12,5,5,4.5f,1,1.45f));
                for(int i=0;i<placed.size();i++)for(int j=i+1;j<placed.size();j++)check(!intersect(placed.get(i),placed.get(j)),"Rotating/browsing floor intersection");
            }
        }
        AlbumSpaceModel oldFloor=new AlbumSpaceModel();oldFloor.reset("floor",50,101);
        List<Matrix4f> oldCards=new ArrayList<>();for(var card:oldFloor.cards())oldCards.add(WorldMusicGeometry.transform(anchor,card.matrix(),-8,12,5,5,4.5f,1).scale(1.45f));
        int oldIntersections=0;for(int i=0;i<oldCards.size();i++)for(int j=i+1;j<oldCards.size();j++)if(intersect(oldCards.get(i),oldCards.get(j)))oldIntersections++;
        check(oldIntersections>0,"Negative control must reproduce the former floor overlap");
        System.out.println("WORLD_MUSIC_PACKING_PASS animated floor frames="+frames+" nonintersecting pairs="+pairs+"; wall stable depth and shared pointer frame; four room scales and four cover scales");
    }
}
