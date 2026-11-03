package com.cheatprogram.parkour;

import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.Goal;
import java.util.*;

/** Bounded collision-shape A* guidance. Its corners are hints, never movement commands. */
public final class ParkourGuide {
    private static final double GRID=.5;
    private record Key(int x,int y,int z,int air){}
    private record Node(double x,double y,double z,double cost,double score,double air,Key key,Node parent){}
    private final List<Node> path;
    private final double[] remaining;
    private final Terrain terrain;private final Parameters physics;
    private final Map<Key,Integer> nearestCache=new HashMap<>();
    private ParkourGuide(List<Node> path,Terrain terrain,Parameters physics){
        this.path=path;this.terrain=terrain;this.physics=physics;remaining=new double[path.size()];
        for(int i=path.size()-2;i>=0;i--)remaining[i]=remaining[i+1]+distance(path.get(i),path.get(i+1));
    }
    public static ParkourGuide build(Terrain terrain,Parameters p,State start,Goal goal,boolean jump,long deadline){
        if(Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline)return null;
        // Ordinary straight travel needs no guide. Physics search still handles jumps/gaps.
        // A clear horizontal ray at the destination's height is not proof of
        // a direct climb: it can float above every intervening raised support.
        if(Math.abs(start.y()-goal.y())<.05&&line(terrain,p,start.x(),start.y(),start.z(),goal.x(),goal.y(),goal.z(),false))return null;
        long now=System.nanoTime();
        ParkourGuide grounded=find(terrain,p,start,goal,jump,false,jump?now+(deadline-now)*2/3:deadline);
        if(grounded!=null||!jump)return grounded;
        now=System.nanoTime();
        ParkourGuide airborne=find(terrain,p,start,goal,true,true,now+(deadline-now)*2/3);
        return airborne!=null?airborne:supports(terrain,p,start,goal,deadline);
    }
    /** Sparse platform-top hints preserve intermediate elevation changes and
     * gaps that do not form a connected half-block walking grid. They never
     * bypass the planner's real per-tick collision/jump validation. */
    private static ParkourGuide supports(Terrain terrain,Parameters p,State start,Goal goal,long deadline){
        if(Math.hypot(goal.x()-start.x(),goal.z()-start.z())>24||System.nanoTime()>=deadline)return null;
        double maxRise=jumpHeight(p)+.001;
        var points=new ArrayList<double[]>();var seen=new HashSet<Key>();
        points.add(new double[]{start.x(),start.y(),start.z()});
        Box goalBody=body(p,goal.x(),goal.y(),goal.z());
        if(!terrain.known(goalBody)||!terrain.safe(goalBody)||!terrain.collisions(goalBody).isEmpty())return null;
        points.add(new double[]{goal.x(),goal.y(),goal.z()});
        Box query=new Box(Math.min(start.x(),goal.x())-5,Math.min(start.y(),goal.y())-3,Math.min(start.z(),goal.z())-5,
            Math.max(start.x(),goal.x())+5,Math.max(start.y(),goal.y())+2,Math.max(start.z(),goal.z())+5);
        List<Box> surfaces=terrain.collisions(query);if(surfaces.size()>512)return null;
        for(Box surface:surfaces){
            if(System.nanoTime()>=deadline||Thread.currentThread().isInterrupted())return null;
            double y=surface.maxY();
            for(double[] point:new double[][]{
                    {(surface.minX()+surface.maxX())/2,y,(surface.minZ()+surface.maxZ())/2},
                    {Math.max(surface.minX()+.05,Math.min(surface.maxX()-.05,start.x())),y,Math.max(surface.minZ()+.05,Math.min(surface.maxZ()-.05,start.z()))},
                    {Math.max(surface.minX()+.05,Math.min(surface.maxX()-.05,goal.x())),y,Math.max(surface.minZ()+.05,Math.min(surface.maxZ()-.05,goal.z()))}}){
                Key key=new Key((int)Math.round(point[0]*32),(int)Math.round(y*32),(int)Math.round(point[2]*32),0);
                if(!seen.add(key))continue;Box b=body(p,point[0],y,point[2]);
                if(terrain.known(b)&&terrain.safe(b)&&terrain.collisions(b).isEmpty()&&!terrain.collisions(b.move(0,-.00001,0)).isEmpty())points.add(point);
                if(points.size()>256)return null;
            }
        }
        var open=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score));
        double[] costs=new double[points.size()];Arrays.fill(costs,Double.POSITIVE_INFINITY);costs[0]=0;
        open.add(new Node(start.x(),start.y(),start.z(),0,estimate(start.x(),start.y(),start.z(),goal),0,new Key(0,0,0,0),null));
        while(!open.isEmpty()&&System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted()){
            Node node=open.remove();int index=node.key().x();if(node.cost()>costs[index]+1e-8)continue;
            if(index==1)return finishSupports(node,terrain,p);
            for(int i=1;i<points.size();i++){
                if(System.nanoTime()>=deadline)return null;
                double[] next=points.get(i);double rise=next[1]-node.y(),length=Math.hypot(next[0]-node.x(),next[2]-node.z());
                if(length<.05||length>4.4||rise>maxRise||!p.allowDamagingFalls()&&rise< -2.5)continue;
                double cost=node.cost()+length+Math.abs(rise)*.8;
                if(cost>=costs[i]-1e-8||!line(terrain,p,node.x(),node.y(),node.z(),next[0],next[1],next[2],false))continue;
                costs[i]=cost;open.add(new Node(next[0],next[1],next[2],cost,cost+estimate(next[0],next[1],next[2],goal),0,new Key(i,0,0,0),node));
            }
        }
        return null;
    }
    private static ParkourGuide finishSupports(Node last,Terrain terrain,Parameters p){
        var sparse=new ArrayList<Node>();for(Node n=last;n!=null;n=n.parent())sparse.add(n);Collections.reverse(sparse);
        var dense=new ArrayList<Node>();dense.add(sparse.getFirst());
        for(int i=1;i<sparse.size();i++){
            Node a=sparse.get(i-1),b=sparse.get(i);int steps=Math.max(1,(int)Math.ceil(distance(a,b)/.25));
            for(int j=1;j<=steps;j++){
                double f=j/(double)steps;dense.add(new Node(a.x()+(b.x()-a.x())*f,a.y()+(b.y()-a.y())*f,a.z()+(b.z()-a.z())*f,0,0,0,new Key(0,0,0,0),null));
            }
        }
        return dense.size()>1?new ParkourGuide(dense,terrain,p):null;
    }
    private static ParkourGuide find(Terrain terrain,Parameters p,State start,Goal goal,boolean jump,boolean airRoute,long deadline){
        double spacing=airRoute?.25:GRID;
        double maxRise=(jump?jumpHeight(p):p.stepHeight())+.001;
        PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::score).thenComparingDouble(Node::cost));
        Map<Key,Double> costs=new HashMap<>();Map<Long,List<Double>> floors=new HashMap<>();
        Key rootKey=new Key(0,(int)Math.round(start.y()*16),0,0);
        Node root=new Node(start.x(),start.y(),start.z(),0,estimate(start.x(),start.y(),start.z(),goal),0,rootKey,null);
        open.add(root);costs.put(rootKey,0.0);int expanded=0;
        while(!open.isEmpty()&&expanded++<(airRoute?8000:24000)&&System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted()){
            Node n=open.remove();if(n.cost()>costs.getOrDefault(n.key(),Double.POSITIVE_INFINITY)+1e-8)continue;
            if(n.air()==0&&Math.hypot(n.x()-goal.x(),n.z()-goal.z())<=Math.max(.35,goal.radius())&&Math.abs(n.y()-goal.y())<=goal.height())return finish(n,terrain,p);
            // Snapshot.known bounds navigation. A second 36-block cutoff used
            // to discard valid corridors even inside a larger captured maze.
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                if(dx==0&&dz==0)continue;
                int ix=n.key().x()+dx,iz=n.key().z()+dz;double x=start.x()+ix*spacing,z=start.z()+iz*spacing;
                long cache=((long)ix<<32)^(iz&0xffffffffL);
                List<Double> heights=floors.get(cache);
                if(heights==null){
                    heights=heights(terrain,p,x,z,Math.min(start.y(),goal.y())-3,Math.max(start.y(),goal.y())+3);floors.put(cache,heights);
                }
                List<Double> candidates=heights;
                if(airRoute&&!heights.contains(n.y())){candidates=new ArrayList<>(heights);candidates.add(n.y());}
                for(double y:candidates){
                    double rise=y-n.y();if(rise>maxRise||!p.allowDamagingFalls()&&rise< -2.5)continue;
                    if(!line(terrain,p,n.x(),n.y(),n.z(),x,y,z,false))continue;
                    double stride=Math.hypot(dx,dz)*spacing;
                    boolean support=!terrain.collisions(body(p,x,y,z).move(0,-.00001,0)).isEmpty();
                    double air=support?0:n.air()+stride;if(air>4.5||!airRoute&&air>0)continue;
                    if(!support&&n.air()==0){
                        // Air corners need space for the first real jump tick,
                        // including vanilla's X/Z clipping order. A geometric
                        // diagonal alone can wrongly suggest jumping after the
                        // body has already reached the face of a long wall.
                        double ux=(x-n.x())/stride,uz=(z-n.z())/stride;
                        float yaw=(float)Math.toDegrees(Math.atan2(-ux,uz));
                        State takeoff=new State(n.x(),n.y(),n.z(),ux*.15,-.0784,uz*.15,yaw,true,false,0,0);
                        State trial=ParkourPhysics.step(terrain,p,takeoff,new Action(yaw,1,0,true,true));
                        if(trial==null||Math.abs(ux)>.1&&Math.abs(trial.vx())<1e-6||Math.abs(uz)>.1&&Math.abs(trial.vz())<1e-6)continue;
                    }
                    double cost=n.cost()+stride+Math.abs(rise)*.8+(support?0:stride*.35);
                    Key key=new Key(ix,(int)Math.round(y*16),iz,(int)Math.ceil(air*2));
                    if(cost>=costs.getOrDefault(key,Double.POSITIVE_INFINITY)-1e-8)continue;
                    costs.put(key,cost);open.add(new Node(x,y,z,cost,cost+estimate(x,y,z,goal),air,key,n));
                }
            }
        }
        // An incomplete walking guide must not replace legal jump/gap search.
        return null;
    }
    private static List<Double> heights(Terrain t,Parameters p,double x,double z,double low,double high){
        double radius=p.width()/2-.00001;
        Box column=new Box(x-radius,low-1,z-radius,x+radius,high+.01,z+radius);
        // A short scan may not contain the entire height-probe column. Read
        // captured collision surfaces, then validate each actual player body.
        TreeSet<Double> out=new TreeSet<>();
        for(Box b:t.collisions(column))if(b.maxY()>=low&&b.maxY()<=high){
            double y=b.maxY();Box body=body(p,x,y,z);
            if(t.known(body)&&t.safe(body)&&t.collisions(body).isEmpty()&&!t.collisions(body.move(0,-.00001,0)).isEmpty())out.add(y);
        }
        return List.copyOf(out);
    }
    private static Box body(Parameters p,double x,double y,double z){double r=p.width()/2;return new Box(x-r,y+.000001,z-r,x+r,y+p.height(),z+r);}
    private static boolean line(Terrain t,Parameters p,double x,double y,double z,double nx,double ny,double nz,boolean ground){
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(nx-x,nz-z)/.2));double top=Math.max(y,ny);
        for(int i=0;i<=steps;i++){
            double f=i/(double)steps;Box b=body(p,x+(nx-x)*f,top,z+(nz-z)*f);
            if(!t.known(b)||!t.safe(b)||!t.collisions(b).isEmpty())return false;
            if(ground&&t.collisions(b.move(0,-.06,0)).isEmpty())return false;
        }
        return true;
    }
    private static double estimate(double x,double y,double z,Goal g){return Math.hypot(x-g.x(),z-g.z())+Math.abs(y-g.y())*.8;}
    private static double jumpHeight(Parameters p){
        double height=0,velocity=p.jumpVelocity();
        for(int i=0;i<1200&&velocity>0;i++){height+=velocity;velocity=(velocity-p.gravity())*.98f;}
        return height;
    }
    private static ParkourGuide finish(Node n,Terrain terrain,Parameters physics){var path=new ArrayList<Node>();for(;n!=null;n=n.parent())path.add(n);Collections.reverse(path);return path.size()>1?new ParkourGuide(path,terrain,physics):null;}
    private static double distance(Node a,Node b){return Math.hypot(a.x()-b.x(),a.z()-b.z())+Math.abs(a.y()-b.y())*.8;}
    private int nearest(State state){
        Key key=new Key((int)Math.round(state.x()*4),(int)Math.round(state.y()*4),(int)Math.round(state.z()*4),0);
        Integer cached=nearestCache.get(key);if(cached!=null)return cached;
        int best=0;double score=Double.POSITIVE_INFINITY;
        for(int i=0;i<path.size();i++){
            Node n=path.get(i);double d=Math.hypot(state.x()-n.x(),state.z()-n.z())+Math.abs(state.y()-n.y())*2;
            // A small path-distance tie-break avoids jumping to the far side of a wall.
            if(d<score-1e-6&&line(terrain,physics,state.x(),state.y(),state.z(),n.x(),n.y(),n.z(),false)){score=d;best=i;}
        }
        nearestCache.put(key,best);
        return best;
    }
    public double distance(State state){int i=nearest(state);Node n=path.get(i);return Math.hypot(state.x()-n.x(),state.z()-n.z())+Math.abs(state.y()-n.y())+remaining[i];}
    public Goal aim(State state){
        int i=nearest(state),next=Math.min(path.size()-1,i+2);Node n=path.get(next);
        while(next>i&&!line(terrain,physics,state.x(),state.y(),state.z(),n.x(),n.y(),n.z(),false))n=path.get(--next);
        return new Goal(n.x(),n.y(),n.z(),.3,.25);
    }
}
