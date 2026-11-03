package com.cheatprogram.dev;
import com.cheatprogram.parkour.ParkourCamera;
import com.cheatprogram.parkour.ParkourPhysics.State;
import java.util.*;

/** Pure frame interpolation tests; no Minecraft session or display required. */
public final class ParkourCameraCheck {
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static float wrap(float value){return (float)(value-Math.floor((value+180)/360)*360);}
    public static void main(String[] args){
        List<State> straight=new ArrayList<>();for(int i=0;i<9;i++)straight.add(new State(i*.3,0,0,.25,-.0784,0,-90,true,true,0,i));
        require(Math.abs(ParkourCamera.heading(straight,0,-110)+90)<.001&&Math.abs(ParkourCamera.heading(straight,0,-70)+90)<.001,"Small left/right steering corrections retain the same path-facing view");
        require(ParkourCamera.heading(straight,0,90)==90,"Backpedalling does not force a reverse camera spin");
        ParkourCamera.begin(0);ParkourCamera.tick(90);
        float start=ParkourCamera.yaw(90,0),middle=ParkourCamera.yaw(90,.5f),end=ParkourCamera.yaw(90,1);
        require(start==0&&middle>0&&middle<end&&end<90,"A sudden tick turn is interpolated across frames");
        float last=end;
        for(int tick=0;tick<20;tick++){
            ParkourCamera.tick(90);float first=ParkourCamera.yaw(90,0);
            require(Math.abs(first-last)<.0001,"No discontinuity at tick boundary");
            for(int frame=1;frame<=10;frame++){float angle=ParkourCamera.yaw(90,frame/10f);require(angle>=last-.0001&&angle<=90.001,"Damped monotonic settling");last=angle;}
        }
        require(Math.abs(last-90)<.001,"Settles to physical heading");
        ParkourCamera.begin(179);ParkourCamera.tick(-179);
        float across=ParkourCamera.yaw(-179,1);
        require(across>179&&across<181,"Shortest path across the wrap, not a full spin");
        for(int tick=0;tick<200;tick++){
            float yaw=wrap(179+tick*18);ParkourCamera.tick(yaw);
            float a=ParkourCamera.yaw(yaw,0),b=ParkourCamera.yaw(yaw,.5f),c=ParkourCamera.yaw(yaw,1);
            require(Float.isFinite(c)&&Math.abs(wrap(c-a))<30&&Math.abs(wrap(b-a))<30,"Repeated revolutions stay smooth and finite");
        }
        float before=ParkourCamera.yaw(wrap(179+199*18),1);float physical=wrap(179+199*18);
        ParkourCamera.clear();float released=ParkourCamera.yaw(physical,1);
        require(Math.abs(wrap(released-before))<.05,"Release initially preserves rendered heading");
        float moved=ParkourCamera.yaw(physical+7,1);
        require(Math.abs(wrap(moved-released)-7)<.05,"Manual mouse deltas stay live during release");
        ParkourCamera.begin(42);require(ParkourCamera.yaw(42,.5f)==42,"Restart discards old smoothing state");
        ParkourCamera.clear();
        System.out.println("PARKOUR_CAMERA_PASS frame continuity, damping, wrap, revolutions, release, manual delta and reset");
    }
}
