package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Four three-block-high walls with one-block landing gaps; no private world data. */
public final class ParkourTightNeoCheck {
    static final Parameters P=Parameters.defaults();
    static final Options O=new Options(100,100000,96,350_000_000L,true,true,true,25);
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static State rotate(State s){return new State(-s.z(),s.y(),s.x(),-s.vz(),s.vy(),s.vx(),s.yaw()+90,s.onGround(),s.sprinting(),s.jumpCooldown(),s.tick(),s.fallDistance(),s.sprintBlocked());}
    static void check(boolean modern,int turn,boolean finalOnly,double speed,boolean blocked){
        var world=new ParkourSpecialCheck.World().block(-2,-1,-.5,16,1,1);
        for(int x=0;x<=6;x+=2)world.block(x,0,-.5,1,3,1);
        var points=new ArrayList<>(List.of(new Goal(1.51,0,.08,.3,.2),new Goal(3.42,0,-.04,.3,.2),new Goal(5.42,0,.077,.3,.2),new Goal(8.21,0,.054,.3,.2)));
        State state=new State(-1.367,0,.039,speed,-.0784000015258789,0,-90,true,speed>0,0,0,0,blocked);
        for(int i=0;i<turn;i++){
            var rotated=new ParkourSpecialCheck.World();for(Box b:world.blocks)rotated.blocks.add(new Box(-b.maxZ(),b.minY(),b.minX(),-b.minZ(),b.maxY(),b.maxX()));world=rotated;
            for(int j=0;j<points.size();j++){Goal g=points.get(j);points.set(j,new Goal(-g.z(),g.y(),g.x(),g.radius(),g.height()));}
            state=rotate(state);
        }
        world.modern=modern;List<Goal> goals=finalOnly?List.of(points.getLast()):points;int index=0,ticks=0,windows=0;
        String label="modern="+modern+" rotation="+(turn*90)+" finalOnly="+finalOnly+" speed="+speed+" blocked="+blocked;
        while(index<goals.size()&&windows++<16){
            Result result=ParkourPlanner.search(world,P,state,ParkourCourse.window(goals,index,8),O);
            require(!result.actions().isEmpty(),"Tight neo stopped / "+label+" / "+state);
            require(result.expanded()<=O.maxNodes()&&result.actions().size()<=O.horizon(),"Tight neo bounded search");
            for(int i=0;i<result.actions().size();i++){
                State next=ParkourPhysics.step(world,P,state,result.actions().get(i));require(next!=null,"Valid tight neo input");
                require(next.equals(result.states().get(i+1)),"Full approach/jump/collision replay");
                require(world.collisions(ParkourPhysics.bounds(next,P).inflate(-1e-6)).isEmpty(),"No wall penetration");
                index=ParkourCourse.advance(goals,index,state,next);state=next;ticks++;
            }
        }
        require(index==goals.size(),"Tight neo did not finish / "+label);
        System.out.println("TIGHT_NEO "+label+" ticks="+ticks+" windows="+windows);
    }
    static void overlapping(boolean modern,int turn,boolean finalOnly){
        var world=new ParkourSpecialCheck.World().block(-2,-1,-.5,16,1,1);
        for(int x=0;x<=6;x+=2)world.block(x,0,-.5,1,3,1);
        var points=new ArrayList<>(List.of(new Goal(1.51,0,.08,.3,.2),new Goal(3.42,0,-.04,.3,.2),new Goal(5.42,0,.077,.3,.2),new Goal(8.21,0,.054,.3,.2)));
        State live=new State(-1.367,0,.039,0,-.0784000015258789,0,-90,true,false,0,0);
        for(int i=0;i<turn;i++){
            var rotated=new ParkourSpecialCheck.World();for(Box b:world.blocks)rotated.blocks.add(new Box(-b.maxZ(),b.minY(),b.minX(),-b.minZ(),b.maxY(),b.maxX()));world=rotated;
            for(int j=0;j<points.size();j++){Goal g=points.get(j);points.set(j,new Goal(-g.z(),g.y(),g.x(),g.radius(),g.height()));}
            live=rotate(live);
        }
        world.modern=modern;List<Goal> goals=finalOnly?List.of(points.getLast()):points;int index=0,ticks=0,handoffs=0;
        Result route=ParkourPlanner.search(world,P,live,goals,O);
        while(index<goals.size()&&handoffs++<20){
            require(!route.actions().isEmpty(),"Tight overlapping route makes progress");
            int anchor=ParkourPrediction.anchor(route.states(),0,29);
            State predicted=route.states().get(anchor);
            int future=ParkourContinuity.reachedThrough(goals,index,route.states().subList(0,anchor+1));
            Result ahead=future<goals.size()?ParkourPlanner.search(world,P,predicted,ParkourCourse.window(goals,future,8),O):null;
            for(int i=0;i<anchor;i++){
                State next=ParkourPhysics.step(world,P,live,route.actions().get(i));require(next!=null,"Exact tight-route input");
                index=ParkourCourse.advance(goals,index,live,next);live=next;ticks++;
            }
            require(index==future&&ParkourPrediction.exactStart(predicted,live),"Actual checkpoint progress and motion agree at handoff");
            if(index==goals.size())break;
            require(ahead!=null&&!ahead.actions().isEmpty(),"Ready continuation cannot send the player back to an already visited checkpoint");
            route=ahead;
        }
        require(index==goals.size(),"Tight neos finish with early predictive handoffs");
        System.out.println("TIGHT_NEO_OVERLAP modern="+modern+" rotation="+(turn*90)+" finalOnly="+finalOnly+" ticks="+ticks+" handoffs="+handoffs);
    }
    public static void main(String[] args){
        int count=0;
        for(boolean modern:new boolean[]{false,true})for(int turn=0;turn<4;turn++)for(boolean finalOnly:new boolean[]{false,true})
            for(double speed:new double[]{0,.12})for(boolean blocked:new boolean[]{false,true}){check(modern,turn,finalOnly,speed,blocked);count++;}
        for(boolean modern:new boolean[]{false,true})for(int turn=0;turn<4;turn++)for(boolean finalOnly:new boolean[]{false,true}){overlapping(modern,turn,finalOnly);count++;}
        System.out.println("TIGHT_NEO_PASS "+count+" narrow landing, incoming momentum and collision-state replays");
    }
}
