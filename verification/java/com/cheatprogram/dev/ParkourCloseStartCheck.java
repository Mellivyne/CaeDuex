package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Unit voxel shapes, native float dimensions, and starts touching the first neo. */
public final class ParkourCloseStartCheck {
    private static void require(boolean v,String why){if(!v)throw new AssertionError(why);}
    public static void main(String[] args){
        var p=new Parameters((double).1f,(double).6f,(double)1.8f,(double).6f,.08,(double).42f,.02);
        for(boolean modern:new boolean[]{false,true})for(boolean ordered:new boolean[]{false,true})
        for(double startZ:new double[]{.5,1.214}){
            var w=new ParkourSpecialCheck.World();w.modern=modern;
            for(int x=-5;x<39;x++)w.block(x,-1,0,1,1,1);
            for(int wall=0;wall<9;wall++)for(int x=0;x<3;x++)for(int y=0;y<2;y++)w.block(wall*4+x,y,0,1,1,1);
            State live=new State(-.3,0,startZ,0,-.0784000015258789,0,-85,true,false,0,0);
            var goals=new ArrayList<Goal>();
            if(ordered)for(int i=0;i<8;i++)goals.add(new Goal(i*4+3.5,0,.5,.3,.2,new Box(i*4+3,-1,0,i*4+4,0,1)));
            goals.add(new Goal(38.5,0,.5,.3,.2,new Box(38,-1,0,39,0,1)));
            int reached=0,ticks=0;
            for(int window=0;window<16&&reached<goals.size();window++){
                var remaining=ParkourCourse.window(goals,reached,8);
                var snapshot=ParkourLongCompactCheck.capture(w,live,ParkourPrediction.scanGoal(remaining,0));
                Result route=ParkourPlanner.search(snapshot,p,live,remaining,ParkourSpecialCheck.O);
                require(!route.actions().isEmpty(),"Close-start chain lost: modern="+modern+" ordered="+ordered+" side="+startZ+" window="+window+" "+route.reason());
                int stop=route.reached()?route.actions().size():ParkourPrediction.anchor(route.states(),0,35);
                require(stop>0,"Continuation must advance");
                for(int i=0;i<stop;i++){
                    State next=ParkourPhysics.step(w,p,live,route.actions().get(i));
                    require(next!=null&&next.equals(route.states().get(i+1)),"Replay every native tick");
                    require(w.collisions(ParkourPhysics.bounds(next,p).inflate(-1e-6)).isEmpty(),"No solid penetration");
                    reached=ParkourCourse.advance(goals,reached,live,next);live=next;ticks++;
                }
            }
            require(reached==goals.size(),"Nine walls must finish");
            System.out.println("CLOSE_START_PASS modern="+modern+" ordered="+ordered+" side="+startZ+" ticks="+ticks);
        }
    }
}
