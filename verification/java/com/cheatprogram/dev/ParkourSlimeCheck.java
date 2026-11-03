package com.cheatprogram.dev;

import com.cheatprogram.parkour.ParkourPhysics;
import com.cheatprogram.parkour.ParkourPhysics.*;
import java.util.*;

/** Formula checks against local vanilla SlimeBlock/Entity/LivingEntity sources:
 * 1.20.1/1.20.4 stepOn runs inside Entity.move before travel gravity;
 * 1.21.4/1.21.10/26.1.2 run it after travel;
 * 26.2 Entity.restituteMovementAfterCollisions adds collision-time restitution.
 * This does not replace an in-game calibration of those mechanics. */
public final class ParkourSlimeCheck {
    private static final Parameters P=Parameters.defaults();
    private static final Action NEUTRAL=new Action(0,0,0,false,false);
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void close(double actual,double expected,String why){require(Math.abs(actual-expected)<1e-9,why+": "+actual+" expected "+expected);}
    private static final class SlimeTerrain implements Terrain {
        final List<Box> blocks=new ArrayList<>(List.of(new Box(-8,-1,-8,8,0,8)));
        boolean enabled=true,legacy,fractional,unsafe;double minimumKnown=-8;
        public List<Box> collisions(Box q){return blocks.stream().filter(b->b.intersects(q)).toList();}
        public boolean known(Box q){return q.minY()>minimumKnown&&q.maxY()<16&&q.minX()>-16&&q.maxX()<16&&q.minZ()>-16&&q.maxZ()<16;}
        public boolean safe(Box q){return !unsafe&&known(q);}
        public Surface surface(double x,double y,double z){return new Surface(enabled&&y<0?.8:.6,true);}
        public boolean slime(double x,double feetY,double z){return enabled&&Math.abs(x)<8&&Math.abs(z)<8&&Math.floor(feetY-(double).2f)==-1;}
        public boolean legacyStep(){return legacy;}
        public boolean fractionalBounce(){return fractional;}
    }
    private static State state(double y,double vx,double vy,double vz,boolean grounded,double fall){return new State(0,y,0,vx,vy,vz,0,grounded,false,0,0,fall);}
    private static State step(SlimeTerrain t,State s,Action a){State n=ParkourPhysics.step(t,P,s,a);require(n!=null,"Unexpected invalid slime state "+s);return n;}
    public static void main(String[] args){
        for(int mode=0;mode<3;mode++){
            SlimeTerrain t=new SlimeTerrain();t.legacy=mode==0;t.fractional=mode==2;
            State hit=step(t,state(.2,.1,-.8,.2,false,5),NEUTRAL);
            double bounce=t.fractional?(.25*.08+.8)*(1+.25*((double).98f-1)):.8;
            close(hit.y(),0,"Impact clipped to solid support");close(hit.vy(),(bounce-.08)*.98f,"Version-specific rebound before gravity/drag");
            close(hit.vx(),.1*.91f,"Fast rebound preserves horizontal velocity except native air drag");
            require(hit.onGround()&&hit.fallDistance()==0,"Slime impact resets fall distance while setting grounded contact");
            State airborne=step(t,hit,NEUTRAL);require(!airborne.onGround()&&airborne.y()>0,"Rebound actually leaves surface next tick");
            State low=step(t,state(.01,.1,-.04,0,false,0),NEUTRAL);
            double lowVy=t.fractional?0:.04,after=(lowVy-.08)*.98f;
            double sample=t.legacy?lowVy:after;
            close(low.vx(),.1*.91f*(.4+Math.abs(sample)*.2),"Native low-speed stepOn ordering");
            close(low.vy(),after,"Low-impact vertical threshold");
            State jump=step(t,state(0,0,-.0784,0,true,0),new Action(0,1,0,true,true));
            close(jump.y(),(double).42f,"Manual jump still uses normal impulse");require(!jump.onGround(),"Jump leaves slime");
            State falling=state(6,0,0,0,false,0);int contacts=0;double reboundPeak=0;boolean firstBounce=false;
            for(int tick=0;tick<130;tick++){
                State next=step(t,falling,NEUTRAL);
                if(next.onGround()&&!falling.onGround()){contacts++;firstBounce=true;}
                if(firstBounce)reboundPeak=Math.max(reboundPeak,next.y());
                falling=next;
            }
            require(contacts>=2&&reboundPeak>2&&reboundPeak<6.1,"Long drop and repeated dissipating rebounds: contacts="+contacts+" peak="+reboundPeak);
            t.enabled=false;require(ParkourPhysics.step(t,P,state(.2,0,-.8,0,false,5),NEUTRAL)==null,"Normal damaging impact still rejected");
            t.enabled=true;t.blocks.add(new Box(-2,1,-2,2,2,2));
            require(ParkourPhysics.step(t,P,state(4,0,-.8,0,false,3.2),NEUTRAL)==null,"Normal obstruction above slime cannot justify a high fall");
            t.blocks.removeLast();t.minimumKnown=1;
            require(ParkourPhysics.step(t,P,state(4,0,-.8,0,false,3.2),NEUTRAL)==null,"Unknown landing column rejected");
            t.minimumKnown=-8;t.unsafe=true;require(ParkourPhysics.step(t,P,state(1,0,-.8,0,false,0),NEUTRAL)==null,"Hazards remain rejected");
            t.unsafe=false;t.blocks.add(new Box(-2,2,-2,2,3,2));
            State head=step(t,state(0,0,-.0784,0,true,0),new Action(0,0,0,true,false));
            close(head.y(),.2,"Low ceiling collision clips position");
            close(head.vy(),((t.fractional?0:(double).42f)-.08)*.98f,"Legacy upward bounce callback and 26.2 restitution differ");
            System.out.println("SLIME_MODE_PASS "+mode+" impact, rebound, native slowdown, jump, repeated bounce, normal/unknown/hazard rejection, head collision");
        }
        System.out.println("SLIME_PASS legacy/new stepOn ordering and 26.2 collision-time restitution");
    }
}
