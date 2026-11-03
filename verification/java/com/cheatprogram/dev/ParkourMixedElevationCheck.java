package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** A wall compound followed by raised turning islands, including a sole high final goal. */
public final class ParkourMixedElevationCheck {
    static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args){
        Parameters p=new Parameters((double).1f,(double).6f,(double)1.8f,(double).6f,.08,(double).42f,.02);
        for(boolean modern:new boolean[]{false,true})for(boolean ordered:new boolean[]{false,true})
        for(boolean blocked:new boolean[]{false,true}){
            var w=new ParkourSpecialCheck.World();w.modern=modern;
            for(int x=-5;x<27;x++)w.block(x,-1,0,1,1,1);
            for(int i=0;i<6;i++)for(int x=0;x<3;x++)for(int y=0;y<2;y++)w.block(i*4+x,y,0,1,1,1);
            double[][] islands={{25,0,0},{29,1,0},{33,2,0},{36,3,-2},{39,3,-5},{42,4,-8}};
            var goals=new ArrayList<Goal>();
            for(int i=0;i<islands.length;i++){
                double[] b=islands[i];Box support=new Box(b[0],b[1]-1,b[2],b[0]+1,b[1],b[2]+1);
                if(i>0)w.blocks.add(support);
                if(ordered||i==islands.length-1)goals.add(new Goal(b[0]+.5,b[1],b[2]+.5,.3,.2,support));
            }
            State state=new State(-.3,0,.953,0,-.0784000015258789,0,-85,true,false,0,0,0,blocked);
            int point=0,total=0;
            for(int window=0;window<16&&point<goals.size();window++){
                var remaining=ParkourCourse.window(goals,point,8);
                var captured=ParkourLongCompactCheck.capture(w,state,ParkourPrediction.scanGoal(remaining,0));
                Result result=ParkourPlanner.search(captured,p,state,remaining,ParkourSpecialCheck.O);
                String label="modern="+modern+" ordered="+ordered+" blocked="+blocked+" window="+window;
                require(!result.actions().isEmpty(),"Mixed course stopped / "+label+" / "+result.reason());
                int stop=result.reached()?result.actions().size():ParkourPrediction.anchor(result.states(),0,35);
                require(stop>0,"Must advance / "+label);
                for(Action action:result.actions().subList(0,stop)){
                    State next=ParkourPhysics.step(w,p,state,action);
                    require(next!=null&&w.collisions(ParkourPhysics.bounds(next,p).inflate(-1e-6)).isEmpty(),"No wall penetration / "+label);
                    point=ParkourCourse.advance(goals,point,state,next);state=next;total++;
                }
            }
            require(point==goals.size(),"Final high platform must be reached");
            System.out.println("MIXED_ELEVATION_PASS modern="+modern+" ordered="+ordered+" blocked="+blocked+" ticks="+total);
        }
    }
}
