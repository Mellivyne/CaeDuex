package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** A normal gap jump must be planned together with entry to a short-runway neo chain. */
public final class ParkourEntryTransitionCheck {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(boolean modern:new boolean[]{false,true})for(boolean ordered:new boolean[]{false,true})for(int rotation=0;rotation<4;rotation++){
            var world=new ParkourSpecialCheck.World().block(-8,-1,-.5,2,1,1).block(-3,-1,-.5,19,1,1);world.modern=modern;
            for(int i=0;i<3;i++)world.block(i*4,0,-.5,3,2,1);
            var goals=new ArrayList<Goal>();
            if(ordered)goals.add(new Goal(-2.5,0,0,.3,.2,new Box(-3,-1,-.5,-2,0,.5)));
            goals.add(new Goal(13,0,0,.3,.2));
            State state=ParkourSpecialCheck.start(-6.5,0,0,-90);int point=0,ticks=0;
            for(int turn=0;turn<rotation;turn++){
                var rotated=new ParkourSpecialCheck.World();rotated.modern=modern;
                for(Box b:world.blocks)rotated.blocks.add(new Box(-b.maxZ(),b.minY(),b.minX(),-b.minZ(),b.maxY(),b.maxX()));world=rotated;
                var points=new ArrayList<Goal>();
                for(Goal g:goals){Box b=g.region();points.add(new Goal(-g.z(),g.y(),g.x(),g.radius(),g.height(),b==null?null:new Box(-b.maxZ(),b.minY(),b.minX(),-b.minZ(),b.maxY(),b.maxX())));}goals=points;
                state=ParkourTightNeoCheck.rotate(state);
            }
            for(int window=0;window<12&&point<goals.size();window++){
                Result route=ParkourPlanner.search(world,Parameters.defaults(),state,ParkourCourse.window(goals,point,8),ParkourSpecialCheck.O);
                require(route.expanded()<=ParkourSpecialCheck.O.maxNodes(),"Compound entry shares the total search budget");
                require(!route.actions().isEmpty(),"Entry lost: modern="+modern+" ordered="+ordered+" window="+window+" "+route.reason());
                if(window==0){
                    boolean crossed=false;for(State s:route.states()){
                        double forward=rotation==0?s.x():rotation==1?s.z():rotation==2?-s.x():-s.z();
                        if(forward>3.3&&s.onGround())crossed=true;
                    }
                    if(!crossed){
                        // A bounded first window can finish before the wall,
                        // but its future continuation must already be viable
                        // before any approach input is executed.
                        int contact=ParkourPrediction.anchor(route.states(),0,35);
                        int nextPoint=ParkourContinuity.reachedThrough(goals,point,route.states().subList(0,contact+1));
                        Result ahead=ParkourPlanner.search(world,Parameters.defaults(),route.states().get(contact),ParkourCourse.window(goals,nextPoint,8),ParkourSpecialCheck.O);
                        require(!ahead.actions().isEmpty(),"The approach must have a viable prediction before reaching its contact");
                        for(State s:ahead.states()){
                            double forward=rotation==0?s.x():rotation==1?s.z():rotation==2?-s.x():-s.z();
                            if(forward>3.3&&s.onGround())crossed=true;
                        }
                    }
                }
                int stop=route.reached()?route.actions().size():ParkourPrediction.anchor(route.states(),0,35);
                for(Action action:route.actions().subList(0,stop)){
                    State next=ParkourPhysics.step(world,Parameters.defaults(),state,action);
                    require(next!=null&&world.collisions(ParkourPhysics.bounds(next,Parameters.defaults()).inflate(-1e-6)).isEmpty(),"Exact native collision replay");
                    point=ParkourCourse.advance(goals,point,state,next);state=next;ticks++;
                }
            }
            require(point==goals.size(),"Mixed approach must finish all three walls");
            System.out.println("ENTRY_TRANSITION_PASS modern="+modern+" ordered="+ordered+" rotation="+rotation+" ticks="+ticks);
        }
    }
}
