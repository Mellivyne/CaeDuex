package com.cheatprogram.parkour;

import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Replay only unexecuted native inputs after observed drift, retaining safety checks. */
public final class ParkourRecovery {
    private ParkourRecovery(){}
    public static Result remaining(Result planned,int nextInput,Terrain terrain,Parameters physics,State actual){
        if(planned==null||nextInput<0||nextInput>=planned.actions().size())return null;
        Result suffix=new Result(planned.states().subList(nextInput,planned.states().size()),
                planned.actions().subList(nextInput,planned.actions().size()),planned.reached(),
                planned.expanded(),planned.elapsedNanos(),planned.reason());
        return ParkourContinuity.rebase(suffix,terrain,physics,actual);
    }
    /** Short receding-horizon steering before millimetre drift reaches a neo's
     * wall margin. Only native keys/yaw change; the whole suffix must replay. */
    public static Result track(Result planned,int nextInput,Terrain terrain,Parameters physics,State actual,float maxTurn){
        if(planned==null||nextInput<0||nextInput>=planned.actions().size())return null;
        List<Action> actions=new ArrayList<>(planned.actions().subList(nextInput,planned.actions().size()));
        List<State> states=new ArrayList<>(actions.size()+1);states.add(actual);State live=actual;
        int corrected=Math.min(6,actions.size());
        for(int i=0;i<actions.size();i++){
            Action requested=actions.get(i);double change=requested.yaw()-live.yaw();change-=Math.floor((change+180)/360)*360;
            float yaw=live.yaw()+(float)Math.max(-maxTurn,Math.min(maxTurn,change));
            Action original=new Action(yaw,requested.forward(),requested.strafe(),requested.jump(),requested.sprint()),best=original;
            State next=ParkourPhysics.step(terrain,physics,live,original);
            State target=planned.states().get(nextInput+i+1);
            if(i<corrected){
                double score=next==null?Double.POSITIVE_INFINITY:trackingError(next,target);
                for(float offset:new float[]{-4,-2,-1,1,2,4}){
                    float wanted=original.yaw()+offset;
                    double delta=wanted-live.yaw();delta-=Math.floor((delta+180)/360)*360;
                    if(Math.abs(delta)>maxTurn+.0001)continue;
                    Action candidate=new Action(wanted,original.forward(),original.strafe(),original.jump(),original.sprint());
                    State n=ParkourPhysics.step(terrain,physics,live,candidate);if(n==null)continue;
                    double value=trackingError(n,target)+offset*offset*.000000005;
                    if(value<score){score=value;best=candidate;next=n;}
                }
            }
            if(next==null)return null;
            actions.set(i,best);states.add(next);live=next;
        }
        if(!ParkourPlanner.safeRelease(terrain,physics,live))return null;
        double before=trackingError(actual,planned.states().get(nextInput));
        if(trackingError(states.get(corrected),planned.states().get(nextInput+corrected))>=before*.98)return null;
        return new Result(states,actions,planned.reached(),planned.expanded(),planned.elapsedNanos(),"TRACKED_"+planned.reason());
    }
    private static double trackingError(State a,State b){
        return square(a.x()-b.x())+square(a.y()-b.y())+square(a.z()-b.z())
            +3*(square(a.vx()-b.vx())+square(a.vy()-b.vy())+square(a.vz()-b.vz()));
    }
    private static double square(double value){return value*value;}
}
