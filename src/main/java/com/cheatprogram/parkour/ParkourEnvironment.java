package com.cheatprogram.parkour;

import com.cheatprogram.parkour.ParkourPhysics.Box;

/** Exact-volume environmental exclusions shared by snapshot and route checks. */
public final class ParkourEnvironment {
    private ParkourEnvironment(){}
    /** Fluid collision uses the actual surface, not an entire wet block cell. */
    public static Box fluid(int x,int y,int z,double height){
        if(!Double.isFinite(height)||height<=0)return null;
        return new Box(x,y,z,x+1,y+Math.min(1,height),z+1);
    }
    public static boolean touchesFluid(Box body,Box fluid){
        return fluid!=null&&body.inflate(-.001).intersects(fluid);
    }
    public static boolean sameFluid(Box before,Box after,Box corridor){
        return java.util.Objects.equals(before,after)
            ||!touchesFluid(corridor,before)&&!touchesFluid(corridor,after);
    }
    /** Grow a failed local scan gradually; retain hard memory/work bounds. */
    public static int widerScan(int current,boolean enabled){
        if(current<8||current>40)throw new IllegalArgumentException("Invalid scan radius");
        return enabled?Math.min(40,current+8):current;
    }
}
