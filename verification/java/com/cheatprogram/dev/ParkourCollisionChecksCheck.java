package com.cheatprogram.dev;
import com.cheatprogram.parkour.ParkourCollisionChecks;
import com.cheatprogram.parkour.ParkourPhysics.Box;
import java.util.List;

/** Movement-relevant geometry/signature checks, independent of the live game. */
public final class ParkourCollisionChecksCheck {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static boolean same(Box cell,Box corridor,List<Box> before,List<Box> after,double f0,double f1,boolean s0,boolean s1,int c0,int c1){
        return ParkourCollisionChecks.equivalent(cell,corridor,before,after,f0,f1,s0,s1,c0,c1);
    }
    public static void main(String[] args){
        Box cell=new Box(0,0,0,1,1,1),corridor=new Box(.2,.5,.2,.8,2.3,.8),outside=new Box(2,0,0,3,1,1);
        List<Box> empty=List.of();
        require(same(cell,corridor,empty,empty,.6,.6,true,true,0,0),"Cosmetic/random-tick state change must be equivalent");
        require(!same(cell,corridor,empty,List.of(cell),.6,.6,true,true,0,0),"New colliding block must invalidate");
        require(!same(cell,corridor,List.of(cell),empty,.6,.6,true,true,0,0),"Removed support/obstacle must invalidate");
        require(same(outside,corridor,empty,List.of(outside),.6,.6,true,true,0,0),"Neighbor changes outside corridor must not stop movement");
        Box floorCell=new Box(0,-1,0,1,0,1),feetAbove=new Box(.2,.4,.2,.8,2.2,.8);
        require(!same(floorCell,feetAbove,empty,empty,.6,.98,true,true,0,0),"Friction sampling below body remains validated");
        require(!same(cell,corridor,empty,empty,.6,.6,true,false,0,0),"Fluid/hazard changes in body corridor remain invalid");
        require(!same(cell,corridor,empty,empty,.6,.6,true,true,16,17),"Ladder orientation affects movement");
        require(!same(cell,corridor,empty,empty,.6,.6,true,true,0,1),"Climbability changes remain invalid");
        Box protruding=new Box(.7,.8,.3,1.2,1.5,.7);
        require(!same(outside,corridor,empty,List.of(protruding),.6,.6,true,true,0,0),"Protruding neighbor shapes cannot be ignored");
        Box a=new Box(.2,.5,.2,.4,1,.4),b=new Box(.5,.5,.5,.7,1,.7);
        require(same(cell,corridor,List.of(a,b),List.of(b,a),.6,.6,true,true,0,0),"Shape component enumeration order is irrelevant");
        System.out.println("COLLISION_EQUIVALENCE_PASS cosmetic changes, geometry, neighbor corridor, friction, hazards and climbability");
    }
}
