package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Long consecutive neos with only one block of landing room between them. */
public final class ParkourCompactNeoCheck {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void check(boolean modern,int rotation,int count,int height,float startingYaw){
        var w=new ParkourSpecialCheck.World().block(-5,-1,-.5,count*4+9,1,1);w.modern=modern;
        for(int i=0;i<count;i++)w.block(i*4,0,-.5,3,height,1);
        State start=new State(-5.11,0,.07,0,-.0784000015258789,0,startingYaw,true,false,0,0);
        Goal goal=new Goal((count-1)*4+5,0,0,.3,.2);
        for(int i=0;i<rotation;i++){
            var turned=new ParkourSpecialCheck.World();turned.modern=modern;
            for(Box b:w.blocks)turned.blocks.add(new Box(-b.maxZ(),b.minY(),b.minX(),-b.minZ(),b.maxY(),b.maxX()));w=turned;
            start=ParkourTightNeoCheck.rotate(start);goal=new Goal(-goal.z(),goal.y(),goal.x(),goal.radius(),goal.height());
        }
        // Match the production forward-biased 18-block capture, including its
        // actual player and distant final goal before any movement is applied.
        var center=ParkourPrediction.scanCenter(start,goal,18);
        Box captured=new Box(center[0]-18,-5,center[2]-18,center[0]+18,8,center[2]+18);
        var full=w;
        Terrain terrain=new Terrain(){
            public List<Box> collisions(Box q){return full.collisions(q);}
            public boolean known(Box q){return q.minX()>=captured.minX()&&q.maxX()<captured.maxX()&&q.minZ()>=captured.minZ()&&q.maxZ()<captured.maxZ()&&q.minY()>=-5&&q.maxY()<8;}
            public boolean safe(Box q){return known(q);}
            public Surface surface(double x,double y,double z){return new Surface(.6,true);}
            public boolean modernMovement(){return modern;}
        };
        var p=Parameters.defaults();var o=ParkourNeoChainCheck.O;
        Result route=ParkourPlanner.search(terrain,p,start,List.of(goal),o);
        String label="modern="+modern+" rotation="+rotation+" walls="+count+" height="+height+" yaw="+startingYaw;
        require(route.reached(),"Whole compact chain planned before takeoff / "+label+" / "+route.reason());
        require(route.actions().size()<=o.horizon()&&route.expanded()<=o.maxNodes(),"Shared search bounds");
        State live=start;int wallJumps=0,lastJump=-1;
        for(int i=0;i<route.actions().size();i++){
            Action a=route.actions().get(i);double delta=a.yaw()-live.yaw();delta-=Math.floor((delta+180)/360)*360;
            require(Math.abs(delta)<=o.maxTurnDegrees()+.0001,"Native yaw bound");
            double u=rotation==0?live.x():rotation==1?live.z():rotation==2?-live.x():-live.z();
            if(a.jump()&&live.onGround()&&u>-.8){
                if(lastJump>=0)require(i-lastJump==12,"Contact jumps preserve cadence; no waiting/replanning in the gap / "+label);
                wallJumps++;lastJump=i;
            }
            State next=ParkourPhysics.step(terrain,p,live,a);
            require(next!=null&&next.equals(route.states().get(i+1)),"Exact complete action/state replay");
            require(terrain.collisions(ParkourPhysics.bounds(next,p).inflate(-1e-6)).isEmpty(),"No solid penetration");
            require(next.y()>=0&&next.y()<1.253,"Normal jump height and same-height landing");
            live=next;
        }
        require(wallJumps==count&&goal.reached(live)&&live.onGround(),"Every wall crossed and final goal reached / "+label);
        System.out.println("COMPACT_NEO "+label+" ticks="+route.actions().size()+" nodes="+route.expanded());
    }
    public static void main(String[] args){
        int cases=0;
        for(boolean modern:new boolean[]{false,true})for(int rotation=0;rotation<4;rotation++)
            for(int count:new int[]{2,5})for(int height:new int[]{2,3})for(float yaw:new float[]{-90,8.9f}){check(modern,rotation,count,height,yaw);cases++;}
        System.out.println("COMPACT_NEO_PASS "+cases+" complete one-gap chains, full up-front planning, contact cadence and native replay");
    }
}
