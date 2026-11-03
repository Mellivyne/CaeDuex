package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.google.gson.*;
import java.util.*;

/** Persistence, shape selection, and ordered per-leg control without a running game. */
public final class ParkourWaypointsCheck {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static State state(double x,double y,double z,boolean ground){return new State(x,y,z,0,-.0784,0,0,ground,false,0,0);}
    static Goal top(Box box,double x,double y,double z){return ParkourWaypointTarget.top(List.of(box),x,y,z);}
    static void close(double actual,double expected,String message){require(Math.abs(actual-expected)<1e-9,message+": "+actual);}
    public static void main(String[] args){
        ParkourWaypointSetting setting=new ParkourWaypointSetting();List<Goal> view=setting.get();
        for(int i=0;i<10000;i++)setting.append(new Goal(i*.25,64+i%3*.0625,i%17,.3,.2));
        require(setting.get()==view&&view.size()==10000,"Stable growable view with no 256-point cap");
        try{view.clear();throw new AssertionError("Mutable external view");}catch(UnsupportedOperationException expected){}
        String text=setting.save().toString();require(text.length()>8192,"Large route serialized beyond old text cap");
        ParkourWaypointSetting loaded=new ParkourWaypointSetting();loaded.load(JsonParser.parseString(text));
        int validatedRevision=loaded.revision();loaded.validateSerialized(new JsonArray());loaded.validateSerialized(JsonParser.parseString(text));
        require(loaded.revision()==validatedRevision&&loaded.get().equals(view),"Profile validation accepts waypoint arrays without changing live state");
        var surface=new ParkourWaypointSetting();surface.append(top(new Box(0,0,0,1,.1875,1),.5,.1875,.5));
        var surfaceCopy=new ParkourWaypointSetting();surfaceCopy.validateSerialized(surface.save());surfaceCopy.load(surface.save());
        require(surfaceCopy.get().equals(surface.get()),"Selected thin support face survives validation and exact round trip");
        for(String invalid:List.of("{}","true","[[0,64,0,0.3]]","[[\"0\",64,0,0.3,0.2]]","[[0,64,0,-1,1]]")){
            try{loaded.validateSerialized(JsonParser.parseString(invalid));throw new AssertionError("Invalid serialized waypoint accepted");}catch(IllegalArgumentException expected){}
        }
        require(loaded.get().equals(view)&&loaded.explicitlySet(),"Exact 10000-point round trip");
        int revision=loaded.revision();
        try{loaded.load(JsonParser.parseString("[[0,64,0,0.3,0.2],[0,0,0,-1,1]]"));throw new AssertionError("Invalid region accepted");}catch(IllegalArgumentException expected){}
        require(loaded.revision()==revision&&loaded.get().equals(view),"Invalid load must be atomic");
        loaded.set(loaded.get());require(loaded.get().equals(view),"Self-assignment preserves values");
        loaded.removeLast();require(loaded.get().size()==9999,"Undo one point");
        loaded.clear();require(loaded.get().isEmpty()&&loaded.explicitlySet(),"Clear prevents legacy re-import");
        loaded.reset();require(!loaded.explicitlySet(),"Profile reset permits migration");
        Goal a=new Goal(0,1,0,.3,.2),b=new Goal(5,1,0,.3,.2);
        List<Goal> points=List.of(a,a,b);
        require(ParkourCourse.leg(points,0).equals(List.of(a)),"Only the current waypoint submitted");
        require(ParkourCourse.reachedNext(points,0,state(5,1,0,true))==0,"Later waypoint cannot skip the current one");
        require(ParkourCourse.reachedNext(points,0,state(0,1,0,false))==0,"Airborne contact is not a safe stop");
        require(ParkourCourse.reachedNext(points,0,state(0,1,0,true))==1,"Overlapping points advance one at a time");
        require(ParkourCourse.reachedNext(points,1,state(0,1,0,true))==2,"Second overlapping point follows in order");
        require(ParkourCourse.leg(points,2).equals(List.of(b)),"Final leg isolates last point");
        require(ParkourCourse.reachedNext(points,3,state(5,1,0,true))==3,"Completed course remains completed");
        require(ParkourCourse.resume(3,3,true)==0,"Re-enabling a completed course begins a new run");
        require(ParkourCourse.resume(1,3,true)==1,"An interrupted unchanged course retains its active checkpoint");
        require(ParkourCourse.resume(1,3,false)==0,"A changed course or world starts at the beginning");
        close(top(new Box(0,0,0,1,1,1),.5,1,.5).y(),1,"Cube top");
        close(top(new Box(0,0,0,1,.5,1),.5,.5,.5).y(),.5,"Slab top");
        close(top(new Box(0,0,0,1,.1875,1),.4,.1875,.6).y(),.1875,"Horizontal trapdoor");
        close(top(new Box(0,0,0,.1875,1,1),.1,.7,.5).x(),.09375,"Open trapdoor thin top");
        close(top(new Box(.0625,0,.0625,.9375,.09375,.9375),.3,.09375,.7).y(),.09375,"Lily pad top");
        Goal ladder=top(new Box(0,0,0,1,1,.125),.5,.8,.06);
        close(ladder.z(),.0625,"Ladder component selectable");close(ladder.y(),1,"Ladder top");
        Goal stair=ParkourWaypointTarget.top(List.of(new Box(0,0,0,1,.5,1),new Box(0,.5,.5,1,1,1)),.5,.5,.25);
        close(stair.y(),.5,"Aim selects lower stair component");
        require(ParkourWaypointTarget.top(List.of(),0,0,0)==null,"Empty shape is not an invented cube");
        // Execute separate searches and re-start each with the previous real endpoint.
        var terrain=new ParkourPhysicsCheck.TerrainFixture().block(-10,-1,-10,30,1,30);
        State live=state(0,0,0,true);int reached=0;
        List<Goal> route=List.of(new Goal(0,0,4,.4,.2),new Goal(4,0,4,.4,.2),new Goal(4,0,0,.4,.2));
        while(reached<route.size()){
            Result leg=ParkourPlanner.search(terrain,Parameters.defaults(),live,ParkourCourse.leg(route,reached),new Options(100,300000,96,3_000_000_000L,true,true,true,45));
            require(leg.reached(),"Sequential leg "+reached+" failed: "+leg.reason());
            for(Action action:leg.actions()){live=ParkourPhysics.step(terrain,Parameters.defaults(),live,action);require(live!=null,"Replay safe");}
            int next=ParkourCourse.reachedNext(route,reached,live);require(next==reached+1,"Advance only after arrival");reached=next;
        }
        System.out.println("WAYPOINTS_PASS 10000-point persistence, atomic load, order, three independent legs, cube/slab/trapdoor/lily/ladder/stair selection");
    }
}
