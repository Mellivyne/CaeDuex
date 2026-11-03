package com.cheatprogram.parkour;

import com.cheatprogram.core.*;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.module.movement.AutoParkour;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.parkour.ParkourPlanner.Goal;
import com.cheatprogram.parkour.ParkourPhysics.Box;
import com.cheatprogram.render.*;
import com.cheatprogram.util.KeyNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Camera-only waypoint placement; never moves the player's body or changes blocks. */
public final class ParkourWaypointEditor {
    private static AutoParkour module;private static Screen parent;
    private static Object world,player;private static boolean ownsFreecam,binding;
    private static Goal aimed;private static String message="";
    private static final Set<Integer> consumed=new HashSet<>();
    private static final ArrayDeque<Integer> nearby=new ArrayDeque<>();
    private static int scanIndex,lastRevision=-1;private static List<WorldLines.Segment> lines=List.of();
    private ParkourWaypointEditor(){}
    public static boolean active(){return module!=null;}
    public static void begin(AutoParkour next,Screen previous,boolean captureKey){
        Minecraft mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||!mc.player.isAlive())return;
        if(active())finish(false);
        next.stopForEditing();module=next;parent=previous;world=mc.level;player=mc.player;
        binding=captureKey;message="";aimed=null;nearby.clear();scanIndex=0;lastRevision=-1;
        Freecam free=ModuleManager.get(Freecam.class);if(free==null){finish(false);return;}ownsFreecam=!free.isEnabled();
        if(ownsFreecam)free.setEnabled(true);
        if(!Freecam.isActive()){finish(false);return;}
        ParkourClient.open(null);
    }
    private static void finish(boolean returnToPanel){
        if(!active())return;Minecraft mc=Minecraft.getInstance();Screen previous=parent;
        boolean same=mc.level==world&&mc.player==player;module.stopForEditing();
        if(ownsFreecam){Freecam free=ModuleManager.get(Freecam.class);if(free!=null)free.setEnabled(false);}
        module=null;parent=null;world=player=null;ownsFreecam=binding=false;aimed=null;nearby.clear();lines=List.of();
        if(returnToPanel&&same)ParkourClient.open(previous);
    }
    public static void lifecycle(){
        if(!active())return;Minecraft mc=Minecraft.getInstance();
        if(mc.level!=world||mc.player!=player||mc.player==null||!mc.player.isAlive()||!Freecam.isActive()||!mc.isWindowActive()||ParkourClient.screenOpen()){finish(false);return;}
        if(module.isEnabled())module.stopForEditing();
        try{updateAim(mc);updateLines();}
        catch(RuntimeException failure){finish(false);com.cheatprogram.CheatProgram.LOGGER.warn("Waypoint editing stopped",failure);}
    }
    public static boolean key(int key,int action){
        if(action==GLFW.GLFW_RELEASE&&consumed.remove(key))return true;
        if(!active())return false;
        if(action!=GLFW.GLFW_PRESS)return consumed.contains(key);
        if(key==GLFW.GLFW_KEY_ESCAPE){consumed.add(key);finish(true);return true;}
        if(binding){
            consumed.add(key);
            if(key<GLFW.GLFW_KEY_SPACE||key>GLFW.GLFW_KEY_LAST||reservedKey(key)){message="parkour.editor.reserved";return true;}
            module.waypointKey(key);binding=false;message="parkour.editor.bound";return true;
        }
        if(module.waypointKey()>=0&&key==module.waypointKey()){
            consumed.add(key);updateAim(Minecraft.getInstance());
            if(aimed!=null){module.addWaypoint(aimed);message="parkour.editor.added";}else message="parkour.editor.noTarget";
            return true;
        }
        if(key==GLFW.GLFW_KEY_BACKSPACE){consumed.add(key);module.removeLastWaypoint();message="parkour.editor.undo";return true;}
        return false;
    }
    public static boolean reservedKey(int key){return key==GLFW.GLFW_KEY_BACKSPACE||key==GLFW.GLFW_KEY_W||key==GLFW.GLFW_KEY_A||key==GLFW.GLFW_KEY_S||key==GLFW.GLFW_KEY_D||key==GLFW.GLFW_KEY_SPACE||key==GLFW.GLFW_KEY_LEFT_SHIFT||key==GLFW.GLFW_KEY_RIGHT_SHIFT||key==ClientSettings.INSTANCE.guiKey;}
    private static void updateAim(Minecraft mc){
        aimed=null;if(mc.level==null||mc.player==null||!Freecam.isActive())return;
        Vec3 from=Freecam.cameraPosition(1);double yaw=Math.toRadians(Freecam.cameraYaw(1)),pitch=Math.toRadians(Freecam.cameraPitch(1));
        Vec3 end=from.add(-Math.sin(yaw)*Math.cos(pitch)*64,-Math.sin(pitch)*64,Math.cos(yaw)*Math.cos(pitch)*64);
        var hit=mc.level.clip(new ClipContext(from,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
        if(hit.getType()!=HitResult.Type.BLOCK||!mc.level.hasChunkAt(hit.getBlockPos()))return;
        var pos=hit.getBlockPos();var state=mc.level.getBlockState(pos);var context=CollisionContext.of(mc.player);
        var shape=state.getCollisionShape(mc.level,pos,context);if(shape.isEmpty())shape=state.getShape(mc.level,pos,context);
        List<Box> shapes=new ArrayList<>();for(var b:shape.toAabbs())shapes.add(new Box(b.minX+pos.getX(),b.minY+pos.getY(),b.minZ+pos.getZ(),b.maxX+pos.getX(),b.maxY+pos.getY(),b.maxZ+pos.getZ()));
        var location=hit.getLocation();aimed=ParkourWaypointTarget.top(shapes,location.x,location.y,location.z);
        if(aimed!=null)aimed=new Goal(aimed.x(),aimed.y(),aimed.z(),module.waypointRadius(),aimed.height(),aimed.region());
    }
    private static void updateLines(){
        List<Goal> points=module.waypoints();Vec3 camera=Freecam.cameraPosition(1);
        if(lastRevision!=module.waypointRevision()){nearby.clear();lastRevision=module.waypointRevision();scanIndex=Math.max(0,points.size()-128);}
        // Bounded drawing/cache work, without limiting the stored route length.
        for(int n=0;n<Math.min(points.size(),512);n++){
            int i=scanIndex++;if(scanIndex>=points.size())scanIndex=0;
            Goal g=points.get(i);if(new Vec3(g.x(),g.y(),g.z()).distanceToSqr(camera)<96*96&&!nearby.contains(i)){nearby.addLast(i);if(nearby.size()>128)nearby.removeFirst();}
        }
        var out=new ArrayList<WorldLines.Segment>();
        for(Iterator<Integer> it=nearby.iterator();it.hasNext();){int i=it.next();if(i>=points.size()){it.remove();continue;}Goal g=points.get(i);
            Vec3 p=new Vec3(g.x(),g.y()+.04,g.z());if(p.distanceToSqr(camera)>96*96){it.remove();continue;}
            ring(out,p,g.radius(),0xff5bcefa);out.add(new WorldLines.Segment(p,p.add(0,.5,0),0xfff5a9b8));
            if(i+1<points.size()){Goal next=points.get(i+1);Vec3 q=new Vec3(next.x(),next.y()+.04,next.z());if(q.distanceToSqr(camera)<96*96)out.add(new WorldLines.Segment(p,q,0x55ffffff));}
        }
        if(aimed!=null)ring(out,new Vec3(aimed.x(),aimed.y()+.05,aimed.z()),aimed.radius(),0xffffffff);
        lines=out;
    }
    private static void ring(List<WorldLines.Segment> out,Vec3 p,double r,int color){for(int i=0;i<20;i++){double a=i*Math.PI/10,b=(i+1)*Math.PI/10;out.add(new WorldLines.Segment(p.add(Math.cos(a)*r,0,Math.sin(a)*r),p.add(Math.cos(b)*r,0,Math.sin(b)*r),color));}}
    public static void render(Render3D renderer){if(active())module.renderEditorLines(renderer,lines);else if(module!=null)module.clearEditorLines();}
    public static void hud(HudContext hud){
        if(!active())return;float w=Math.min(610,hud.width-24),x=(hud.width-w)/2,y=18;
        hud.panel(x,y,w,76,12);
        hud.c.textMid(Fonts.Face.BOLD,Lang.t("parkour.editor.title",module.waypoints().size()),hud.width/2,y+18,12,0xffffffff);
        String controls=binding?Lang.t("parkour.editor.bindHint"):Lang.t("parkour.editor.controls",KeyNames.name(module.waypointKey()));
        hud.c.textMid(Fonts.Face.REGULAR,hud.c.ellipsize(Fonts.Face.REGULAR,controls,9,w-18),hud.width/2,y+39,9,0xffe9f3ff);
        String detail=message.isEmpty()?Lang.t("parkour.editor.hint"):Lang.t(message);
        hud.c.textMid(Fonts.Face.REGULAR,hud.c.ellipsize(Fonts.Face.REGULAR,detail,9,w-18),hud.width/2,y+59,9,0xffe9f3ff);
    }
}
