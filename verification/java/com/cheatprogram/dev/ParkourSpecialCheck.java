package com.cheatprogram.dev;
import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Ordered compound scenes with real collision boxes and native input replay. */
public final class ParkourSpecialCheck {
    static final Parameters P=Parameters.defaults();
    static final Options O=new Options(100,100000,96,350_000_000L,true,true,true,25);
    static final class World implements Terrain {
        final List<Box> blocks=new ArrayList<>(),ice=new ArrayList<>(),slime=new ArrayList<>(),climbs=new ArrayList<>();
        boolean modern,water;
        World block(double x,double y,double z,double w,double h,double d){blocks.add(new Box(x,y,z,x+w,y+h,z+d));return this;}
        World ice(double x,double y,double z,double w,double h,double d){block(x,y,z,w,h,d);ice.add(blocks.getLast());return this;}
        World slime(double x,double y,double z,double w,double h,double d){block(x,y,z,w,h,d);slime.add(blocks.getLast());return this;}
        public List<Box> collisions(Box q){return blocks.stream().filter(b->b.intersects(q)).toList();}
        public boolean known(Box q){return q.minX()>-80&&q.maxX()<80&&q.minZ()>-80&&q.maxZ()<80&&q.minY()>-8&&q.maxY()<20;}
        public boolean safe(Box q){return known(q)&&(!water||q.minY()>=0);}
        static boolean in(Box b,double x,double y,double z){return x>=b.minX()&&x<b.maxX()&&y>=b.minY()&&y<b.maxY()&&z>=b.minZ()&&z<b.maxZ();}
        public Surface surface(double x,double y,double z){return new Surface(ice.stream().anyMatch(b->in(b,x,y,z))?.98:slime.stream().anyMatch(b->in(b,x,y,z))?.8:.6,true);}
        public Surface groundSurface(Box body){
            for(Box b:blocks)if(Math.abs(b.maxY()-body.minY())<1e-6&&b.intersects(body.move(0,-.00001,0)))return new Surface(ice.contains(b)?.98:slime.contains(b)?.8:.6,true);
            return Terrain.super.groundSurface(body);
        }
        public boolean slime(double x,double y,double z){return slime.stream().anyMatch(b->x>=b.minX()&&x<b.maxX()&&z>=b.minZ()&&z<b.maxZ()&&Math.abs(y-b.maxY())<.2);}
        public boolean climbable(double x,double y,double z){return climbs.stream().anyMatch(b->in(b,x,y,z));}
        public boolean modernMovement(){return modern;}
    }
    record Scene(String name,World world,State start,List<Goal> goals){}
    static State start(double x,double y,double z,float yaw){return new State(x,y,z,0,-.0784000015258789,0,yaw,true,false,0,0);}
    static Goal goal(double x,double y,double z){return new Goal(x,y,z,.3,.2);}
    static List<Scene> scenes(){
        World ladder=new World().block(-3,-1,-3,6,1,5).block(-1,0,.9,2,3,1.1).block(-.5,0,.775,1,3,.125).block(-1,2,4,2,1,4);
        ladder.climbs.add(new Box(-.5,0,0,.5,3,1));
        World lily=new World().block(-.5,0,-.5,1,.09375,1).block(-.5,0,2,1,.09375,1).block(-.5,0,4.5,1,.09375,1);lily.water=true;
        World stairs=new World().block(-2,-1,-2,4,1,3);for(int i=0;i<6;i++)stairs.block(-1,0,1+i*.75,2,.5*(i+1),.75);
        var scenes=new ArrayList<>(List.of(
            new Scene("one-wall",new World().block(-5,-1,-.5,10,1,1).block(-.5,0,-.5,1,3,1),start(-3,0,0,-90),List.of(goal(3,0,0))),
            new Scene("two-wall",new World().block(-6,-1,-.5,12,1,1).block(-1,0,-.5,2,3,1),start(-4,0,0,-90),List.of(goal(4,0,0))),
            new Scene("three-wall-ice-approach",new World().ice(-8,-1,-.5,16,1,1).block(-1.5,0,-.5,3,3,1),start(-6,0,0,-90),List.of(goal(5,0,0))),
            new Scene("alternating-neos",new World().block(-6,-1,-.7,17,1,1.4).block(-.5,0,-.7,1,3,1).block(3.5,0,-.3,1,3,1),start(-3,0,0,-90),List.of(goal(2,0,0),goal(7,0,0))),
            new Scene("zigzag-islands",new World().block(-1,-1,-1,2,1,2).block(1,-1,3,2,1,2).block(-1,-1,7,2,1,2),start(0,0,0,0),List.of(goal(2,0,4),goal(0,0,8))),
            new Scene("head-hitter-gap",new World().block(-1,-1,-2,2,1,3.5).block(-1,-1,2.5,2,1,4).block(-1,2,-1,2,1,6),start(0,0,0,0),List.of(goal(0,0,4))),
            new Scene("half-step-staircase",stairs,start(0,0,0,0),List.of(goal(0,3,5.1))),
            new Scene("fence-post-jumps",new World().block(-.125,0,-1,.25,1.5,2).block(-.125,0,3,.25,1.5,1).block(-.5,0,6,1,1.5,2),start(0,1.5,0,0),List.of(goal(0,1.5,3.5),goal(0,1.5,6.5))),
            new Scene("thin-trapdoor-chain",new World().block(-.5,0,-.5,1,.1875,1).block(-.5,0,2,1,.1875,1).block(-.5,0,4.5,1,.1875,1),start(0,.1875,0,0),List.of(goal(0,.1875,2.5),goal(0,.1875,5))),
            new Scene("lily-pad-chain",lily,start(0,.09375,0,0),List.of(goal(0,.09375,2.5),goal(0,.09375,5))),
            new Scene("ice-turns",new World().ice(-10,-1,-10,30,1,30),start(0,0,0,0),List.of(goal(0,0,5),goal(5,0,5),goal(5,0,0))),
            new Scene("ladder-to-gap",ladder,start(0,0,.475,0),List.of(goal(0,3,1.5),goal(0,3,5))),
            new Scene("slime-drop-to-exit",new World().block(-1,0,-5,2,3,4).slime(-2,-1,0,4,1,4).block(-1,0,5,2,1,4),start(0,3,-3,0),List.of(goal(0,0,2),goal(0,1,7)))
        ));
        Scene original=scenes.get(2);World rotated=original.world();
        for(int turn=1;turn<=3;turn++){
            World next=new World();
            for(Box old:rotated.blocks){Box b=new Box(-old.maxZ(),old.minY(),old.minX(),-old.minZ(),old.maxY(),old.maxX());next.blocks.add(b);if(rotated.ice.contains(old))next.ice.add(b);}
            rotated=next;
            double x=original.start().x(),z=original.start().z(),gx=original.goals().getFirst().x(),gz=original.goals().getFirst().z();
            for(int i=0;i<turn;i++){double t=x;x=-z;z=t;t=gx;gx=-gz;gz=t;}
            scenes.add(new Scene("three-wall-ice-rotation-"+(turn*90),rotated,start(x,0,z,-90+turn*90),List.of(goal(gx,0,gz))));
        }
        return scenes;
    }
    static void require(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] args){
        World edge=new World().ice(-8,-1,-.5,16,1,1);State overhang=new State(-3,0,.79,.2,-.0784,0,-90,true,false,0,0);
        require(edge.surface(-3,-.5,.79).friction()==.6&&edge.groundSurface(ParkourPhysics.bounds(overhang,P)).friction()==.98,"Actual support friction differs from an off-edge centre sample");
        State coast=ParkourPhysics.step(edge,P,overhang,new Action(-90,0,0,false,false));
        require(coast!=null&&Math.abs(coast.vx()-.2*(float).98*.91f)<1e-8,"Native ice drag remains active on a supported overhang");
        boolean report=Arrays.asList(args).contains("--report");List<String> failures=new ArrayList<>();
        for(boolean modern:new boolean[]{false,true})for(Scene scene:scenes()){
            scene.world().modern=modern;State live=scene.start();int point=0,steps=0,windows=0;
            for(;windows<12&&point<scene.goals().size();windows++){
                Result route=ParkourPlanner.search(scene.world(),P,live,ParkourCourse.window(scene.goals(),point,8),O);
                require(route.expanded()<=O.maxNodes(),"Shared search budget");
                for(Action input:route.actions()){
                    live=ParkourPhysics.step(scene.world(),P,live,input);require(live!=null,scene.name()+" invalid replay");
                    require(scene.world().collisions(ParkourPhysics.bounds(live,P).inflate(-1e-6)).isEmpty(),scene.name()+" penetrates solid geometry");
                    point=ParkourCourse.advance(scene.goals(),point,live);steps++;
                }
                if(route.actions().isEmpty())break;
            }
            boolean reached=point==scene.goals().size();System.out.println("SPECIAL "+scene.name()+" modern="+modern+" reached="+reached+" windows="+windows+" ticks="+steps);
            if(!reached)failures.add(scene.name()+"/"+modern);
        }
        if(!report)require(failures.isEmpty(),failures.toString());
        System.out.println(failures.isEmpty()?"SPECIAL_PASS compound collision scenes in both input families":"SPECIAL_FAILURES "+failures);
    }
}
