package com.cheatprogram.parkour;

import com.cheatprogram.parkour.ParkourPhysics.State;
import com.cheatprogram.parkour.ParkourPhysics.Terrain;
import com.cheatprogram.parkour.ParkourPhysics.Parameters;
import com.cheatprogram.parkour.ParkourPlanner.Goal;
import com.cheatprogram.parkour.ParkourPlanner.Result;
import java.util.List;

/** Scheduling/replay bounds shared by the controller and deterministic checks. */
public final class ParkourPrediction {
    public static final int WINDOW=8;
    public static final int SCAN_CELLS=8192;
    private ParkourPrediction(){}
    public static Goal scanGoal(List<Goal> goals,int index){
        int last=Math.min(goals.size()-1,index+WINDOW-1),chosen=index;
        while(chosen<last&&goals.get(chosen).region()!=null)chosen++;
        return goals.get(chosen);
    }
    /** Keep retreat space behind the actor while capturing more of a distant
     * compound in front before its first takeoff. The cube size is unchanged. */
    public static double[] scanCenter(State start,Goal target,int radius){
        double dx=target.x()-start.x(),dz=target.z()-start.z(),length=Math.hypot(dx,dz);
        double shift=Math.min(radius*.5,Math.max(0,length-radius+3));
        return length<1e-6?new double[]{start.x(),start.y(),start.z()}
            :new double[]{start.x()+dx/length*shift,start.y(),start.z()+dz/length*shift};
    }
    public static int leadTicks(int radius,int height,double searchMillis){
        long cells=(radius*2L+3)*(radius*2L+3)*(height*2L+1);
        return (int)Math.max(16,Math.min(100,(cells+SCAN_CELLS-1)/SCAN_CELLS+(long)Math.ceil(searchMillis/50)+10));
    }
    public static int anchor(int current,int actions,int lead){return Math.min(actions,current+Math.max(1,lead));}
    /** Keep a prepared hop/edge-takeoff/side-wrap intact during early handoff.
     * The transient grounded landing immediately followed by another jump is
     * not a new standing start for the next independent search. */
    public static int anchor(List<State> states,int current,int lead){
        int end=states.size()-1;
        // Long scan estimates must not push the handoff into terminal braking.
        // Start the worker now, using a contact in the first half of this suffix;
        // leave the remaining verified inputs available if it finishes late.
        int desired=anchor(current,end,Math.min(lead,Math.max(8,(end-current)/2)));
        for(int i=desired;i+2<=end;i++){
            State contact=states.get(i);
            // A verified contact jump is a useful future starting state. Waiting
            // for three grounded ticks postpones every long-neo continuation
            // until braking in a gap where a fresh run-up is impossible.
            if(i>0&&contact.onGround()&&!states.get(i-1).onGround()&&!states.get(i+1).onGround()
                    &&contact.jumpCooldown()==0&&!contact.sprintBlocked()
                    &&Math.hypot(contact.vx(),contact.vz())>.15)return i;
        }
        for(int i=desired;i+2<=end;i++)if(states.get(i).onGround()&&states.get(i+1).onGround()&&states.get(i+2).onGround())return i;
        return end;
    }
    /** A cached capture can plan the next local window even if the user's sole
     * final checkpoint is outside it. Require forward body room, not just the
     * new start, so subsequent windows still refresh before the capture edge. */
    public static boolean localCovered(Terrain terrain,Parameters p,State start,Goal target){
        if(covered(terrain,p,start,target))return true;
        if(!terrain.known(ParkourPhysics.bounds(start,p).inflate(3)))return false;
        double dx=target.x()-start.x(),dz=target.z()-start.z(),distance=Math.hypot(dx,dz);
        double ratio=distance<1e-6?0:Math.min(1,10/distance);
        State next=new State(start.x()+dx*ratio,start.y(),start.z()+dz*ratio,0,0,0,start.yaw(),true,false,0,0);
        return terrain.known(ParkourPhysics.bounds(next,p).inflate(2));
    }
    public static boolean heightCovered(Terrain terrain,Parameters p,State start,Goal target){
        State column=new State(start.x(),target.y(),start.z(),0,0,0,start.yaw(),true,false,0,0);
        return terrain.known(ParkourPhysics.bounds(column,p));
    }
    /** Cover the actual next checkpoint's elevation, bounded by the dimension
     * itself rather than a waypoint height-difference threshold. */
    public static int[] verticalBounds(double centerY,double targetY,int padding,int worldMin,int worldHeight){
        int low=(int)Math.floor(Math.min(centerY-padding,targetY-3));
        int high=(int)Math.floor(Math.max(centerY+padding,targetY+4))+1;
        low=Math.max(worldMin,low);high=Math.min(worldMin+worldHeight+8,high);
        return new int[]{low,Math.max(low+1,high)};
    }
    public static int planningHorizon(int requested,Parameters physics,State start,Goal next){
        if(!physics.allowDamagingFalls()||next.y()>=start.y())return requested;
        double y=start.y(),velocity=Math.min(0,start.vy());int ticks=0;
        while(y>next.y()&&ticks<1180){y+=velocity;velocity=(velocity-physics.gravity())*.98f;ticks++;}
        return Math.max(requested,Math.min(1200,ticks+20));
    }
    /** Keep an already running worker after a small replay correction. Its
     * original start is retained; adoption replays from the observed state. */
    public static boolean preserve(State requested,State corrected,int requestedPoint,int correctedPoint){
        return requestedPoint==correctedPoint&&ParkourContinuity.compatible(requested,corrected,.18,.08)
            &&requested.jumpCooldown()==corrected.jumpCooldown()&&requested.sprintBlocked()==corrected.sprintBlocked();
    }
    /** A result arriving after its contact tick is usable only when the actor
     * actually followed its prefix. Never drop unexecuted actions blindly. */
    public static Result catchUp(Result candidate,int elapsed,State actual){
        if(candidate==null||elapsed<0||elapsed>=candidate.actions().size())return null;
        State expected=candidate.states().get(elapsed);
        if(!ParkourContinuity.compatible(expected,actual,.18,.08)
            ||expected.jumpCooldown()!=actual.jumpCooldown()||expected.sprintBlocked()!=actual.sprintBlocked())return null;
        return new Result(candidate.states().subList(elapsed,candidate.states().size()),
            candidate.actions().subList(elapsed,candidate.actions().size()),candidate.reached(),
            candidate.expanded(),candidate.elapsedNanos(),candidate.reason());
    }
    /** Reusing a snapshot containing only the new start repeatedly walks a
     * distant-target plan up to stale scan boundaries. Refresh around the
     * predicted start when the active destination is still outside it. */
    public static boolean covered(Terrain terrain,Parameters physics,State start,Goal target){
        State destination=new State(target.x(),target.y(),target.z(),0,0,0,start.yaw(),true,false,0,0);
        return terrain.known(ParkourPhysics.bounds(start,physics).inflate(3))
            &&terrain.known(ParkourPhysics.bounds(destination,physics));
    }
    /** Early prediction must improve the still-valid suffix, not replace it
     * with a slower repeat of a wall approach already in progress. */
    public static boolean improves(Result current,int index,int currentReached,Result candidate,int candidateReached){
        if(current==null||index>=current.actions().size())return true;
        if(candidateReached!=currentReached)return candidateReached>currentReached;
        if(current.reached()||candidate.reached())return candidate.reached()
            &&(!current.reached()||candidate.actions().size()<current.actions().size()-index);
        State start=current.states().get(index),end=current.states().getLast(),next=candidate.states().getLast();
        double dx=end.x()-start.x(),dz=end.z()-start.z(),length=Math.hypot(dx,dz);
        if(length<.1)return true;
        double progress=((next.x()-start.x())*dx+(next.z()-start.z())*dz)/length;
        return progress>=length-.25;
    }
    public static int leadTicks(int radius,int height,double searchMillis,int observedTicks){
        return Math.min(200,Math.max(leadTicks(radius,height,searchMillis),observedTicks+8));
    }
    /** Already fully simulated by the worker; ordinary adoption need not replay the whole route. */
    public static boolean exactStart(State planned,State actual){
        return ParkourContinuity.compatible(planned,actual,.0001,.00001)
            &&planned.jumpCooldown()==actual.jumpCooldown()&&planned.sprintBlocked()==actual.sprintBlocked()&&Math.abs(planned.fallDistance()-actual.fallDistance())<.001;
    }
}
