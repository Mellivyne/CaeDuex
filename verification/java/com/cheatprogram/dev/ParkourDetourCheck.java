package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Loaded/static collision detours, replayed through production tick physics. No game is started. */
public final class ParkourDetourCheck {
    private static final Parameters P=Parameters.defaults();
    private static final Options O=new Options(100,100000,96,350_000_000L,true,true,true,25);
    private record Fixture(String name,ParkourPhysicsCheck.TerrainFixture terrain,State start,List<Goal> goals,
                           boolean reachable,double minimumSide,double mustRetreatBelow){}
    private record Outcome(boolean reached,int legs,int ticks,double length,double maximumSide,double minimumZ,String reason){}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static State start(double x,double z){return new State(x,0,z,0,-.0784000015258789,0,0,true,false,0,0);}
    private static Goal goal(double x,double z){return new Goal(x,0,z,.4,.2);}
    private static ParkourPhysicsCheck.TerrainFixture floor(){return new ParkourPhysicsCheck.TerrainFixture().block(-20,-1,-20,40,1,40);}
    private static List<Fixture> fixtures(){
        return List.of(
            new Fixture("wall-bypass",floor().block(-4,0,3,8,4,1),start(0,0),List.of(goal(0,8)),true,4.2,Double.POSITIVE_INFINITY),
            new Fixture("L-corner",floor().block(-3,0,3,7,4,1).block(3,0,3,1,4,6),start(0,0),List.of(goal(0,8)),true,3.2,Double.POSITIVE_INFINITY),
            new Fixture("U-escape-away-first",floor().block(-4,0,5,8,4,1).block(-4,0,0,1,4,5).block(3,0,0,1,4,5),start(0,3),List.of(goal(0,8)),true,4.2,-.25),
            new Fixture("offset-doorway-ordered",floor().block(-10,0,3,12,4,1).block(3.5,0,3,6.5,4,1),start(-4,0),List.of(goal(-4,8),goal(0,8)),true,6.2,Double.POSITIVE_INFINITY),
            new Fixture("sealed-unreachable",floor().block(-3,0,3,6,4,1).block(-3,0,8,6,4,1).block(-3,0,3,1,4,6).block(2,0,3,1,4,6),start(0,0),List.of(goal(0,6)),false,0,Double.POSITIVE_INFINITY)
        );
    }
    private static Outcome run(Fixture f){
        State live=f.start();int point=0,ticks=0,legs=0;double length=0,maxSide=0,minZ=live.z();String reason="";
        for(;legs<12&&point<f.goals().size();legs++){
            List<Goal> window=ParkourCourse.window(f.goals(),point);
            Result result=ParkourPlanner.search(f.terrain(),P,live,window,O);reason=result.reason();
            require(result.expanded()<=O.maxNodes(),f.name()+" exceeds node budget");
            require(result.states().size()==result.actions().size()+1,f.name()+" malformed result");
            int actionIndex=0;
            for(Action action:result.actions()){
                State next=ParkourPhysics.step(f.terrain(),P,live,action);
                require(next!=null,f.name()+" unsafe replay");
                Box body=ParkourPhysics.bounds(next,P).inflate(-.000001);
                require(f.terrain().known(body)&&f.terrain().safe(body)&&f.terrain().collisions(body).isEmpty(),f.name()+" crossed a collision or unknown region");
                State recorded=result.states().get(++actionIndex);
                require(Math.hypot(next.x()-recorded.x(),next.z()-recorded.z())<1e-8&&Math.abs(next.y()-recorded.y())<1e-8,f.name()+" replay drift");
                length+=Math.sqrt(Math.pow(next.x()-live.x(),2)+Math.pow(next.y()-live.y(),2)+Math.pow(next.z()-live.z(),2));
                maxSide=Math.max(maxSide,Math.abs(next.x()-f.start().x()));minZ=Math.min(minZ,next.z());
                point=ParkourCourse.advance(f.goals(),point,next);live=next;ticks++;
            }
            if(result.actions().isEmpty())break;
        }
        return new Outcome(point==f.goals().size(),legs,ticks,length,maxSide,minZ,reason);
    }
    public static void main(String[] args){
        boolean reportOnly=args.length>0&&args[0].equals("--report");List<String> failures=new ArrayList<>();
        for(boolean modern:new boolean[]{false,true})for(Fixture f:fixtures()){
            f.terrain().modern=modern;
            Outcome result=run(f);
            System.out.printf(Locale.ROOT,"DETOUR %s modern="+modern+" reached=%s windows=%d ticks=%d travel=%.3f maxSide=%.3f minZ=%.3f reason=%s%n",f.name(),result.reached(),result.legs(),result.ticks(),result.length(),result.maximumSide(),result.minimumZ(),result.reason());
            if(result.reached()!=f.reachable())failures.add(f.name()+" reachable="+f.reachable()+" actual="+result.reached());
            if(result.reached()&&result.maximumSide()<f.minimumSide())failures.add(f.name()+" did not go around required side clearance");
            if(result.reached()&&result.minimumZ()>f.mustRetreatBelow())failures.add(f.name()+" did not retreat through U entrance");
        }
        if(!reportOnly)require(failures.isEmpty(),String.join("; ",failures));
        if(failures.isEmpty())System.out.println("DETOUR_PASS wall bypass, L corner, away-first U escape, offset doorway/order and sealed target rejection");
        else System.out.println("DETOUR_FAILURES "+String.join("; ",failures));
        var blocks=floor().block(-4,0,3,8,3,1);
        Terrain shortScan=new Terrain(){
            public List<Box> collisions(Box q){return blocks.collisions(q);}
            public boolean safe(Box q){return known(q);}
            public boolean known(Box q){return blocks.known(q)&&q.minY()>-4&&q.maxY()<4;}
            public Surface surface(double x,double y,double z){return blocks.surface(x,y,z);}
        };
        require(ParkourGuide.build(shortScan,P,start(0,0),goal(0,8),true,System.nanoTime()+350_000_000L)!=null,"Minimum-height scan still yields detour guidance");
        System.out.println("DETOUR_SHORT_SCAN_PASS probe outside height bounds retains known collision surfaces");
    }
}
