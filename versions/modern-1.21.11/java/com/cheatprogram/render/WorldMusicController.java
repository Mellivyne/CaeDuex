package com.cheatprogram.render;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.media.player.MusicArtworkCache;
import com.cheatprogram.media.player.MusicModels.*;
import com.cheatprogram.media.player.MusicPlayer;
import com.cheatprogram.module.visual.FloatingSubtitles;
import com.cheatprogram.util.KeyNames;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

/** World-space album browser. No screen replacement, account request or playback on layout changes. */
public final class WorldMusicController {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final MusicPlayer PLAYER = MusicPlayer.INSTANCE;
    private static final WorldMusicGesture GESTURE = new WorldMusicGesture();
    private static final MusicCoverCarouselRenderer RENDERER = new MusicCoverCarouselRenderer();
    private static final MusicCoverCarouselRenderer.Motion FLOW = new MusicCoverCarouselRenderer.Motion();
    private static final MusicCoverCarouselRenderer.RingMotion RING = new MusicCoverCarouselRenderer.RingMotion();
    private static final AlbumSpaceModel SPACE = new AlbumSpaceModel();
    private static final AlbumWallModel WALL = new AlbumWallModel();
    private static MusicArtworkCache artwork;
    private static List<Track> tracks = List.of();
    private static List<AlbumSpaceModel.Card> rendered = List.of();
    private static Matrix4f inverse, wallFrame;
    private static Vec3 anchor;
    private static Quaternionf orientation;
    private static Object level;
    private static boolean visible, interacting, recenter, dragging, wasGrabbed;
    private static String layout = "coverflow", playing = "";
    private static long lastFrame, shownAt, renderedAt;
    private static float pressX, pressY, lastDragX, lastDragY;
    private static boolean dragged;
    private static int pressedCover = -1, hovered = -1;
    private static final MusicCoverCarouselRenderer.CoverHover HOVER = new MusicCoverCarouselRenderer.CoverHover();
    private static final double[] CURSOR_X = new double[1], CURSOR_Y = new double[1];
    private WorldMusicController() { }
    private static FloatingSubtitles settings() { return ModuleManager.get(FloatingSubtitles.class); }
    private static boolean allowed() {
        FloatingSubtitles module = settings();
        return module != null && module.isEnabled() && module.worldMusic.on() && MC.level != null && MC.player != null && !Config.restartRequired();
    }
    public static void open() {
        if (!allowed() || MC.screen != null || MC.getOverlay() != null) return;
        visible = true; recenter = true; level = MC.level; shownAt = System.nanoTime(); lastFrame = 0;
        layout = PLAYER.layout(); tracks = List.of(); playing = "";
        if (artwork == null) artwork = new MusicArtworkCache();
        sync();
    }
    public static void close() {
        visible = false; GESTURE.cancel(); releaseInput(); rendered = List.of(); inverse = wallFrame = null; anchor = null;
        if (artwork != null) { artwork.close(); artwork = null; }
        tracks = List.of(); RENDERER.close(); HOVER.clear();
    }
    public static boolean key(int key, int action) {
        if (key == GLFW.GLFW_KEY_ESCAPE && visible && MC.screen == null) { if(action == GLFW.GLFW_PRESS)close(); return true; }
        FloatingSubtitles module = settings();
        if (module == null || key < 0 || key != module.worldMusicKey.intVal()) return false;
        if (action == GLFW.GLFW_RELEASE && GESTURE.pressed()) {
            boolean cycle = GESTURE.release(System.currentTimeMillis(), module.worldMusicHold.intVal());
            releaseInput();
            if (cycle && visible) nextLayout();
            return true;
        }
        if (!allowed() || MC.screen != null || MC.getOverlay() != null || !MC.isWindowActive()) return false;
        if (action == GLFW.GLFW_PRESS && !GESTURE.pressed()) {
            boolean opened = !visible;
            if (opened) open();
            GESTURE.press(opened, System.currentTimeMillis());
        }
        return true;
    }
    public static void lifecycle() {
        if (!visible) return;
        if (!allowed() || MC.level != level || MC.screen != null || MC.getOverlay() != null || !MC.isWindowActive()) { close(); return; }
        if (GESTURE.hold(System.currentTimeMillis(), settings().worldMusicHold.intVal()) && !interacting) {
            interacting = true; wasGrabbed = MC.mouseHandler.isMouseGrabbed(); MC.mouseHandler.releaseMouse();
            MC.options.keyAttack.setDown(false); MC.options.keyUse.setDown(false);
        }
        // Lost focus/releases must never leave the cursor detached from the camera.
        int key = settings().worldMusicKey.intVal();
        if (GESTURE.pressed() && (key < 0 || GLFW.glfwGetKey(MC.getWindow().handle(), key) != GLFW.GLFW_PRESS)) {
            GESTURE.cancel(); releaseInput();
        }
    }
    private static void releaseInput() {
        if(dragging||!visible){FLOW.cancelInteraction();RING.cancelInteraction();}
        dragging = false; SPACE.cancel(); WALL.cancel(); pressedCover = -1;
        if (interacting) {
            interacting = false;
            MC.options.keyAttack.setDown(false); MC.options.keyUse.setDown(false);
            if (wasGrabbed && MC.screen == null && MC.player != null && MC.isWindowActive()) MC.mouseHandler.grabMouse();
        }
        wasGrabbed = false;
    }
    private static void nextLayout() {
        int selected = selected(), index = MusicPlayer.LAYOUTS.indexOf(layout);
        layout = MusicPlayer.LAYOUTS.get((index + 1) % MusicPlayer.LAYOUTS.size());
        PLAYER.layout(layout); reset(selected); recenter = true; shownAt = System.nanoTime();
    }
    private static int playingIndex() {
        Playback p = PLAYER.snapshot().playback();
        if (p != null && p.track() != null) for (int i=0;i<tracks.size();i++) if (tracks.get(i).key().equals(p.track().key())) return i;
        return 0;
    }
    private static void reset(int index) {
        dragging = false; pressedCover = -1;
        FLOW.reset(index,tracks.size()); RING.reset(index,tracks.size()); WALL.reset(index,tracks.size()); WALL.viewport(884,420);
        SPACE.reset(layout,index,tracks.size()); RENDERER.clearHover();
    }
    private static void sync() {
        Snapshot data = PLAYER.snapshot();
        if (!tracks.equals(data.tracks())) { tracks = data.tracks(); reset(playingIndex()); }
        Playback p = data.playback(); String key = p == null || p.track() == null ? "" : p.track().key();
        if (!key.equals(playing)) {
            int index = playingIndex(); FLOW.follow(index,tracks.size()); RING.follow(index,tracks.size()); WALL.select(index); SPACE.select(index); playing = key;
        }
    }
    private static int selected() {
        return AlbumSpaceModel.supports(layout)?SPACE.selected():layout.equals("wall")?WALL.selected():layout.equals("ring")?MusicCoverCarouselRenderer.RingMotion.index(RING.position(),tracks.size()):Math.max(0,Math.min(Math.max(0,tracks.size()-1),Math.round(FLOW.position())));
    }
    private static float[] ndc(double rawX,double rawY) {
        return WorldMusicPointer.ndcPoint(rawX,rawY,MC.getWindow().getScreenWidth(),MC.getWindow().getScreenHeight());
    }
    private static float[] pointer(double rawX,double rawY) {
        if(layout.equals("wall")){
            float[] n=ndc(rawX,rawY);
            return n==null?null:WorldMusicPointer.wallPoint(inverse,wallFrame,n[0],n[1]);
        }
        return WorldMusicPointer.gesturePoint(rawX,rawY,MC.getWindow().getScreenWidth(),MC.getWindow().getScreenHeight(),
                MC.getWindow().getWidth(),MC.getWindow().getHeight(),com.cheatprogram.core.ClientSettings.INSTANCE.uiScale.fraction());
    }
    private static float[] pointer() {
        GLFW.glfwGetCursorPos(MC.getWindow().handle(),CURSOR_X,CURSOR_Y);
        return pointer(CURSOR_X[0],CURSOR_Y[0]);
    }
    private static int hit() {
        if (inverse == null || System.nanoTime()-renderedAt > 200_000_000L) return -1;
        GLFW.glfwGetCursorPos(MC.getWindow().handle(),CURSOR_X,CURSOR_Y);
        float[] n=ndc(CURSOR_X[0],CURSOR_Y[0]);
        return n==null?-1:WorldMusicGeometry.pick(rendered,inverse,n[0],n[1]);
    }
    /** Native motion events feed the same logical gesture units as the music page. */
    public static boolean move(double rawX,double rawY) {
        if(!interacting||!visible||MC.screen!=null)return false;
        float[] point=pointer(rawX,rawY);
        if(dragging)drag(point);
        else if(layout.equals("wall")&&point!=null)WALL.hoverAt(point[0],point[1]);
        return true;
    }
    public static boolean button(int button, int action) {
        if (!interacting || !visible || MC.screen != null) return false;
        float[] p = pointer();
        if (button == 0 && action == GLFW.GLFW_PRESS && p!=null) {
            pressX=lastDragX=p[0];pressY=lastDragY=p[1];pressedCover=hit();dragging=true;dragged=false;
            if(layout.equals("wall"))WALL.begin(p[0],p[1]);
            else if(AlbumSpaceModel.supports(layout))SPACE.begin(p[0],p[1]);
            else if(layout.equals("ring"))RING.begin(p[0]);else FLOW.begin(p[0]);
        } else if (button == 0 && action == GLFW.GLFW_RELEASE && dragging) {
            drag(p); boolean click=p!=null&&!dragged;
            int index=click&&pressedCover==hit()?pressedCover:-1;
            if(layout.equals("wall")){if(p!=null)WALL.release(p[0],p[1]);else WALL.cancel();}
            else if(AlbumSpaceModel.supports(layout)){if(p!=null)SPACE.release(p[0],p[1]);else SPACE.cancel();}
            else if(!click){if(layout.equals("ring"))RING.release(false);else FLOW.release();}
            else {FLOW.cancelInteraction();RING.cancelInteraction();}
            dragging=false;
            if(index>=0)play(index);
        }
        return true;
    }
    public static boolean scroll(double horizontal, double vertical) {
        if (!interacting || !visible || MC.screen != null) return false;
        if (!Double.isFinite(vertical) || !Double.isFinite(horizontal)) return true;
        float[] p=pointer();
        if(layout.equals("wall")){
            if(Math.abs(horizontal)>.01)WALL.pan((float)-horizontal*40,0);
            if(p!=null&&Math.abs(vertical)>.001)WALL.zoomAt(p[0],p[1],vertical);
        }else if(Math.abs(horizontal)>.01)step(horizontal>0?-1:1);
        else if(AlbumSpaceModel.supports(layout))SPACE.scroll(vertical);
        else if(Math.abs(vertical)>.001)step(vertical>0?-1:1);
        return true;
    }
    private static void step(int delta) { if(!tracks.isEmpty())play(layout.equals("ring")?Math.floorMod(selected()+delta,tracks.size()):Math.max(0,Math.min(tracks.size()-1,selected()+delta))); }
    private static void play(int index) {
        if(!allowed()||index<0||index>=tracks.size())return;
        FLOW.follow(index,tracks.size());RING.follow(index,tracks.size());SPACE.select(index);WALL.select(index);
        PLAYER.playIndex(index);
    }
    private static void drag(float[] p) {
        if(!dragging||p==null||!Float.isFinite(p[0])||!Float.isFinite(p[1]))return;
        if(Math.abs(p[0]-lastDragX)<.0001f&&Math.abs(p[1]-lastDragY)<.0001f)return;
        lastDragX=p[0];lastDragY=p[1];
        if(Math.hypot(p[0]-pressX,p[1]-pressY)>=4)dragged=true;
        if(layout.equals("wall"))WALL.drag(p[0],p[1]);
        else if(AlbumSpaceModel.supports(layout))SPACE.drag(p[0],p[1]);
        else if(layout.equals("ring"))RING.drag(p[0]);else FLOW.drag(p[0]);
    }
    public static void render(Render3D world) {
        if(!visible||!allowed()||MC.screen!=null||RearMirrorRenderer.isRendering())return;
        lifecycle();if(!visible)return;sync();
        long now=System.nanoTime();float dt=lastFrame==0?0:Math.min(.1f,(now-lastFrame)/1e9f);lastFrame=now;
        if(layout.equals("wall")&&interacting&&!dragging){float[] point=pointer();if(point!=null)WALL.hoverAt(point[0],point[1]);}
        int flowCommit=FLOW.update(dt),ringCommit=RING.update(dt);SPACE.update(dt);WALL.update(dt);
        int commit=layout.equals("ring")?ringCommit:layout.equals("coverflow")?flowCommit:-1;
        if(commit>=0&&commit<tracks.size())play(commit);
        FloatingSubtitles s=settings();
        Vec3 targetAnchor=world.camera.add(new Vec3(world.cameraObject.forwardVector()).scale(s.worldMusicDistance.val())).add(0,s.worldMusicLift.val(),0);
        Quaternionf targetOrientation=new Quaternionf().setFromNormalized(world.viewMatrix().invert());
        if(recenter||anchor==null){anchor=targetAnchor;orientation=targetOrientation;recenter=false;}
        else if(s.worldMusicFollow.on()&&!interacting){float easing=1-(float)Math.exp(-12*dt);anchor=anchor.lerp(targetAnchor,easing);orientation.slerp(targetOrientation,easing);}
        Matrix4f base=new Matrix4f().translation((float)(anchor.x-world.camera.x),(float)(anchor.y-world.camera.y),(float)(anchor.z-world.camera.z)).rotate(orientation);
        HOVER.update(hovered,dt);
        float position=layout.equals("ring")?RING.position():FLOW.position();
        List<AlbumSpaceModel.Card> local=RENDERER.worldCards(layout,tracks.size(),position,SPACE,WALL);
        float pivot=layout.equals("coverflow")?-3.65f:layout.equals("ring")?-4:layout.equals("wall")?-6:-8;
        float age=(now-shownAt)/1e9f,reveal=1-(float)Math.exp(-9*age)*(float)Math.cos(10*age);
        List<AlbumSpaceModel.Card> cards=new ArrayList<>(local.size());
        float coverSize=(float)s.worldMusicCoverSize.fraction();
        wallFrame=layout.equals("wall")?WorldMusicGeometry.wallFrame(base,s.worldMusicWidth.floatVal(),s.worldMusicHeight.floatVal(),s.worldMusicDepth.floatVal(),s.worldMusicDistance.floatVal(),reveal,coverSize):null;
        for(var card:local){
            Matrix4f sheet=new Matrix4f(card.matrix());
            // The mosaic already grows into its seam; floor spacing owns the entire enlarged tile.
            if(!layout.equals("wall")&&!layout.equals("floor"))sheet.scale(1+.06f*HOVER.amount(card.index()));
            Matrix4f pose;
            if(layout.equals("gallery"))pose=WorldMusicGeometry.galleryTransform(base,sheet,s.worldMusicWidth.floatVal(),s.worldMusicHeight.floatVal(),s.worldMusicDepth.floatVal(),s.worldMusicDistance.floatVal(),reveal,true,coverSize);
            else if(layout.equals("wall"))pose=WorldMusicGeometry.wallTransform(base,sheet,s.worldMusicWidth.floatVal(),s.worldMusicHeight.floatVal(),s.worldMusicDepth.floatVal(),s.worldMusicDistance.floatVal(),reveal,coverSize,card.index(),selected(),hovered);
            else if(layout.equals("floor"))pose=WorldMusicGeometry.floorTransform(base,sheet,s.worldMusicWidth.floatVal(),s.worldMusicHeight.floatVal(),s.worldMusicDepth.floatVal(),s.worldMusicDistance.floatVal(),reveal,coverSize);
            else pose=WorldMusicGeometry.transform(base,sheet,pivot,s.worldMusicWidth.floatVal(),s.worldMusicHeight.floatVal(),s.worldMusicDepth.floatVal(),s.worldMusicDistance.floatVal(),reveal).scale(coverSize);
            cards.add(new AlbumSpaceModel.Card(card.index(),pose));
        }
        inverse=world.projectionMatrix().mul(world.viewMatrix()).invert();rendered=List.copyOf(cards);renderedAt=now;
        hovered=interacting?hit():-1;
        artwork.preloadVisible(tracks,local.stream().map(AlbumSpaceModel.Card::index).toList());artwork.preloadIdle(tracks,AlbumSpaceModel.supports(layout)?SPACE.browseIndex():selected());
        List<AlbumSpaceModel.GlassSurface> glass=new ArrayList<>();
        if(layout.equals("gallery")){
            var surfaces=SPACE.glass();
            for(int i=0;i<surfaces.size();i++){
                boolean backing=i>=3;float size=coverSize;
                if(backing&&i-3<local.size())size*=1+.06f*HOVER.amount(local.get(i-3).index());
                glass.add(new AlbumSpaceModel.GlassSurface(WorldMusicGeometry.galleryTransform(base,surfaces.get(i).matrix(),s.worldMusicWidth.floatVal(),s.worldMusicHeight.floatVal(),s.worldMusicDepth.floatVal(),s.worldMusicDistance.floatVal(),reveal,backing,size)));
            }
        }
        RENDERER.drawWorld(world,tracks,artwork,cards,glass,s.worldMusicThroughWalls.on(),hovered,dt);
    }
    public static void hud(HudContext ctx) {
        if(!visible||MC.screen!=null)return;
        String text=Lang.t("mode.music_layout_"+layout)+" · "+Lang.t(tracks.isEmpty()?"ui.music.worldEmpty":interacting?"ui.music.worldInteracting":"ui.music.worldHelp")+" · "+KeyNames.name(settings().worldMusicKey.intVal());
        if(!tracks.isEmpty()){Track track=tracks.get(selected());ctx.c.textMid(Fonts.Face.MEDIUM,ctx.c.ellipsize(Fonts.Face.MEDIUM,track.title()+" · "+track.artist(),10,ctx.width-40),ctx.width/2,ctx.height-63,10,0xfff8f8ff);}
        ctx.c.textMid(Fonts.Face.REGULAR,ctx.c.ellipsize(Fonts.Face.REGULAR,text,8.5f,ctx.width-40),ctx.width/2,ctx.height-46,8.5f,0xeeffffff);
    }
}
