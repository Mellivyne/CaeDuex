package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

/** Native-input compounds must remain viable when coarse terrain queries are slow. */
public final class ParkourCompoundPriorityCheck {
    static final Parameters P=Parameters.defaults();
    static final Options O=new Options(100,100000,96,350_000_000L,true,true,true,25);
    static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    static final class SlowGuideTerrain implements Terrain {
        final ParkourSpecialCheck.World source;int expensiveColumns;
        SlowGuideTerrain(ParkourSpecialCheck.World source){this.source=source;}
        public List<Box> collisions(Box query){
            if(query.maxY()-query.minY()>8){expensiveColumns++;LockSupport.parkNanos(3_000_000L);}
            return source.collisions(query);
        }
        public boolean known(Box b){return source.known(b);}
        public boolean safe(Box b){return source.safe(b);}
        public Surface surface(double x,double y,double z){return source.surface(x,y,z);}
        public Surface groundSurface(Box b){return source.groundSurface(b);}
        public boolean modernMovement(){return source.modernMovement();}
    }
    static void protectedCompound(boolean modern){
        var world=new ParkourSpecialCheck.World().block(-6,-1,-.5,32,1,1);world.modern=modern;
        for(int i=0;i<5;i++)world.block(i*4,0,-.5,3,3,1);
        var terrain=new SlowGuideTerrain(world);State live=ParkourSpecialCheck.start(-5.11,0,.07,-90);Goal finalGoal=new Goal(21,0,0,.3,.2);
        Result route=ParkourPlanner.search(terrain,P,live,List.of(finalGoal),O);
        require(route.reached(),"Five-wall compound survives an expensive walking-guide environment: "+route.reason());
        require(terrain.expensiveColumns==0,"A validated complete chain must not spend its budget on redundant maze height columns");
        for(int i=0;i<route.actions().size();i++){
            live=ParkourPhysics.step(world,P,live,route.actions().get(i));require(live!=null&&live.equals(route.states().get(i+1)),"Exact compound replay");
            require(world.collisions(ParkourPhysics.bounds(live,P).inflate(-1e-6)).isEmpty(),"Compound cannot penetrate a wall");
        }
        require(finalGoal.reached(live),"Compound reaches its actual destination");
        System.out.println("COMPOUND_PRIORITY_PASS modern="+modern+" ticks="+route.actions().size()+" expensiveColumns="+terrain.expensiveColumns);
    }
    static void elevatedSupports(boolean modern,boolean ordered){
        var world=new ParkourSpecialCheck.World();world.modern=modern;
        var points=new ArrayList<Goal>();
        for(double[] platform:new double[][]{{0,0,0},{2,1,0},{4,2,0},{6,3,0},{8,3,0},{10,4,0}}){
            Box b=new Box(platform[0]-.5,platform[1]-1,platform[2]-.5,platform[0]+.5,platform[1],platform[2]+.5);world.blocks.add(b);
            points.add(new Goal(platform[0],platform[1],platform[2],.3,.2,b));
        }
        if(!ordered)points=new ArrayList<>(List.of(points.getLast()));
        State live=ParkourSpecialCheck.start(0,0,0,-90);int reached=0,total=0;
        require(ParkourGuide.build(world,P,live,points.getLast(),true,System.nanoTime()+500_000_000L)!=null,"Clear air at a high destination does not erase intermediate platforms");
        for(int window=0;window<16&&reached<points.size();window++){
            Result route=ParkourPlanner.search(world,P,live,ParkourCourse.window(points,reached,8),O);
            require(!route.actions().isEmpty(),"Elevated compound retains a continuation: "+route.reason());
            for(int i=0;i<route.actions().size();i++){
                State next=ParkourPhysics.step(world,P,live,route.actions().get(i));require(next!=null&&next.equals(route.states().get(i+1)),"Elevated support exact replay");
                require(world.collisions(ParkourPhysics.bounds(next,P).inflate(-1e-6)).isEmpty(),"Elevated route cannot clip solids");
                reached=ParkourCourse.advance(points,reached,live,next);live=next;total++;
            }
        }
        require(reached==points.size(),"Raised-platform chain completes");
        System.out.println("RAISED_COMPOUND_PASS modern="+modern+" ordered="+ordered+" ticks="+total);
    }
    public static void main(String[] args){
        for(boolean modern:new boolean[]{false,true}){protectedCompound(modern);for(boolean ordered:new boolean[]{false,true})elevatedSupports(modern,ordered);}
        System.out.println("COMPOUND_REGRESSION_PASS protected jumping budget, no wall penetration, raised final-only and ordered supports");
    }
}
