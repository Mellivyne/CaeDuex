package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** External course returns and physically simulated direct drops, including damaging heights. */
public final class ParkourProgressHeightCheck {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static State at(double x,double y,double z){return new State(x,y,z,0,-.0784000015258789,0,-90,true,false,0,0);}
    public static void main(String[] args){
        var goals=new ArrayList<Goal>();for(int i=0;i<10;i++)goals.add(new Goal(i*4,0,0,.3,.2));
        State origin=at(-4,0,0),saved=at(30,0,0);
        require(ParkourCourse.rewind(goals,8,at(8,0,0),saved,origin)==3,"Return to passed waypoint resumes its next leg");
        require(ParkourCourse.rewind(goals,10,at(8,0,0),at(36,0,0),origin)==3,"A completed route returned to an earlier section resumes there");
        require(ParkourCourse.rewind(goals,8,at(10,0,0),saved,origin)==3,"Return between earlier points selects that segment's destination");
        require(ParkourCourse.rewind(goals,8,origin,saved,origin)==0,"Return to the real initial start resets progress");
        require(ParkourCourse.rewind(goals,8,at(28,0,0),saved,origin)==8,"Last passed checkpoint remains the current incoming leg");
        require(ParkourCourse.rewind(goals,8,at(8,-4,0),saved,origin)==8,"Height alone does not select a different old leg");
        require(ParkourCourse.rewind(goals,8,saved,saved,origin)==8,"An unchanged interrupted position preserves progress");
        require(ParkourCourse.rewind(goals,8,at(8,0,0),at(8.1,0,0),origin)==8,"No return inferred from tiny ordinary movement");
        var crossings=List.of(new Goal(0,0,0,.3,.2),new Goal(4,0,0,.3,.2),new Goal(4,0,4,.3,.2),new Goal(-4,0,0,.3,.2),new Goal(4,0,0,.3,.2));
        require(ParkourCourse.rewind(crossings,4,at(0,0,0),at(-4,0,0),origin)==4,"An overlapping current leg wins an ambiguous old crossing");
        var large=new ArrayList<Goal>();for(int i=0;i<10000;i++)large.add(new Goal(i*4,0,0,.3,.2));
        require(ParkourCourse.rewind(large,9998,at(400,0,0),at(39990,0,0),origin)==101,"Rewind has no fixed waypoint-history limit");
        System.out.println("PROGRESS_RETURN_PASS markers, old segments, initial start, nearby resume, vertical separation, crossings and 10000-point route");
        int[] bounds=ParkourPrediction.verticalBounds(250,-60,8,-64,384);
        require(bounds[0]<=-63&&bounds[1]>=259,"Capture spans a full-dimension descent before takeoff");
        bounds=ParkourPrediction.verticalBounds(-60,300,8,-64,384);
        require(bounds[0]==-64&&bounds[1]>=305,"High endpoint remains in a dimension-bounded capture");

        for(boolean modern:new boolean[]{false,true})for(int drop:new int[]{2,5,20,48,300}){
            var world=new ParkourSpecialCheck.World();
            world.modern=modern;world.block(-2,-1,-1,2.5,1,2).block(1,-drop-1,-2,6,1,4);
            Terrain terrain=new Terrain(){
                public boolean known(Box b){return b.minX()>-80&&b.maxX()<80&&b.minY()>-512&&b.maxY()<80&&b.minZ()>-80&&b.maxZ()<80;}
                public boolean safe(Box b){return known(b);}public List<Box> collisions(Box b){return world.collisions(b);}
                public Surface surface(double x,double y,double z){return world.surface(x,y,z);}public boolean modernMovement(){return world.modern;}
            };
            State start=at(0,0,0);Goal goal=new Goal(3,-drop,0,.4,.2);
            var p=Parameters.defaults().allowingFalls();var o=new Options(ParkourPrediction.planningHorizon(100,p,start,goal),100000,96,350_000_000L,true,true,true,25);
            Result route=ParkourPlanner.search(terrain,p,start,List.of(goal),o);
            require(route.reached(),"Direct drop must not be rejected by a waypoint-height cap / modern="+modern+" drop="+drop+" "+route.reason());
            State live=start;for(Action input:route.actions()){live=ParkourPhysics.step(terrain,p,live,input);require(live!=null,"Every drop tick uses real collision and gravity");}
            require(goal.reached(live)&&live.onGround(),"Drop reaches actual lower support");
            System.out.println("UNRESTRICTED_DROP_PASS modern="+modern+" drop="+drop+" ticks="+route.actions().size());
        }
    }
}
