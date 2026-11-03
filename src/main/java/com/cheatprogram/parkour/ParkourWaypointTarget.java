package com.cheatprogram.parkour;
import com.cheatprogram.parkour.ParkourPhysics.Box;
import com.cheatprogram.parkour.ParkourPlanner.Goal;
import java.util.List;

/** Selects the top of the aimed shape component, including thin/non-cube blocks. */
public final class ParkourWaypointTarget {
    private ParkourWaypointTarget(){}
    public static Goal top(List<Box> shapes,double hitX,double hitY,double hitZ){
        Box best=null;double score=Double.POSITIVE_INFINITY;
        for(Box box:shapes){
            double x=clamp(hitX,box.minX(),box.maxX()),z=clamp(hitZ,box.minZ(),box.maxZ());
            double horizontal=Math.hypot(x-hitX,z-hitZ);
            double candidate=horizontal*100+Math.abs(box.maxY()-hitY);
            if(candidate<score){best=box;score=candidate;}
        }
        if(best==null)return null;
        // Keep the selected point inside the component without centering a full voxel.
        double mx=Math.min(.12,(best.maxX()-best.minX())*.5),mz=Math.min(.12,(best.maxZ()-best.minZ())*.5);
        return new Goal(clamp(hitX,best.minX()+mx,best.maxX()-mx),best.maxY(),clamp(hitZ,best.minZ()+mz,best.maxZ()-mz),.3,.2,best);
    }
    private static double clamp(double v,double min,double max){return Math.max(min,Math.min(max,v));}
}
