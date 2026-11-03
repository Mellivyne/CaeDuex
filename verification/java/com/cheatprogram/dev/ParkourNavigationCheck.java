package com.cheatprogram.dev;

import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Final-only maze and environmental routes, replayed through real planner physics. */
public final class ParkourNavigationCheck {
    private static final Parameters P=Parameters.defaults();
    private static final Options O=new Options(100,100000,96,350_000_000L,true,true,true,25);
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static State start(double x,double y,double z){return new State(x,y,z,0,-.0784000015258789,0,0,true,false,0,0);}
    private static final class MapTerrain implements Terrain {
        final List<Box> blocks=new ArrayList<>(),fluids=new ArrayList<>(),hazards=new ArrayList<>();
        final Map<Long,List<Box>> buckets=new HashMap<>();
        boolean modern;double bound=80;Box captured;
        private static long key(int x,int y,int z){return ((long)(x+128)<<32)|((long)(y+32)<<24)|(z+128);}
        MapTerrain block(double x,double y,double z,double w,double h,double d){
            Box box=new Box(x,y,z,x+w,y+h,z+d);blocks.add(box);
            for(int by=(int)Math.floor(y);by<Math.ceil(y+h);by++)for(int bz=(int)Math.floor(z);bz<Math.ceil(z+d);bz++)for(int bx=(int)Math.floor(x);bx<Math.ceil(x+w);bx++)buckets.computeIfAbsent(key(bx,by,bz),k->new ArrayList<>()).add(box);
            return this;
        }
        MapTerrain water(double x,double y,double z,double w,double h,double d){fluids.add(new Box(x,y,z,x+w,y+h,z+d));return this;}
        public boolean known(Box q){return q.minX()>-bound&&q.maxX()<bound&&q.minZ()>-bound&&q.maxZ()<bound&&q.minY()>-5&&q.maxY()<10
            &&(captured==null||q.minX()>=captured.minX()&&q.maxX()<captured.maxX()&&q.minZ()>=captured.minZ()&&q.maxZ()<captured.maxZ());}
        public boolean safe(Box q){return known(q)&&fluids.stream().noneMatch(b->ParkourEnvironment.touchesFluid(q,b))&&hazards.stream().noneMatch(q::intersects);}
        public List<Box> collisions(Box q){
            Set<Box> found=new LinkedHashSet<>();
            for(int y=(int)Math.floor(q.minY());y<=Math.floor(q.maxY());y++)for(int z=(int)Math.floor(q.minZ());z<=Math.floor(q.maxZ());z++)for(int x=(int)Math.floor(q.minX());x<=Math.floor(q.maxX());x++)for(Box b:buckets.getOrDefault(key(x,y,z),List.of()))if(q.intersects(b))found.add(b);
            return List.copyOf(found);
        }
        public Surface surface(double x,double y,double z){return new Surface(.6,true);}
        public boolean modernMovement(){return modern;}
    }
    private static MapTerrain serpent(){
        var t=new MapTerrain().block(0,-1,0,14,1,25).block(0,0,0,1,4,25).block(13,0,0,1,4,25)
            .block(0,0,0,14,4,1).block(0,0,24,14,4,1);
        for(int row=0;row<5;row++){
            int z=4+row*4;
            if(row%2==0)t.block(0,0,z,11,4,1);else t.block(3,0,z,11,4,1);
        }
        // A tempting dead-end branch near the destination direction.
        t.block(6,0,1,1,4,2);return t;
    }
    private static MapTerrain branchingMaze(){
        int rooms=15,size=rooms*2+1;boolean[][] open=new boolean[size][size],seen=new boolean[rooms][rooms];
        var random=new Random(20261101);var stack=new ArrayDeque<int[]>();stack.push(new int[]{0,0});seen[0][0]=true;open[1][1]=true;
        while(!stack.isEmpty()){
            int[] current=stack.peek();var choices=new ArrayList<int[]>();
            for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){int x=current[0]+direction[0],z=current[1]+direction[1];if(x>=0&&z>=0&&x<rooms&&z<rooms&&!seen[x][z])choices.add(new int[]{x,z});}
            if(choices.isEmpty()){stack.pop();continue;}
            int[] next=choices.get(random.nextInt(choices.size()));seen[next[0]][next[1]]=true;open[next[0]*2+1][next[1]*2+1]=true;open[current[0]+next[0]+1][current[1]+next[1]+1]=true;stack.push(next);
        }
        var t=new MapTerrain().block(0,-1,0,size,1,size);
        for(int z=0;z<size;z++)for(int x=0;x<size;x++)if(!open[x][z])t.block(x,0,z,1,4,1);
        return t;
    }
    private static MapTerrain longEscape(){
        return new MapTerrain().block(-12,-1,-12,24,1,58).block(-4,0,-5,1,4,44)
            .block(3,0,-5,1,4,44).block(-4,0,38,8,4,1);
    }
    private static void replay(String name,MapTerrain t,State state,Goal goal,int maximum){
        int point=0,total=0,windows=0;double travel=0,side=0;
        for(;windows<maximum&&point==0;windows++){
            Result route=ParkourPlanner.search(t,P,state,List.of(goal),O);
            require(route.expanded()<=O.maxNodes(),name+" respects node budget");
            require(!route.actions().isEmpty(),name+" stalled at "+state.x()+","+state.z()+" reason="+route.reason());
            int i=0;
            for(Action action:route.actions()){
                State next=ParkourPhysics.step(t,P,state,action);require(next!=null,name+" emitted unsafe action");
                State predicted=route.states().get(++i);
                require(Math.hypot(next.x()-predicted.x(),next.z()-predicted.z())<1e-8&&Math.abs(next.y()-predicted.y())<1e-8,name+" exact tick replay");
                require(t.collisions(ParkourPhysics.bounds(next,P).inflate(-1e-6)).isEmpty(),name+" cannot cross solid walls");
                travel+=Math.hypot(next.x()-state.x(),next.z()-state.z());side=Math.max(side,Math.abs(next.x()));
                point=ParkourCourse.advance(List.of(goal),point,state,next);state=next;total++;
            }
        }
        System.out.printf(Locale.ROOT,"NAVIGATION %s modern=%s reached=%s windows=%d ticks=%d travel=%.2f side=%.2f%n",name,t.modern,point==1,windows,total,travel,side);
        require(point==1,name+" final-only route did not complete");
    }
    private static void rollingScan(String name,MapTerrain terrain,State state,Goal goal,int minimumExpansions){
        int radius=18,point=0,widened=0,total=0;
        double[] expansion=null;
        var memory=new ParkourNavigationMemory();
        for(int window=0;window<90&&point==0;window++){
            if(expansion!=null||terrain.captured==null||!ParkourPrediction.covered(terrain,P,state,goal)){
                double[] center=expansion!=null?expansion:ParkourPrediction.scanCenter(state,goal,radius);expansion=null;
                terrain.captured=new Box(Math.floor(center[0])-radius-1,-5,Math.floor(center[2])-radius-1,Math.floor(center[0])+radius+2,10,Math.floor(center[2])+radius+2);
            }
            Result route=ParkourPlanner.search(terrain,P,state,List.of(goal),O);
            if(route.actions().isEmpty()){
                int next=ParkourEnvironment.widerScan(radius,true);require(next>radius,"Partial scan cannot loop forever at its hard bound");
                expansion=new double[]{(terrain.captured.minX()+terrain.captured.maxX()-1)/2,0,(terrain.captured.minZ()+terrain.captured.maxZ()-1)/2};radius=next;widened++;continue;
            }
            for(Action action:route.actions()){
                State next=ParkourPhysics.step(terrain,P,state,action);require(next!=null,"Rolling maze prefix stays within captured terrain");
                point=ParkourCourse.advance(List.of(goal),point,state,next);state=next;total++;
            }
            if(point==0&&!route.reached()&&memory.repeated(point,state)){
                int next=ParkourEnvironment.widerScan(radius,true);require(next>radius,"Repeated prefixes must stop retrying the same scope");
                expansion=new double[]{(terrain.captured.minX()+terrain.captured.maxX()-1)/2,0,(terrain.captured.minZ()+terrain.captured.maxZ()-1)/2};radius=next;widened++;
            }
        }
        System.out.println("NAVIGATION_SCAN_RESULT "+name+" point="+point+" radius="+radius+" expansions="+widened+" position="+state.x()+","+state.z());
        require(point==1&&widened>=minimumExpansions,"One distant endpoint completes from the default local scan after automatic widening");
        System.out.println("NAVIGATION_ROLLING_SCAN_PASS "+name+" modern="+terrain.modern+" widening="+widened+" radius="+radius+" ticks="+total);
    }
    public static void main(String[] args){
        Box water=ParkourEnvironment.fluid(0,0,0,8/9.0);
        require(ParkourEnvironment.touchesFluid(new Box(.2,.5,.2,.8,2.3,.8),water),"Submerged feet are not dry support");
        require(!ParkourEnvironment.touchesFluid(new Box(.2,1,.2,.8,2.8,.8),water),"Waterlogged upper surface is dry at the actual water height");
        require(!ParkourEnvironment.touchesFluid(new Box(.2,.9,.2,.8,2.7,.8),water),"Thin support above water is not rejected by its block cell");
        require(!ParkourEnvironment.touchesFluid(new Box(1,0,.2,1.6,1.8,.8),water),"A dry bank touching the water cell boundary remains dry");
        require(!ParkourEnvironment.sameFluid(null,water,new Box(.2,.5,.2,.8,2.3,.8)),"New water in the route invalidates its snapshot");
        require(ParkourEnvironment.sameFluid(null,water,new Box(.2,1,.2,.8,2.8,.8)),"Water change below a dry bridge does not invalidate it");
        int radius=18;for(int expected:new int[]{26,34,40,40}){radius=ParkourEnvironment.widerScan(radius,true);require(radius==expected,"Gradual bounded scan expansion");}
        require(ParkourEnvironment.widerScan(18,false)==18,"User can disable scan expansion");
        var memory=new ParkourNavigationMemory();require(!memory.repeated(0,start(1,0,1))&&!memory.repeated(0,start(5,0,5))&&memory.repeated(0,start(1.2,0,1.1)),"Returning to an exhausted prefix area triggers wider routing");
        require(!memory.repeated(1,start(1,0,1)),"A newly reached user checkpoint starts independent navigation history");
        require(ParkourGuide.build(longEscape(),P,start(0,0,34),new Goal(0,0,40,.4,.2),true,System.nanoTime()+500_000_000L)!=null,
            "A known maze exit requiring retreat beyond 36 blocks must not be discarded");
        for(boolean modern:new boolean[]{false,true}){
            MapTerrain maze=serpent();maze.modern=modern;
            replay("serpentine-final-only",maze,start(1.5,0,1.5),new Goal(1.5,0,22.5,.4,.2),20);
            MapTerrain escape=longEscape();escape.modern=modern;
            replay("long-U-retreat-final-only",escape,start(0,0,34),new Goal(0,0,40,.4,.2),20);
            MapTerrain branches=branchingMaze();branches.modern=modern;
            replay("branching-15x15-final-only",branches,start(1.5,0,1.5),new Goal(29.5,0,29.5,.4,.2),70);
            MapTerrain rolling=branchingMaze();rolling.modern=modern;rollingScan("branching",rolling,start(1.5,0,1.5),new Goal(29.5,0,29.5,.4,.2),0);
            MapTerrain retreat=longEscape();retreat.modern=modern;rollingScan("long-retreat",retreat,start(0,0,34),new Goal(0,0,40,.4,.2),3);
            MapTerrain pond=new MapTerrain().block(-10,-1,-4,20,1,22).water(-4,0,3,8,.8888889,5);pond.modern=modern;
            replay("pond-dry-bank",pond,start(0,0,0),new Goal(0,0,13,.4,.2),10);
            MapTerrain bridge=new MapTerrain().block(-2,-1,-2,4,1,4).water(-2,-1,2,4,.8888889,10).block(-.5,0,2,1,.125,10).block(-2,-1,12,4,1,4);bridge.modern=modern;
            replay("thin-bridge-over-water",bridge,start(0,0,0),new Goal(0,0,14,.4,.2),10);
            MapTerrain hazard=new MapTerrain().block(-10,-1,-4,20,1,18);hazard.hazards.add(new Box(-3,0,3,3,1,7));hazard.modern=modern;
            replay("dangerous-floor-detour",hazard,start(0,0,0),new Goal(0,0,10,.4,.2),10);
        }
        System.out.println("NAVIGATION_PASS final-only maze, water banks, thin bridge, hazards and bounded search expansion");
    }
}
