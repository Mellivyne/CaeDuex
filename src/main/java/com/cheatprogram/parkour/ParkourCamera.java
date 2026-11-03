package com.cheatprogram.parkour;
import java.util.List;
import com.cheatprogram.parkour.ParkourPhysics.State;

/** Render-only heading filter. Native player yaw and the physics plan are untouched. */
public final class ParkourCamera {
    private static final double STEP=.05, OMEGA=16;
    private static boolean active,drawn;
    private static double previous,current,previousVelocity,velocity,target,lastDrawn,releaseOffset;
    private static long releasedAt;
    private ParkourCamera(){}
    /** Aim along the verified path instead of every small steering-key heading.
     * Backpedalling preparation still looks toward the obstacle. */
    public static float heading(List<State> states,int index,float physicalYaw){
        if(states==null||index<0||index>=states.size())return physicalYaw;
        State from=states.get(index),to=states.get(Math.min(states.size()-1,index+6));
        double dx=to.x()-from.x(),dz=to.z()-from.z();if(dx*dx+dz*dz<.0064)return physicalYaw;
        float path=(float)Math.toDegrees(Math.atan2(-dx,dz));double delta=wrap(path-physicalYaw);
        return Math.abs(delta)<=85?physicalYaw+(float)delta:physicalYaw;
    }

    public static void begin(float yaw){
        previous=current=target=lastDrawn=yaw;previousVelocity=velocity=releaseOffset=0;
        active=true;drawn=false;releasedAt=0;
    }
    /** Call once per native input tick, including neutral-input waits. */
    public static void tick(float yaw){
        if(!active||!Float.isFinite(yaw))return;
        previous=current;previousVelocity=velocity;
        target=current+wrap(yaw-current);
        double error=current-target,c=velocity+OMEGA*error,decay=Math.exp(-OMEGA*STEP);
        current=target+(error+c*STEP)*decay;
        velocity=(velocity-OMEGA*c*STEP)*decay;
        // Keep accumulated revolutions bounded without changing relative angles.
        if(Math.abs(current)>720){double shift=360*Math.floor((current+180)/360);previous-=shift;current-=shift;target-=shift;lastDrawn-=shift;}
    }
    /** Hand back to the live vanilla view with a short render-only decay. */
    public static void clear(){
        if(!active)return;
        releaseOffset=drawn?wrap(lastDrawn-target):0;releasedAt=System.nanoTime();active=false;
    }
    public static float yaw(float vanillaYaw,float partialTick){
        if(!Float.isFinite(vanillaYaw))return vanillaYaw;
        if(!active){
            if(releasedAt==0)return vanillaYaw;
            double t=(System.nanoTime()-releasedAt)/1_000_000_000.0;
            if(t>=.25){releasedAt=0;releaseOffset=0;return vanillaYaw;}
            // Smoothstep release reaches exactly zero; manual mouse movement stays live.
            double u=Math.max(0,Math.min(1,t/.25)),blend=1-u*u*(3-2*u);
            return (float)(vanillaYaw+releaseOffset*blend);
        }
        double t=Math.max(0,Math.min(1,Float.isFinite(partialTick)?partialTick:0));
        double t2=t*t,t3=t2*t;
        double angle=(2*t3-3*t2+1)*previous+(t3-2*t2+t)*previousVelocity*STEP
                +(-2*t3+3*t2)*current+(t3-t2)*velocity*STEP;
        lastDrawn=angle;drawn=true;
        return (float)angle;
    }
    private static double wrap(double angle){return angle-Math.floor((angle+180)/360)*360;}
}
