package com.cheatprogram.dev;
import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Standalone fixtures; these are simulations, not measured gameplay records. */
public final class ParkourPhysicsCheck {
    public static final Parameters P=Parameters.defaults();
    public static final class TerrainFixture implements Terrain {
        final List<Box> blocks=new ArrayList<>();boolean unsafe,modern;double friction=.6;
        public TerrainFixture block(double x,double y,double z,double w,double h,double d){blocks.add(new Box(x,y,z,x+w,y+h,z+d));return this;}
        public List<Box> collisions(Box query){return blocks.stream().filter(b->b.intersects(query)).toList();}
        public boolean known(Box q){return q.minX()>-100&&q.maxX()<100&&q.minY()>-8&&q.maxY()<16&&q.minZ()>-100&&q.maxZ()<100;}
        public boolean safe(Box q){return !unsafe&&known(q);}
        public Surface surface(double x,double y,double z){return new Surface(friction,!unsafe);}
        public boolean modernMovement(){return modern;}
    }
    static State initial(double x,double y,double z){return new State(x,y,z,0,-.0784000015258789,0,0,true,false,0,0);}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static State step(Terrain t,State s,Action a){State n=ParkourPhysics.step(t,P,s,a);require(n!=null,"Unexpected invalid physics state");return n;}
    public static void main(String[] args){
        TerrainFixture flat=new TerrainFixture().block(-20,-1,-20,40,1,40);
        State s=initial(0,0,0);Action walk=new Action(0,1,0,false,false);
        State first=step(flat,s,walk);require(Math.abs(first.z()-.09800001)<1e-6,"Ground acceleration / input damping");require(first.onGround(),"Ground support");
        for(int i=0;i<80;i++)s=step(flat,s,walk);
        require(Math.abs(s.vz()-.11786)<.001,"Walking terminal drag");
        State jump=step(flat,initial(0,0,0),new Action(0,1,0,true,true));
        require(Math.abs(jump.y()-.42f)<1e-8,"Jump impulse");require(Math.abs(jump.z()-.3274)<1e-5,"Sprint jump impulse + ground acceleration");require(jump.jumpCooldown()==10,"Jump cooldown");
        State blocked=new State(0,0,0,0,-.0784,0,0,true,true,0,0,0,true);
        State blockedJump=step(flat,blocked,new Action(0,1,0,true,true));
        require(!blockedJump.sprinting()&&Math.abs(blockedJump.z()-.09800001)<1e-6,"Previous major collision cancels sprint speed and sprint-jump impulse");
        require(!ParkourPrediction.exactStart(initial(0,0,0),blocked),"Collision sprint state is part of exact adoption");
        TerrainFixture graze=new TerrainFixture().block(-3,-1,-3,6,1,8).block(.5,0,-3,1,3,8);
        State grazing=step(graze,new State(.19,0,0,0,-.0784,0,-5,true,false,0,0),new Action(-5,1,0,false,true));
        require(!grazing.sprintBlocked(),"A minor glancing collision preserves native sprint eligibility");
        double peak=jump.y();int air=1;while(!jump.onGround()&&air<40){jump=step(flat,jump,new Action(0,0,0,false,false));peak=Math.max(peak,jump.y());air++;}
        require(peak>1.24&&peak<1.26&&air==12,"Discrete jump height/airtime: "+peak+" / "+air);
        TerrainFixture low=new TerrainFixture().block(-5,-1,-5,10,1,10).block(-5,2,-5,10,1,10);
        s=step(low,initial(0,0,0),new Action(0,0,0,true,false));require(s.y()<=.200001,"Head collision");
        TerrainFixture wall=new TerrainFixture().block(-5,-1,-5,10,1,10).block(-2,0,1,4,3,1);
        s=initial(0,0,0);for(int i=0;i<25;i++)s=step(wall,s,walk);require(s.z()<=.700001&&Math.abs(s.vz())<1e-8,"Wall cannot be crossed");
        TerrainFixture slab=new TerrainFixture().block(-5,-1,-5,10,1,10).block(-2,0,1,4,.5,3);
        s=initial(0,0,0);for(int i=0;i<14;i++)s=step(slab,s,walk);require(Math.abs(s.y()-.5)<1e-6&&s.z()>1,"Step up slab");
        flat.unsafe=true;require(ParkourPhysics.step(flat,P,s,walk)==null,"Unsupported rejected");flat.unsafe=false;
        require(ParkourPhysics.step(flat,P,initial(101,0,0),walk)==null,"Unknown rejected");
        State diag=step(flat,initial(0,0,0),new Action(0,1,1,false,false));flat.modern=true;State modern=step(flat,initial(0,0,0),new Action(0,1,1,false,false));
        require(Math.abs(Math.hypot(diag.vx(),diag.vz())-Math.hypot(modern.vx(),modern.vz()))<1e-7,"Modern square movement normalization");
        for(int i=0;i<1000;i++){int c=ParkourColors.pride(i/1000.0);require((c&0xff000000)==0,"Color range");}
        require(ParkourColors.pride(0)==0x5bcefa&&ParkourColors.pride(.5)==0xffffff,"Palette endpoints");
        System.out.println("PHYSICS_PASS walking sprint-jump gravity drag head-hit wall slab unknown unsupported modern-input OKLab");
        benchmark("flat",new TerrainFixture().block(-4,-1,-4,8,1,20),initial(0,0,0),List.of(new Goal(0,0,8,.35,.2)));
        benchmark("gap-2",new TerrainFixture().block(-2,-1,-3,4,1,5).block(-2,-1,4,4,1,8),initial(0,0,0),List.of(new Goal(0,0,7,.35,.2)));
        benchmark("diagonal",new TerrainFixture().block(-4,-1,-4,20,1,20),initial(0,0,0),List.of(new Goal(5,0,5,.35,.2)));
        TerrainFixture ice=new TerrainFixture().block(-4,-1,-4,8,1,30);ice.friction=.98;
        benchmark("ice",ice,initial(0,0,0),List.of(new Goal(0,0,10,.4,.2)));
        benchmark("ordered",new TerrainFixture().block(-4,-1,-4,20,1,20),initial(0,0,0),List.of(new Goal(0,0,4,.5,.2),new Goal(4,0,4,.5,.2)));
        benchmark("narrow",new TerrainFixture().block(-.5,-1,-2,1,1,3).block(-.5,-1,3,1,1,3),initial(0,0,0),List.of(new Goal(0,0,4.5,.2,.2)));
        benchmark("elevated",new TerrainFixture().block(-2,-1,-3,4,1,5).block(-2,-1,3,4,2,6),initial(0,0,0),List.of(new Goal(0,1,6,.35,.2)));
        State fall=new State(0,5,0,0,-.9,0,0,false,false,0,0,2.8);
        require(ParkourPhysics.step(new TerrainFixture().block(-10,-1,-10,20,1,20),P,fall,walk)==null,"Damaging drop rejected");
        TerrainFixture ledge=new TerrainFixture().block(-.5,-1,-.5,1,1,1);ledge.friction=.98;
        State sliding=new State(0,0,.45,0,-.0784,.05,0,true,false,0,0);
        Result unsafeArrival=ParkourPlanner.search(ledge,P,sliding,List.of(new Goal(0,0,.45,.1,.1)),new Options(30,10000,16,100_000_000L,true,true,true,25));
        require(!unsafeArrival.reason().equals("ALREADY_REACHED"),"Already-reached endpoint must not release into an unsafe slide");
        Result bounded=ParkourPlanner.search(flat,P,initial(0,0,0),List.of(new Goal(99,0,99,.3,.2)),new Options(120,1,16,1_000_000_000L,true,true,true,25));require(bounded.expanded()<=1,"Node budget");
        Thread.currentThread().interrupt();Result cancel=ParkourPlanner.search(flat,P,initial(0,0,0),List.of(new Goal(0,0,5,.3,.2)),new Options(120,10000,16,1_000_000_000L,true,true,true,25));Thread.interrupted();require(cancel.reason().equals("CANCELLED"),"Cancellation");
        System.out.println("PLANNER_BUDGET_PASS node cancellation");
    }
    static void benchmark(String name,TerrainFixture t,State s,List<Goal> goals){
        Result r=ParkourPlanner.search(t,P,s,goals,new Options(100,300000,80,3_000_000_000L,true,true,true,45));
        State replay=s;int point=0;double length=0;for(Action a:r.actions()){State n=step(t,replay,a);length+=Math.sqrt(Math.pow(n.x()-replay.x(),2)+Math.pow(n.y()-replay.y(),2)+Math.pow(n.z()-replay.z(),2));replay=n;while(point<goals.size()&&goals.get(point).reached(replay))point++;}
        require(r.reached()&&point==goals.size(),name+" route failed: "+r.reason()+" points "+point+" nodes "+r.expanded());
        State base=s;int basicTicks=0;while(basicTicks<150&&!goals.getLast().reached(base)){float yaw=(float)Math.toDegrees(Math.atan2(-(goals.getLast().x()-base.x()),goals.getLast().z()-base.z()));State n=ParkourPhysics.step(t,P,base,new Action(yaw,1,0,base.onGround(),true));if(n==null)break;base=n;basicTicks++;}
        String grid=gridBaseline(t,s,goals);
        System.out.printf(Locale.ROOT,"SIMULATED %s success=true ticks=%d length=%.3f nodes=%d searchMs=%.1f basicJumpTicks=%s gridAStarTicks=%s%n",name,r.actions().size(),length,r.expanded(),r.elapsedNanos()/1e6,goals.size()==1&&goals.getLast().reached(base)?String.valueOf(basicTicks):"not-completed",grid);
    }
    private record Grid(int x,int z){}
    private record Open(Grid cell,int cost,double score){}
    /** Ordinary four-neighbour ground A*, followed with non-jumping legal walking
     * in the SAME physics model. Intentionally has no gap-jump motion primitives. */
    static String gridBaseline(TerrainFixture t,State start,List<Goal> goals){
        State live=start;int ticks=0;
        for(Goal goal:goals){
            if(Math.abs(goal.y()-start.y())>.01)return "no-grid-path";
            Grid root=new Grid((int)Math.round(live.x()),(int)Math.round(live.z())),end=new Grid((int)Math.round(goal.x()),(int)Math.round(goal.z()));
            var open=new PriorityQueue<Open>(Comparator.comparingDouble(Open::score));var cost=new HashMap<Grid,Integer>();var parent=new HashMap<Grid,Grid>();open.add(new Open(root,0,0));cost.put(root,0);boolean found=false;int nodes=0;
            while(!open.isEmpty()&&nodes++<4000){Open o=open.remove();if(o.cost()!=cost.get(o.cell()))continue;if(o.cell().equals(end)){found=true;break;}
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){Grid n=new Grid(o.cell().x+d[0],o.cell().z+d[1]);if(Math.abs(n.x)>25||Math.abs(n.z)>25)continue;
                    boolean clear=true;for(int i=0;i<=5;i++){double f=i/5.0;Box b=ParkourPhysics.bounds(initial(o.cell().x+d[0]*f,start.y(),o.cell().z+d[1]*f),P);
                        if(!t.safe(b)||!t.collisions(b).isEmpty()||t.collisions(b.move(0,-.01,0)).isEmpty()){clear=false;break;}}
                    if(!clear)continue;int next=o.cost+1;if(next>=cost.getOrDefault(n,Integer.MAX_VALUE))continue;cost.put(n,next);parent.put(n,o.cell);open.add(new Open(n,next,next+Math.abs(n.x-end.x)+Math.abs(n.z-end.z)));
                }
            }
            if(!found)return "no-grid-path";
            var path=new ArrayList<Grid>();for(Grid g=end;g!=null;g=parent.get(g))path.add(g);Collections.reverse(path);int next=path.size()>1?1:0;
            while(ticks<300&&!goal.reached(live)){
                Grid g=path.get(next);if(Math.hypot(live.x()-g.x,live.z()-g.z)<.35&&next+1<path.size())g=path.get(++next);
                float yaw=(float)Math.toDegrees(Math.atan2(-(g.x-live.x()),g.z-live.z()));State moved=ParkourPhysics.step(t,P,live,new Action(yaw,1,0,false,false));if(moved==null)return "not-completed";live=moved;ticks++;
            }
            if(!goal.reached(live))return "not-completed";
        }
        return String.valueOf(ticks);
    }
}
