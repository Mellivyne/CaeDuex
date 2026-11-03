package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Wall-wrap and constrained landing fixtures; no Minecraft session is opened. */
public final class ParkourWallCheck {
    private static final Parameters P=Parameters.defaults();
    private static final Options O=new Options(100,100000,96,350_000_000L,true,true,true,25);
    private record Case(String name,ParkourPhysicsCheck.TerrainFixture terrain,State start,List<Goal> goals,boolean reachable,boolean requireAirSide){}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static State start(double x,double z,float yaw){return new State(x,0,z,0,-.0784000015258789,0,yaw,true,false,0,0);}
    private static Goal goal(double x,double z){return new Goal(x,0,z,.3,.2);}
    private static ParkourPhysicsCheck.TerrainFixture floor(){return new ParkourPhysicsCheck.TerrainFixture().block(-12,-1,-8,24,1,32);}
    private static List<Case> cases(){
        return List.of(
            new Case("neo-two-high",new ParkourPhysicsCheck.TerrainFixture().block(-3,-1,-.5,6,1,1).block(-.5,0,-.5,1,2,1),start(-2,0,-90),List.of(goal(2,0)),true,true),
            new Case("neo-three-high",new ParkourPhysicsCheck.TerrainFixture().block(-3,-1,-.5,6,1,1).block(-.5,0,-.5,1,3,1),start(-2,0,-90),List.of(goal(2,0)),true,true),
            new Case("double-width-neo",new ParkourPhysicsCheck.TerrainFixture().block(-6,-1,-.5,12,1,1).block(-1,0,-.5,2,3,1),start(-4,0,-90),List.of(goal(4,0)),true,true),
            new Case("triple-width-neo-drop-1",new ParkourPhysicsCheck.TerrainFixture().block(-6,-1,-.5,7.5,1,1).block(1.5,-2,-.5,4.5,1,1).block(-1.5,0,-.5,3,3,1),start(-4,0,-90),List.of(new Goal(4,-1,0,.3,.2)),true,true),
            new Case("gap-to-wall-platform",new ParkourPhysicsCheck.TerrainFixture().block(-.5,-1,-2,1,1,3).block(-1.5,-1,3,3,1,7).block(-.5,0,5,1,3,1),start(0,0,0),List.of(goal(0,4),goal(0,8)),true,false),
            new Case("double-offset-wall",floor().block(-10,0,3,11,3,1).block(2.5,0,3,7.5,3,1).block(-10,0,7,7.5,3,1).block(-1,0,7,11,3,1),start(0,0,0),List.of(goal(0,10)),true,false),
            new Case("triple-offset-wall",floor().block(-10,0,3,11,3,1).block(2.5,0,3,7.5,3,1).block(-10,0,7,7.5,3,1).block(-1,0,7,11,3,1).block(-10,0,11,11,3,1).block(2.5,0,11,7.5,3,1),start(0,0,0),List.of(goal(0,14)),true,false),
            new Case("low-ceiling-narrow-landing",new ParkourPhysicsCheck.TerrainFixture().block(-1,-1,-2,2,1,4).block(-.5,-1,3,1,1,3).block(-1,2,-1,2,1,6),start(0,0,0),List.of(goal(0,4.5)),true,false),
            new Case("enclosed-three-high",floor().block(-2,0,3,4,3,1).block(-2,0,7,4,3,1).block(-2,0,3,1,3,5).block(1,0,3,1,3,5),start(0,0,0),List.of(goal(0,5)),false,false)
        );
    }
    public static void main(String[] args){
        boolean report=Arrays.asList(args).contains("--report");List<String> failed=new ArrayList<>();
        for(boolean modern:new boolean[]{false,true})for(Case c:cases()){
            c.terrain().modern=modern;State live=c.start();int checkpoint=0,ticks=0,windows=0;boolean airSide=false;double maxY=live.y(),maxSide=0;String reason="";
            for(;windows<12&&checkpoint<c.goals().size();windows++){
                Result result=ParkourPlanner.search(c.terrain(),P,live,ParkourCourse.window(c.goals(),checkpoint),O);reason=result.reason();
                require(result.expanded()<=O.maxNodes(),c.name()+" node budget");
                int input=0;for(Action action:result.actions()){
                    State next=ParkourPhysics.step(c.terrain(),P,live,action);require(next!=null,c.name()+" invalid returned action");
                    Box body=ParkourPhysics.bounds(next,P).inflate(-1e-6);require(c.terrain().collisions(body).isEmpty(),c.name()+" penetrated a wall");
                    State saved=result.states().get(++input);require(Math.abs(saved.x()-next.x())<1e-8&&Math.abs(saved.y()-next.y())<1e-8&&Math.abs(saved.z()-next.z())<1e-8,c.name()+" replay mismatch");
                    if(!next.onGround()&&Math.abs(next.z())>.79&&Math.abs(next.x())<.85)airSide=true;
                    maxY=Math.max(maxY,next.y());maxSide=Math.max(maxSide,Math.abs(next.z()));checkpoint=ParkourCourse.advance(c.goals(),checkpoint,next);live=next;ticks++;
                }
                if(result.actions().isEmpty())break;
            }
            boolean reached=checkpoint==c.goals().size();
            System.out.printf(Locale.ROOT,"WALL %s modern=%s reached=%s windows=%d ticks=%d airSide=%s maxY=%.3f maxSide=%.3f reason=%s%n",c.name(),modern,reached,windows,ticks,airSide,maxY,maxSide,reason);
            if(reached!=c.reachable())failed.add(c.name()+" modern="+modern+" reached="+reached);
            if(reached&&c.requireAirSide()&&!airSide)failed.add(c.name()+" must wrap the wall in air");
            if(c.requireAirSide()&&maxY>1.26)failed.add(c.name()+" faked excess jump height");
        }
        if(!report)require(failed.isEmpty(),String.join("; ",failed));
        System.out.println(failed.isEmpty()?"WALL_PASS two/three-high air wraps, gap arrival, offset walls, ceiling/narrow landing, sealed target":"WALL_FAILURES "+String.join("; ",failed));
    }
}
