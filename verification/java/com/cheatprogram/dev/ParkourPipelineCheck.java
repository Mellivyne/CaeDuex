package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;
import java.util.concurrent.FutureTask;

/** Real compound replay with oversized scan estimates, repeated corrections and late delivery. */
public final class ParkourPipelineCheck {
    static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        Parameters p=Parameters.defaults();
        for(boolean modern:new boolean[]{false,true}){
            var world=new ParkourSpecialCheck.World().block(-6,-1,-.5,62,1,1);world.modern=modern;
            for(int i=0;i<12;i++)world.block(i*4,0,-.5,3,3,1);
            State start=ParkourSpecialCheck.start(-5.11,0,.07,-90);
            Goal goal=new Goal(49,0,0,.3,.2);
            var slow=new ParkourCompoundPriorityCheck.SlowGuideTerrain(world);
            Result route=ParkourPlanner.search(slow,p,start,List.of(goal),ParkourSpecialCheck.O);
            require(route.reason().equals("PREPARED_COMPOUND_PREFIX")&&route.actions().size()>=50,"Publish a validated local prefix promptly");
            require(route.expanded()<=30000&&slow.expensiveColumns==0,"No redundant guide/100k beam after a prepared long prefix");
            int anchor=ParkourPrediction.anchor(route.states(),0,160);
            require(anchor<route.actions().size()-12,"A large latency estimate cannot select terminal braking");
            State requested=route.states().get(anchor);
            require(requested.onGround()&&!route.states().get(anchor+1).onGround(),"Prefer a real contact jump");
            Terrain snapshot=ParkourLongCompactCheck.capture(world,start,goal);
            require(!ParkourPrediction.covered(snapshot,p,requested,goal)&&ParkourPrediction.localCovered(snapshot,p,requested,goal),"Reuse actual forward capture room before rescanning a distant goal");
            State edge=new State(22,0,0,0,0,0,-90,true,false,0,0);
            require(!ParkourPrediction.localCovered(snapshot,p,edge,goal),"Refresh before the next window leaves captured geometry");

            FutureTask<Integer> inFlight=new FutureTask<>(()->1);
            Result suffix=route;int shifted=anchor;
            for(int correction=0;correction<4;correction++){
                // The production recovery path removes executed inputs and
                // replays from a small observed position error.
                State at=suffix.states().get(5);
                State observed=new State(at.x(),at.y(),at.z()+.00001,at.vx(),at.vy(),at.vz(),at.yaw(),at.onGround(),at.sprinting(),at.jumpCooldown(),at.tick(),at.fallDistance(),at.sprintBlocked());
                suffix=ParkourRecovery.remaining(suffix,5,world,p,observed);
                require(suffix!=null,"Corrected remaining native inputs remain safe");shifted-=5;
                if(!ParkourPrediction.preserve(requested,suffix.states().get(shifted),0,0))inFlight.cancel(true);
                require(!inFlight.isCancelled(),"Small corrections must not starve the existing prediction worker");
            }
            inFlight.run();require(inFlight.isDone(),"The retained worker can finish");
            require(!ParkourPrediction.preserve(requested,requested,0,1),"Changed goal coverage invalidates a prediction");

            Result candidate=new Result(route.states().subList(anchor,route.states().size()),route.actions().subList(anchor,route.actions().size()),false,0,0,"TEST_CONTINUATION");
            int late=3;State actual=candidate.states().get(late);
            Result caught=ParkourPrediction.catchUp(candidate,late,actual);
            require(caught!=null&&caught.actions().size()==candidate.actions().size()-late,"A late compatible result retains only unexecuted controls");
            Result adopted=ParkourContinuity.rebase(caught,world,p,actual);
            require(adopted!=null,"Late continuation passes full collision replay");
            State next=ParkourPhysics.step(world,p,actual,adopted.actions().getFirst());
            require(next!=null&&next.equals(candidate.states().get(late+1)),"The next native action executes without an inserted idle tick");
            State wrong=new State(actual.x()+.5,actual.y(),actual.z(),actual.vx(),actual.vy(),actual.vz(),actual.yaw(),actual.onGround(),actual.sprinting(),actual.jumpCooldown(),actual.tick());
            require(ParkourPrediction.catchUp(candidate,late,wrong)==null,"Do not splice a result whose prefix was not actually followed");
            System.out.println("PIPELINE_PASS modern="+modern+" anchor="+anchor+" tail="+(route.actions().size()-anchor)+" corrections=4 lateTicks=3 nodes="+route.expanded()+" searchMs="+route.elapsedNanos()/1_000_000.0);
        }
    }
}
