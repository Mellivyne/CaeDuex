package com.cheatprogram.parkour;

import com.cheatprogram.parkour.ParkourPhysics.Box;
import java.util.List;

/** Movement equivalence is independent of cosmetic/random-tick BlockState changes. */
public final class ParkourCollisionChecks {
    private ParkourCollisionChecks(){}
    public static boolean equivalent(Box cell,Box swept,List<Box> before,List<Box> after,
                                     double oldFriction,double newFriction,boolean oldSafe,boolean newSafe,
                                     int oldClimb,int newClimb){
        if(cell.intersects(swept)&&(oldSafe!=newSafe||oldClimb!=newClimb))return false;
        if(cell.intersects(swept.expand(0,-.500001,0))&&Double.compare(oldFriction,newFriction)!=0)return false;
        List<Box> oldCollision=before.stream().filter(b->b.intersects(swept)).toList();
        List<Box> newCollision=after.stream().filter(b->b.intersects(swept)).toList();
        return oldCollision.size()==newCollision.size()&&oldCollision.containsAll(newCollision);
    }
}
