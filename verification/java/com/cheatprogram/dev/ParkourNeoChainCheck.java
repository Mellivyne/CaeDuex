package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Ordinary-ground, same-height neo chains with ONE user checkpoint at the end. */
public final class ParkourNeoChainCheck {
    static final Parameters P=Parameters.defaults();
    static final Options O=new Options(100,100000,96,350_000_000L,true,true,true,25);
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static ParkourSpecialCheck.Scene scene(int count,double spacing,int turns,double startX){
        var world=new ParkourSpecialCheck.World().block(-10,-1,-.5,count*spacing+16,1,1);
        for(int i=0;i<count;i++)world.block(-1.5+i*spacing,0,-.5,3,3,1);
        double x=startX,z=0,gx=(count-1)*spacing+5,gz=0;
        for(int i=0;i<turns;i++){
            var rotated=new ParkourSpecialCheck.World();
            for(Box b:world.blocks)rotated.blocks.add(new Box(-b.maxZ(),b.minY(),b.minX(),-b.minZ(),b.maxY(),b.maxX()));
            world=rotated;double t=x;x=-z;z=t;t=gx;gx=-gz;gz=t;
        }
        return new ParkourSpecialCheck.Scene(count+"-triple-neos-gap-"+(spacing-3)+"-rotation-"+(turns*90)+"-start-"+startX,
            world,ParkourSpecialCheck.start(x,0,z,-90+turns*90),List.of(new Goal(gx,0,gz,.3,.2)));
    }
    static void check(ParkourSpecialCheck.Scene scene,boolean modern){
        scene.world().modern=modern;State state=scene.start();Goal goal=scene.goals().getFirst();int steps=0,windows=0;
        require(scene.goals().size()==1,"No manually inserted intermediate checkpoints");
        while(!goal.reached(state)&&windows++<20){
            Result route=ParkourPlanner.search(scene.world(),P,state,scene.goals(),O);
            require(route.expanded()<=O.maxNodes(),"Shared node limit");
            require(!route.actions().isEmpty(),scene.name()+" stopped before the final checkpoint / "+modern+" / "+state+" / "+route.reason());
            require(route.actions().size()<=O.horizon(),"Shared action horizon");
            for(int i=0;i<route.actions().size();i++){
                Action action=route.actions().get(i);double delta=action.yaw()-state.yaw();delta-=Math.floor((delta+180)/360)*360;
                require(Math.abs(delta)<=O.maxTurnDegrees()+.0001,"Native bounded turning");
                State next=ParkourPhysics.step(scene.world(),P,state,action);
                require(next!=null,scene.name()+" invalid native input");
                require(next.equals(route.states().get(i+1)),"The entire approach and jump must replay exactly");
                require(scene.world().collisions(ParkourPhysics.bounds(next,P).inflate(-1e-6)).isEmpty(),"Body penetrates a wall");
                require(next.y()<=1.253&&next.y()>=0,"No extra jump height or lower landing platform");
                state=next;steps++;
            }
            require(state.onGround(),"Rolling continuation has a grounded endpoint");
        }
        require(goal.reached(state),scene.name()+" did not reach its single final checkpoint / "+modern);
        System.out.println("NEO_CHAIN "+scene.name()+" modern="+modern+" ticks="+steps+" windows="+windows);
    }
    static void finiteSnapshots(boolean modern){
        var scene=scene(8,8,0,-6);scene.world().modern=modern;
        State state=scene.start();Goal goal=scene.goals().getFirst();int windows=0;
        while(!goal.reached(state)&&windows++<20){
            double cx=state.x(),cz=state.z();
            Box captured=new Box(cx-18,-5,cz-18,cx+18,8,cz+18);
            List<Box> shapes=scene.world().blocks.stream().filter(b->b.intersects(captured)).toList();
            Terrain snapshot=new Terrain(){
                public boolean known(Box q){return q.minX()>=captured.minX()&&q.maxX()<=captured.maxX()&&q.minY()>=captured.minY()&&q.maxY()<=captured.maxY()&&q.minZ()>=captured.minZ()&&q.maxZ()<=captured.maxZ();}
                public boolean safe(Box q){return known(q);}
                public List<Box> collisions(Box q){return shapes.stream().filter(b->b.intersects(q)).toList();}
                public Surface surface(double x,double y,double z){return new Surface(.6,true);}
                public boolean modernMovement(){return modern;}
            };
            Result route=ParkourPlanner.search(snapshot,P,state,List.of(goal),O);
            require(!route.actions().isEmpty(),"Distant final checkpoint must permit a local continuation / "+modern+" / "+state);
            require(route.expanded()<=O.maxNodes(),"Finite snapshot node limit");
            for(Action a:route.actions()){
                state=ParkourPhysics.step(snapshot,P,state,a);require(state!=null,"No movement beyond captured terrain");
            }
        }
        require(goal.reached(state),"Eight neos with only local terrain and one final checkpoint / "+modern);
        System.out.println("NEO_LOCAL_SNAPSHOTS modern="+modern+" windows="+windows);
    }
    static Terrain capture(ParkourSpecialCheck.World world,State center,boolean modern){
        Box captured=new Box(center.x()-18,-5,center.z()-18,center.x()+18,8,center.z()+18);
        List<Box> shapes=world.blocks.stream().filter(b->b.intersects(captured)).toList();
        return new Terrain(){
            public boolean known(Box q){return q.minX()>=captured.minX()&&q.maxX()<=captured.maxX()&&q.minY()>=captured.minY()&&q.maxY()<=captured.maxY()&&q.minZ()>=captured.minZ()&&q.maxZ()<=captured.maxZ();}
            public boolean safe(Box q){return known(q);}
            public List<Box> collisions(Box q){return shapes.stream().filter(b->b.intersects(q)).toList();}
            public Surface surface(double x,double y,double z){return new Surface(.6,true);}
            public boolean modernMovement(){return modern;}
        };
    }
    static void overlapping(boolean modern,double spacing,int turns){
        var scene=scene(8,spacing,turns,-6);scene.world().modern=modern;
        State live=scene.start();Terrain terrain=capture(scene.world(),live,modern);Goal goal=scene.goals().getFirst();
        Result route=ParkourPlanner.search(terrain,P,live,scene.goals(),O);int ticks=0,handoffs=0;
        while(!goal.reached(live)&&handoffs++<40){
            require(!route.actions().isEmpty(),"Overlapping neo route makes progress / "+modern+" / "+spacing+" / "+turns);
            int anchor=ParkourPrediction.anchor(route.states(),0,ParkourPrediction.leadTicks(18,8,350));
            State predicted=route.states().get(anchor);
            require(predicted.onGround(),"Do not split the prepared jump with an airborne standing search");
            Terrain nextTerrain=ParkourPrediction.covered(terrain,P,predicted,goal)?terrain:capture(scene.world(),predicted,modern);
            Result ahead=goal.reached(predicted)?null:ParkourPlanner.search(nextTerrain,P,predicted,scene.goals(),O);
            for(int i=0;i<anchor;i++){
                live=ParkourPhysics.step(terrain,P,live,route.actions().get(i));require(live!=null,"Valid overlapping prefix");ticks++;
            }
            require(ParkourPrediction.exactStart(predicted,live),"Exact position, momentum and cooldown at the early handoff");
            if(goal.reached(live))break;
            require(ahead!=null&&!ahead.actions().isEmpty(),"Continuation ready before arrival; no inserted idle ticks / "+modern+" / "+spacing+" / "+turns+" / "+live+" / "+(ahead==null?null:ahead.reason()));
            require(ahead.expanded()<=O.maxNodes()&&ahead.actions().size()<=O.horizon(),"Overlapping route budgets");
            route=ahead;terrain=nextTerrain;
        }
        require(goal.reached(live),"Eight neos finish through early overlapping local snapshots / "+modern+" / "+spacing+" / "+turns);
        System.out.println("NEO_OVERLAP modern="+modern+" gap="+(spacing-3)+" rotation="+(turns*90)+" ticks="+ticks+" handoffs="+handoffs);
    }
    static void boundedDistantQuery(){
        int[] queries={0};
        Terrain limited=new Terrain(){
            public List<Box> collisions(Box q){
                queries[0]++;
                require(q.maxX()-q.minX()<=44&&q.maxZ()-q.minZ()<=44,"Distant coordinates must not expand collision scans");
                require((q.maxX()-q.minX())*(q.maxZ()-q.minZ())<=400,"Bounded local scan area");
                return List.of();
            }
            public boolean known(Box q){return false;}
            public boolean safe(Box q){return false;}
            public Surface surface(double x,double y,double z){return new Surface(.6,true);}
        };
        for(double[] target:new double[][]{{10000000,10000000},{-10000000,-10000000},{0,10000000},{0,-10000000}})
            ParkourManeuvers.search(limited,P,ParkourSpecialCheck.start(0,0,0,0),List.of(new Goal(target[0],0,target[1],.3,.2)),O,System.nanoTime()+1_000_000_000L,6000);
        require(queries[0]==4,"Each distant target uses one bounded local obstacle query");
    }
    public static void main(String[] args){
        boundedDistantQuery();
        int scenes=0;
        for(boolean modern:new boolean[]{false,true}){
            for(int turns=0;turns<4;turns++){
                check(scene(1,8,turns,-6),modern);scenes++;
                check(scene(1,8,turns,-4),modern);scenes++;
                for(int count:new int[]{2,3})for(double spacing:new double[]{7,8,9}){check(scene(count,spacing,turns,-6),modern);scenes++;}
            }
            check(scene(8,8,0,-6),modern);scenes++;
            finiteSnapshots(modern);scenes++;
            for(int turns=0;turns<4;turns++)for(double spacing:new double[]{7,8,9}){overlapping(modern,spacing,turns);scenes++;}
        }
        System.out.println("NEO_CHAIN_PASS "+scenes+" ordinary-ground single-checkpoint replays");
    }
}
