package com.cheatprogram.parkour;

import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.ArrayList;
import java.util.List;

/** Pure validation/replay at a rolling-plan boundary; never inserts a braking tick. */
public final class ParkourContinuity {
    private ParkourContinuity(){}

    public static boolean compatible(State expected,State actual,double positionTolerance,double velocityTolerance){
        if(expected==null||actual==null||positionTolerance<0||velocityTolerance<0)return false;
        double position=Math.sqrt(square(expected.x()-actual.x())+square(expected.y()-actual.y())+square(expected.z()-actual.z()));
        double velocity=Math.sqrt(square(expected.vx()-actual.vx())+square(expected.vy()-actual.vy())+square(expected.vz()-actual.vz()));
        return expected.onGround()==actual.onGround()&&position<=positionTolerance&&velocity<=velocityTolerance;
    }

    /** Null means collision/safety changed. Goal coverage is verified by the caller. */
    public static Result rebase(Result planned,Terrain terrain,Parameters physics,State actual){
        if(planned==null||actual==null)return null;
        var states=new ArrayList<State>(planned.actions().size()+1);states.add(actual);State state=actual;
        for(Action action:planned.actions()){
            state=ParkourPhysics.step(terrain,physics,state,action);
            if(state==null)return null;
            states.add(state);
        }
        if(!ParkourPlanner.safeRelease(terrain,physics,state))return null;
        return new Result(states,planned.actions(),planned.reached(),planned.expanded(),planned.elapsedNanos(),planned.reason());
    }

    public static int reachedThrough(List<Goal> points,int index,List<State> states){
        State previous=null;for(State state:states){index=ParkourCourse.advance(points,index,previous,state);previous=state;}
        return index;
    }
    private static double square(double value){return value*value;}
}
