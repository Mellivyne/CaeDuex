package com.cheatprogram.dev;
import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import java.util.*;

/** Fluid under thin solids must not invalidate dry motion; true fluid entry still fails. */
public final class ParkourThinSupportCheck {
    static final Parameters P=Parameters.defaults();
    static void require(boolean v,String m){if(!v)throw new AssertionError(m);}
    static final class Pad implements Terrain {
        public List<Box> collisions(Box q){Box pad=new Box(-.45,0,-.45,.45,.09375,.45);return pad.intersects(q)?List.of(pad):List.of();}
        public boolean known(Box q){return true;}
        public boolean safe(Box q){return q.minY()>=0;}
        public Surface surface(double x,double y,double z){return new Surface(.6,y>=0);}
    }
    static final class Ladder implements Terrain {
        final List<Box> boxes=List.of(new Box(-4,-1,-4,4,0,4),new Box(-2,0,.9,2,5,1.9),new Box(-.5,0,.775,.5,3,.9));
        public List<Box> collisions(Box q){return boxes.stream().filter(b->b.intersects(q)).toList();}
        public boolean known(Box q){return true;}
        public boolean safe(Box q){return true;}
        public Surface surface(double x,double y,double z){return new Surface(.6,true);}
        public boolean climbable(double x,double y,double z){return Math.abs(x)<.5&&z>=0&&z<1&&y>=0&&y<3;}
    }
    public static void main(String[] args){
        Pad pad=new Pad();State stand=new State(0,.09375,0,0,-.0784000015,0,0,true,false,0,0);
        State resting=ParkourPhysics.step(pad,P,stand,new Action(0,0,0,false,false));
        require(resting!=null&&resting.onGround()&&resting.y()==.09375,"Water below lily pad is not body contact");
        State falling=new State(0,.2,0,0,-.5,0,0,false,false,0,0);
        State landed=ParkourPhysics.step(pad,P,falling,new Action(0,0,0,false,false));
        require(landed!=null&&landed.onGround()&&Math.abs(landed.y()-.09375)<1e-9,"Raw fall sweep clipped before water");
        State wet=new State(1,.2,0,0,-.5,0,0,false,false,0,0);
        require(ParkourPhysics.step(pad,P,wet,new Action(0,0,0,false,false))==null,"Actual fluid entry still rejected");
        State jumping=ParkourPhysics.step(pad,P,stand,new Action(0,0,0,true,false));
        require(jumping!=null&&jumping.y()>.5,"Jump off thin support");
        Ladder ladder=new Ladder();State climb=new State(0,1,.475,.4,-.6,0,0,false,false,0,0,2.5);
        State next=ParkourPhysics.step(ladder,P,climb,new Action(0,0,0,true,false));
        require(next!=null&&Math.abs(next.x()-.15f)<1e-7,"Climbing horizontal speed cap");
        require(Math.abs(next.y()-(1-.15f))<1e-7,"Climbing downward speed cap");
        require(Math.abs(next.vy()-(.2-.08)*.98f)<1e-9&&next.fallDistance()<.2,"Climb impulse and reset fall distance");
        State moving=new State(0,.5,.475,0,-.0784,0,0,false,false,0,0);
        for(int i=0;i<20;i++){moving=ParkourPhysics.step(ladder,P,moving,new Action(0,1,0,true,false));require(moving!=null,"Climbing remains collision-safe");}
        require(moving.y()>2.5,"Holding native jump climbs the ladder");
        var route=ParkourPlanner.search(ladder,P,new State(0,0,.475,0,-.0784,0,0,true,false,0,0),
            List.of(new ParkourPlanner.Goal(0,3,.8375,.3,.2)),new ParkourPlanner.Options(100,300000,96,3_000_000_000L,true,true,true,45));
        require(route.reached(),"Planner reaches ladder's solid top: "+route.reason());
        System.out.println("THIN_SUPPORT_PASS dry lily rest/landing/jump, fluid rejection, ladder clamp/impulse/fall reset/climb");
    }
}
