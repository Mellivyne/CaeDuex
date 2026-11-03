package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Rolling-plan handoff checks use production physics and contain no artificial idle ticks. */
public final class ParkourContinuityCheck {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static State start(){return new State(0,0,0,0,-.0784000015258789,0,0,true,false,0,0);}
    static State shifted(State s,double x,double vx){return new State(s.x()+x,s.y(),s.z(),s.vx()+vx,s.vy(),s.vz(),s.yaw(),s.onGround(),s.sprinting(),s.jumpCooldown(),s.tick(),s.fallDistance());}
    static final Parameters PHYSICS=Parameters.defaults();
    static final Options OPTIONS=new Options(180,500000,128,3_000_000_000L,true,true,true,25);

    public static void main(String[] args){
        var points=new ArrayList<Goal>();for(int i=1;i<=6;i++)points.add(new Goal(0,0,i*4,.45,.2));
        List<Goal> window=ParkourCourse.window(points,0);
        require(window.equals(points.subList(0,3)),"Bounded three-point lookahead");
        require(ParkourCourse.window(points,5).equals(List.of(points.get(5))),"Short final window");
        require(ParkourCourse.window(points,6).isEmpty(),"Completed window is empty");
        points.add(new Goal(0,0,28,.45,.2));require(window.size()==3,"Worker window does not mutate with settings");points.removeLast();
        State atLater=new State(0,0,8,0,-.0784,.2,0,true,false,0,0);
        require(ParkourCourse.advance(points,0,atLater)==0,"Future destination cannot skip current destination");
        Goal duplicate=new Goal(0,0,0,.3,.2);
        require(ParkourCourse.advance(List.of(duplicate,duplicate,points.get(0)),0,start())==2,"Duplicate points require no extra stop tick");
        State airborne=new State(0,0,4,0,0,.2,0,false,false,0,0);
        require(ParkourCourse.advance(points,0,airborne)==0,"Midair crossing is not grounded waypoint arrival");
        Goal narrow=new Goal(0,0,0,.1,.2);
        State left=new State(-.4,0,0,0,-.0784,0,0,true,false,0,0),right=new State(.4,0,0,0,-.0784,0,0,true,false,0,1);
        require(ParkourCourse.advance(List.of(narrow),0,left,right)==1,"A grounded stride crossing between sample positions is recorded");
        require(ParkourCourse.advance(List.of(narrow),0,new State(-.4,0,0,0,0,0,0,false,false,0,0),right)==0,"An airborne arrival does not invent prior ground contact");
        require(ParkourCourse.advance(List.of(narrow),0,left,new State(.4,1,0,0,0,0,0,true,false,0,1))==0,"Different-height crossing is not a flat grounded stride");
        require(ParkourCourse.advance(List.of(narrow),0,new State(-4,0,0,0,0,0,0,true,false,0,0),new State(4,0,0,0,0,0,0,true,false,0,1))==0,"An external long correction cannot skip checkpoints");
        require(ParkourCourse.advance(List.of(new Goal(.25,0,0,.05,.2),new Goal(-.25,0,0,.05,.2)),0,left,right)==1,"Swept checkpoint crossings retain temporal order");

        var terrain=new ParkourPhysicsCheck.TerrainFixture().block(-6,-1,-6,12,1,42);
        Result first=ParkourPlanner.search(terrain,PHYSICS,start(),window,OPTIONS);
        require(first.reached(),"First rolling window completes: "+first.reason());
        int reached=0;List<Integer> arrivals=new ArrayList<>();
        for(int tick=1;tick<first.states().size();tick++){
            State current=first.states().get(tick);int next=ParkourCourse.advance(points,reached,current);
            if(next>reached){
                arrivals.add(tick);
                if(next<3){
                    require(Math.hypot(current.vx(),current.vz())>.08,"Intermediate waypoint retains motion");
                    Action incoming=first.actions().get(tick-1),outgoing=first.actions().get(tick);
                    require(incoming.forward()!=0||incoming.strafe()!=0,"Intermediate arrival has movement input");
                    require(outgoing.forward()!=0||outgoing.strafe()!=0,"Intermediate departure has movement input");
                }
            }
            reached=next;
        }
        require(reached==3&&arrivals.size()==3,"All three points reached in order");
        State boundary=first.states().getLast();
        require(Math.hypot(boundary.vx(),boundary.vz())>.08,"Prefetch endpoint retains actual momentum");
        Result ahead=ParkourPlanner.search(terrain,PHYSICS,boundary,ParkourCourse.window(points,reached),OPTIONS);
        require(ahead.reached(),"Second rolling window completes: "+ahead.reason());
        require(ahead.states().getFirst().equals(boundary),"Prefetch begins at exact previous endpoint including velocity/cooldown");
        Result rebased=ParkourContinuity.rebase(ahead,terrain,PHYSICS,boundary);
        require(rebased!=null&&rebased.actions().equals(ahead.actions()),"Rebase never inserts neutral ticks");
        require(rebased.states().equals(ahead.states()),"Exact boundary replay is identical");
        require(ParkourContinuity.reachedThrough(points,reached,rebased.states())==6,"Second window advances remaining points");
        State firstAfter=ParkourPhysics.step(terrain,PHYSICS,boundary,rebased.actions().getFirst());
        require(firstAfter.equals(rebased.states().get(1))&&firstAfter.tick()==boundary.tick()+1,"Immediate handoff uses next tick, no waiting frame");
        require(ParkourContinuity.compatible(boundary,shifted(boundary,.01,.005),.04,.03),"Small replayable drift accepted");
        require(!ParkourContinuity.compatible(boundary,shifted(boundary,.4,0),.04,.03),"Stale position rejected");
        require(!ParkourContinuity.compatible(boundary,shifted(boundary,0,.2),.04,.03),"Stale velocity rejected");
        terrain.unsafe=true;require(ParkourContinuity.rebase(ahead,terrain,PHYSICS,boundary)==null,"Changed unsafe terrain invalidates continuation");
        // Replaying actions alone is insufficient: drift may leave a formerly
        // safe endpoint sliding off the edge after the actions are exhausted.
        var ledge=new ParkourPhysicsCheck.TerrainFixture().block(-.5,-1,-.5,1,1,1);ledge.friction=.98;
        State drift=new State(0,0,.45,0,-.0784,.05,0,true,false,0,0);
        require(ParkourContinuity.rebase(new Result(List.of(drift),List.of(),true,0,0,"endpoint"),ledge,PHYSICS,drift)==null,"Unsafe release after drift rejected");
        System.out.println("CONTINUITY_PASS bounded immutable lookahead, grounded order, moving intermediate arrivals, exact-velocity immediate handoff, stale/unsafe result rejection; arrivals="+arrivals);
    }
}
