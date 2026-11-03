package com.cheatprogram.parkour;
import com.cheatprogram.parkour.ParkourPhysics.State;
import com.cheatprogram.parkour.ParkourPlanner.Goal;
import java.util.List;

/** Bounded lookahead preserves ordered checkpoints without stopping at each one. */
public final class ParkourCourse {
    private ParkourCourse(){}
    public static List<Goal> leg(List<Goal> points,int index){return List.of(points.get(index));}
    public static List<Goal> window(List<Goal> points,int index){return window(points,index,3);}
    public static List<Goal> window(List<Goal> points,int index,int maximum){
        if(index<0||index>points.size()||maximum<1)throw new IllegalArgumentException("Invalid course window");
        return List.copyOf(points.subList(index,index+Math.min(maximum,points.size()-index)));
    }
    /** Completed courses start a fresh run; interrupted courses resume in order. */
    public static int resume(int checkpoint,int size,boolean unchanged){
        if(size<0||checkpoint<0)throw new IllegalArgumentException("Invalid course progress");
        return unchanged&&checkpoint<size?checkpoint:0;
    }
    /** Reconcile an externally returned position with earlier route progress.
     * Used on resume or a large observed correction, never for ordinary runup. */
    public static int rewind(List<Goal> points,int checkpoint,State actual,State reference,State origin){
        if(checkpoint<=0||checkpoint>points.size()||actual==null||!actual.onGround())return checkpoint;
        if(reference!=null&&separation(actual,reference)<.25)return checkpoint;
        int active=Math.min(checkpoint,points.size()-1);
        double current=pointDistance(points.get(active),actual);
        if(active>0)current=Math.min(current,segmentDistance(points.get(active-1),points.get(active),actual));
        int selected=checkpoint;double best=Double.POSITIVE_INFINITY;
        if(origin!=null){
            Goal first=new Goal(origin.x(),origin.y(),origin.z(),.3,.25);
            double d=Math.min(pointDistance(first,actual),segmentDistance(first,points.getFirst(),actual));
            if(d<=1.1){best=d;selected=0;}
        }
        for(int i=0;i<checkpoint;i++){
            double d=pointDistance(points.get(i),actual);int target=advance(points,i,actual);
            if(target<checkpoint&&d<=1.1&&(d<best-1e-6||Math.abs(d-best)<1e-6&&target>selected)){best=d;selected=target;}
            if(i+1<checkpoint){
                d=segmentDistance(points.get(i),points.get(i+1),actual);
                if(d<=1.1&&(d<best-1e-6||Math.abs(d-best)<1e-6&&i+1>selected)){best=d;selected=i+1;}
            }
        }
        // At crossings or overlapping legs keep the current, equally plausible
        // progress instead of jumping to an unrelated earlier traversal.
        return selected<checkpoint&&best+.35<current?selected:checkpoint;
    }
    private static double separation(State a,State b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    private static double pointDistance(Goal g,State a){
        double dy=Math.abs(a.y()-g.y());if(dy>Math.max(.5,g.height()))return Double.POSITIVE_INFINITY;
        double dx,dz;
        if(g.region()!=null){var b=g.region();dx=Math.max(0,Math.max(b.minX()-.3-a.x(),a.x()-b.maxX()-.3));dz=Math.max(0,Math.max(b.minZ()-.3-a.z(),a.z()-b.maxZ()-.3));}
        else{double length=Math.hypot(a.x()-g.x(),a.z()-g.z());dx=Math.max(0,length-g.radius());dz=0;}
        return Math.sqrt(dx*dx+dz*dz+dy*dy*4);
    }
    private static double segmentDistance(Goal a,Goal b,State s){
        double dx=b.x()-a.x(),dz=b.z()-a.z(),length=dx*dx+dz*dz;
        if(length<.25||length>1024)return Double.POSITIVE_INFINITY;
        double t=((s.x()-a.x())*dx+(s.z()-a.z())*dz)/length;
        if(t<0||t>1)return Double.POSITIVE_INFINITY;
        double dy=Math.abs(s.y()-(a.y()+(b.y()-a.y())*t));if(dy>.5)return Double.POSITIVE_INFINITY;
        return Math.sqrt(Math.pow(s.x()-a.x()-dx*t,2)+Math.pow(s.z()-a.z()-dz*t,2)+dy*dy*4);
    }
    /** Advance only through regions actually reached in order; duplicate regions need no idle tick. */
    public static int advance(List<Goal> points,int index,State actual){
        if(index<0||index>points.size())throw new IllegalArgumentException("Invalid checkpoint index");
        while(index<points.size()&&actual.onGround()&&points.get(index).reached(actual))index++;
        return index;
    }
    /** Account for a fast grounded stride crossing a small region between ticks.
     * Airborne passes, height changes and long external corrections do not count. */
    public static int advance(List<Goal> points,int index,State previous,State actual){
        if(index<0||index>points.size())throw new IllegalArgumentException("Invalid checkpoint index");
        if(previous!=null&&actual.onGround()&&Math.hypot(actual.x()-previous.x(),actual.z()-previous.z())<=1.25){
            // Native collision resolves Y before X/Z: a one-tick edge contact
            // can end just beyond the support, although its landing footprint
            // touched the selected top face before horizontal movement.
            State contact=new State(previous.x(),actual.y(),previous.z(),actual.vx(),actual.vy(),actual.vz(),actual.yaw(),true,actual.sprinting(),actual.jumpCooldown(),actual.tick(),actual.fallDistance(),actual.sprintBlocked());
            while(index<points.size()&&points.get(index).region()!=null
                    &&(points.get(index).reached(actual)||points.get(index).reached(contact)))index++;
        }
        if(previous==null||!previous.onGround()||!actual.onGround()||Math.abs(previous.y()-actual.y())>1e-5)
            return advance(points,index,actual);
        double dx=actual.x()-previous.x(),dz=actual.z()-previous.z(),length=dx*dx+dz*dz;
        if(length<1e-12||length>1.5625)return advance(points,index,actual);
        double after=0;
        while(index<points.size()){
            Goal g=points.get(index);if(Math.abs(actual.y()-g.y())>g.height())break;
            if(g.region()!=null){
                if(!g.reached(actual))break;index++;continue;
            }
            double ox=previous.x()-g.x(),oz=previous.z()-g.z(),b=ox*dx+oz*dz;
            double discriminant=b*b-length*(ox*ox+oz*oz-g.radius()*g.radius());
            if(discriminant<0)break;
            double low=(-b-Math.sqrt(discriminant))/length,high=(-b+Math.sqrt(discriminant))/length;
            double entered=Math.max(after,Math.max(0,low));
            if(entered>1||entered>high)break;
            after=entered;index++;
        }
        return index;
    }
    public static int reachedNext(List<Goal> points,int index,State actual){
        return index<points.size()&&actual.onGround()&&points.get(index).reached(actual)?index+1:index;
    }
}
