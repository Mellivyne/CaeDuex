package com.cheatprogram.render;

import com.cheatprogram.capture.SharedSceneSession;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Native glyph scenes, including only the owner's displayed current text and style. */
public final class SharedSubtitleScene {
    public record Group(double x,double y,double z,float yaw,float pitch,float roll,List<WorldSubtitleRenderer.Line> lines,
                        float letterHeight,float maxWidth,float gap,boolean walls,boolean minecraft,float glow,float radius,String palette) { }
    private record Scene(String dimension,double seconds,double colorPeriod,List<Group> groups) { }
    private static final Gson JSON=new Gson();
    private final SharedSceneSession session;
    private final List<Group> outgoing=new ArrayList<>();
    private Scene remote;private long receivedAt,lastSend;
    public SharedSubtitleScene(SharedSceneSession session){this.session=session;}
    public void reset(){remote=null;outgoing.clear();lastSend=receivedAt=0;}
    public void begin(){outgoing.clear();}
    public void add(Vec3 at,float yaw,float pitch,float roll,List<WorldSubtitleRenderer.Line> lines,float height,float width,float gap,
                    boolean walls,boolean minecraft,float glow,float radius,PaletteText.Style colors){
        if(session.publishing()&&outgoing.size()<4)outgoing.add(new Group(at.x,at.y,at.z,yaw,pitch,roll,List.copyOf(lines),height,width,gap,walls,minecraft,glow,radius,colors.palette()));
    }
    public void send(String dimension,long now,double colorPeriod){
        if(!session.publishing()||now-lastSend<33_000_000L)return;lastSend=now;
        byte[] json=JSON.toJson(new Scene(dimension,now/1e9,colorPeriod,List.copyOf(outgoing))).getBytes(StandardCharsets.UTF_8);
        if(json.length>65532)return;
        byte[] packet=new byte[json.length+4];packet[0]='C';packet[1]='P';packet[2]='L';packet[3]=1;System.arraycopy(json,0,packet,4,json.length);session.offer(packet);
    }
    public void draw(Render3D renderer,String dimension){
        if(!session.watching()){remote=null;return;}
        byte[] update=session.take();long now=System.nanoTime();
        if(update!=null)try{
            Scene parsed=JSON.fromJson(new String(update,4,update.length-4,StandardCharsets.UTF_8),Scene.class);
            if(valid(parsed)){remote=parsed;receivedAt=now;}else remote=null;
        }catch(RuntimeException invalid){remote=null;}
        Scene scene=remote;
        if(scene==null||!scene.dimension.equals(dimension)||now-receivedAt>3_000_000_000L)return;
        double elapsed=Math.min(.25,(now-receivedAt)/1e9);
        var colorsPhase=PaletteText.flowingPhase(scene.seconds+elapsed,scene.colorPeriod,1,false,0);
        for(Group group:scene.groups){
            List<WorldSubtitleRenderer.Line> lines=group.lines.stream().map(line->{
                var motion=line.motion();var moving=new WorldSubtitleRenderer.Motion(motion.mode(),motion.seconds()+elapsed,motion.amount(),motion.speed());
                return new WorldSubtitleRenderer.Line(line.text(),line.alpha(),line.scale(),line.rise(),line.progress(),line.layoutText(),line.shift(),line.rotation(),moving,line.futureOpacity());
            }).toList();
            WorldSubtitleRenderer.draw(renderer,new Vec3(group.x,group.y,group.z),group.yaw,group.pitch,group.roll,lines,group.letterHeight,group.maxWidth,group.gap,
                    group.walls,group.minecraft,group.glow,group.radius,new PaletteText.Style(group.palette,colorsPhase));
        }
    }
    private static boolean valid(Scene scene){
        if(scene==null||scene.dimension==null||scene.dimension.length()>256||!scene.dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                ||!finite(scene.seconds,0,1e12)||!finite(scene.colorPeriod,.05,3600)||scene.groups==null||scene.groups.size()>4)return false;
        int characters=0;
        for(Group g:scene.groups){
            if(g==null||!finite(g.x,-32000000,32000000)||!finite(g.y,-32000000,32000000)||!finite(g.z,-32000000,32000000)
                    ||!finite(g.yaw,-1e6,1e6)||!finite(g.pitch,-1e6,1e6)||!finite(g.roll,-1e6,1e6)||g.lines==null||g.lines.size()>12
                    ||!finite(g.letterHeight,.01,4)||!finite(g.maxWidth,.1,128)||!finite(g.gap,0,8)||!finite(g.glow,0,1)||!finite(g.radius,0,32)
                    ||!List.of("palette_white","palette_rainbow","palette_trans").contains(g.palette))return false;
            for(var line:g.lines){
                if(line==null||line.text()==null||line.layoutText()==null||(characters+=line.text().length()+line.layoutText().length())>16384
                        ||!finite(line.alpha(),0,1)||!finite(line.scale(),0,16)||!finite(line.rise(),-100,100)||!finite(line.progress(),-1,1)
                        ||!finite(line.shift(),-100,100)||!finite(line.rotation(),-1e6,1e6)||!finite(line.futureOpacity(),0,1)||line.motion()==null
                        ||!finite(line.motion().seconds(),0,1e12)||!finite(line.motion().amount(),0,32)||!finite(line.motion().speed(),0,100)
                        ||!List.of("lyrics_none","lyrics_float","lyrics_fade","lyrics_slide","lyrics_spring","lyrics_word","lyrics_typewriter","lyrics_shake","lyrics_wave","lyrics_bounce","lyrics_spin").contains(line.motion().mode()))return false;
            }
        }return true;
    }
    private static boolean finite(double value,double min,double max){return Double.isFinite(value)&&value>=min&&value<=max;}
}
