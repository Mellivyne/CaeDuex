package com.cheatprogram.dev;
import com.cheatprogram.render.AlbumSpaceModel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.HashSet;

/** CPU-only scene regression: no Minecraft client, textures, or account requests. */
public final class AlbumSceneGeometryCheck {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static boolean finite(Matrix4f m){float[] a=new float[16];m.get(a);for(float v:a)if(!Float.isFinite(v))return false;return true;}
    private static void valid(AlbumSpaceModel scene,int size,String kind){
        var cards=scene.cards();check(cards.size()<=85,"unbounded cards "+kind);
        var seen=new HashSet<Integer>();
        for(var card:cards){check(card.index()>=0&&card.index()<size,"invalid index "+kind);check(seen.add(card.index()),"duplicate pick index "+kind);check(finite(card.matrix()),"invalid card matrix "+kind);}
        var preload=scene.preloadIndices();var prefetched=new HashSet<Integer>();
        check(preload.size()<=101,"unbounded preload indices "+kind);
        for(int index:preload){check(index>=0&&index<size,"invalid preload index "+kind);check(prefetched.add(index),"duplicate preload index "+kind);}
        check(prefetched.containsAll(seen),"visible cover missing from preload "+kind);
        check(size==0?preload.isEmpty():scene.browseIndex()>=0&&scene.browseIndex()<size,"invalid browse state "+kind);
    }
    private static void settle(AlbumSpaceModel scene){for(int k=0;k<180;k++)scene.update(1/60f);}
    public static void main(String[] args){
        String[] kinds={"stars","sphere"};
        for(String kind:kinds)check(AlbumSpaceModel.supports(kind),"missing supported layout "+kind);
        for (String removed : new String[]{"crate", "book", "spiral", "gallery"}) {
            check(!AlbumSpaceModel.supports(removed), "removed layout is still supported: " + removed);
        }
        for(String kind:kinds)for(int size:new int[]{0,1,2,8,9,37,100,100_000}){
            var scene=new AlbumSpaceModel();scene.reset(kind,size/2,size);valid(scene,size,kind);
            if(size==0){check(scene.cards().isEmpty(),"empty library");continue;}
            scene.select(size-1);settle(scene);valid(scene,size,kind);
            check(scene.selected()==size-1,"last selection unavailable "+kind);
            check(scene.cards().stream().anyMatch(c->c.index()==size-1),"last selection not drawn "+kind);
            scene.begin(10,10);scene.drag(250,130);check(!scene.release(250,130),"drag emitted click "+kind);settle(scene);valid(scene,size,kind);
            scene.begin(20,20);check(scene.release(21,21),"tap rejected "+kind);
            scene.scroll(Double.NaN);scene.update(Float.NaN);scene.begin(Float.NaN,0);scene.drag(Float.POSITIVE_INFINITY,0);valid(scene,size,kind);
            scene.scroll(1e100);settle(scene);valid(scene,size,kind);scene.scroll(-1e100);settle(scene);valid(scene,size,kind);
        }
        var sphere=new AlbumSpaceModel();sphere.reset("sphere",42,100);settle(sphere);
        var projection=new Matrix4f().perspective((float)Math.toRadians(40),900f/420,.1f,40);
        int visible=0;for(var card:sphere.cards()){var point=new Vector3f();new Matrix4f(projection).mul(card.matrix()).transformProject(point);if(Math.abs(point.x)<1&&Math.abs(point.y)<1&&Math.abs(point.z)<1)visible++;}
        check(visible>8,"sphere has too few visible covers");
        sphere.scroll(1e100);settle(sphere);
        for(var card:sphere.cards())for(float x:new float[]{-1,1})for(float y:new float[]{-1,1}){
            var corner=new Vector3f(x,y,0);card.matrix().transformPosition(corner);
            check(corner.z<-.1f,"sphere zoom crossed the near plane");
        }
        System.out.println("ALBUM_SCENE_GEOMETRY_PASS three-layouts removed-layouts-rejected zero-small-large-libraries bounded-meshes finite-transforms full-library-selection drag-tap viewport-preload sphere-near-plane");
    }
}
