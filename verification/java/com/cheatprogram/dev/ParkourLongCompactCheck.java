package com.cheatprogram.dev;
import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** A distant final checkpoint and explicit contact checkpoints share long compounds. */
public final class ParkourLongCompactCheck {
    static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    static Terrain capture(ParkourSpecialCheck.World world,State start,Goal target){
        double[] c=ParkourPrediction.scanCenter(start,target,18);Box scan=new Box(c[0]-18,-5,c[2]-18,c[0]+18,8,c[2]+18);
        return new Terrain(){
            public List<Box> collisions(Box q){return world.blocks.stream().filter(b->b.intersects(scan)&&b.intersects(q)).toList();}
            public boolean known(Box q){return q.minX()>=scan.minX()&&q.maxX()<scan.maxX()&&q.minZ()>=scan.minZ()&&q.maxZ()<scan.maxZ()&&q.minY()>=-5&&q.maxY()<8;}
            public boolean safe(Box q){return known(q);}public Surface surface(double x,double y,double z){return world.surface(x,y,z);}public boolean modernMovement(){return world.modern;}
        };
    }
    static void check(boolean modern,int count,boolean intermediate){
        var world=new ParkourSpecialCheck.World().block(-6,-1,-.5,count*4+12,1,1);world.modern=modern;
        for(int i=0;i<count;i++)world.block(i*4,0,-.5,3,3,1);
        State live=ParkourSpecialCheck.start(-5.11,0,.07,-90);
        var goals=new ArrayList<Goal>();if(intermediate)for(int i=0;i<count-1;i++)goals.add(new Goal(i*4+3.5,0,0,.3,.2,new Box(i*4+3,-1,-.5,i*4+4,0,.5)));
        goals.add(new Goal((count-1)*4+5,0,0,.3,.2));int reached=0,windows=0,ticks=0;
        for(;windows<20&&reached<goals.size();windows++){
            var remaining=ParkourCourse.window(goals,reached,ParkourPrediction.WINDOW);
            Terrain snapshot=capture(world,live,ParkourPrediction.scanGoal(remaining,0));
            Result route=ParkourPlanner.search(snapshot,Parameters.defaults(),live,remaining,ParkourNeoChainCheck.O);
            System.out.println("LONG_WINDOW walls="+count+" checkpoints="+intermediate+" index="+windows+" start="+String.format(java.util.Locale.ROOT,"%.3f/%.3f",live.x(),live.z())+" actions="+route.actions().size()+" reason="+route.reason());
            require(!route.actions().isEmpty(),"Long compound stopped: modern="+modern+" walls="+count+" checkpoints="+intermediate+" window="+windows+" reason="+route.reason());
            int handoff=ParkourPrediction.anchor(route.states(),0,35);
            int stop=route.reached()?route.actions().size():handoff;
            // Exact future contact is planned before executing its prefix, as
            // the production overlap worker does; never stop in the gap first.
            if(handoff<route.actions().size()){
                int nextGoal=ParkourContinuity.reachedThrough(goals,reached,route.states().subList(0,handoff+1));
                if(nextGoal<goals.size()){
                    State nextStart=route.states().get(handoff);
                    Result ahead=ParkourPlanner.search(capture(world,nextStart,ParkourPrediction.scanGoal(goals,nextGoal)),Parameters.defaults(),nextStart,ParkourCourse.window(goals,nextGoal,ParkourPrediction.WINDOW),ParkourNeoChainCheck.O);
                    if(!ahead.actions().isEmpty()&&ParkourPrediction.improves(route,handoff,ParkourContinuity.reachedThrough(goals,reached,route.states()),ahead,ParkourContinuity.reachedThrough(goals,nextGoal,ahead.states())))stop=handoff;
                }
            }
            require(stop>0,"Rolling handoff must move forward");
            for(int i=0;i<stop;i++){
                State next=ParkourPhysics.step(world,Parameters.defaults(),live,route.actions().get(i));
                require(next!=null&&next.equals(route.states().get(i+1)),"Collision-checked native replay");
                reached=ParkourCourse.advance(goals,reached,live,next);live=next;ticks++;
            }
        }
        require(reached==goals.size()&&goals.getLast().reached(live),"Long compound did not finish");
        System.out.println("LONG_COMPACT modern="+modern+" walls="+count+" checkpoints="+intermediate+" windows="+windows+" ticks="+ticks);
    }
    public static void main(String[] args){for(boolean modern:new boolean[]{false,true})for(int count:new int[]{5,8,12})for(boolean intermediate:new boolean[]{false,true})check(modern,count,intermediate);System.out.println("LONG_COMPACT_PASS 12 long and multi-checkpoint courses");}
}
