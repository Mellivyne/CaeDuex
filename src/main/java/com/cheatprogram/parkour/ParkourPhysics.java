package com.cheatprogram.parkour;

import java.util.List;
import java.util.TreeSet;

/** Tick-discrete standing-player model. No live world or Minecraft classes are used here.
 * Calibrated against LivingEntity.travelInAir/aiStep and Entity.collide, not a parabola.
 * Terrain must reject mechanics it cannot model (fluids, honey, moving shapes).
 */
public final class ParkourPhysics {
    private ParkourPhysics() { }
    public record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        public Box move(double x,double y,double z){return new Box(minX+x,minY+y,minZ+z,maxX+x,maxY+y,maxZ+z);}
        public Box expand(double x,double y,double z){return new Box(minX+Math.min(0,x),minY+Math.min(0,y),minZ+Math.min(0,z),maxX+Math.max(0,x),maxY+Math.max(0,y),maxZ+Math.max(0,z));}
        public Box inflate(double a){return new Box(minX-a,minY-a,minZ-a,maxX+a,maxY+a,maxZ+a);}
        public boolean intersects(Box b){return maxX>b.minX&&minX<b.maxX&&maxY>b.minY&&minY<b.maxY&&maxZ>b.minZ&&minZ<b.maxZ;}
    }
    public record State(double x,double y,double z,double vx,double vy,double vz,float yaw,boolean onGround,boolean sprinting,int jumpCooldown,int tick,double fallDistance,boolean sprintBlocked) {
        public State(double x,double y,double z,double vx,double vy,double vz,float yaw,boolean onGround,boolean sprinting,int jumpCooldown,int tick,double fallDistance){this(x,y,z,vx,vy,vz,yaw,onGround,sprinting,jumpCooldown,tick,fallDistance,false);}
        public State(double x,double y,double z,double vx,double vy,double vz,float yaw,boolean onGround,boolean sprinting,int jumpCooldown,int tick){this(x,y,z,vx,vy,vz,yaw,onGround,sprinting,jumpCooldown,tick,0,false);}
    }
    public record Action(float yaw,int forward,int strafe,boolean jump,boolean sprint) { }
    public record Surface(double friction,boolean supported) { }
    public interface Terrain {
        List<Box> collisions(Box query);
        boolean known(Box query);
        Surface surface(double x,double y,double z);
        boolean safe(Box query);
        default boolean modernMovement(){return false;}
        default boolean legacyStep(){return false;}
        default boolean climbable(double x,double y,double z){return false;}
        /** Native legacy support sample; feetY is the player's feet height. */
        default boolean slime(double x,double feetY,double z){return false;}
        default boolean slimeContact(Box body){return slime((body.minX+body.maxX)/2,body.minY,(body.minZ+body.maxZ)/2);}
        default Surface groundSurface(Box body){return surface((body.minX+body.maxX)/2,body.minY-.5000001,(body.minZ+body.maxZ)/2);}
        /** 26.2 uses collision-time restitution instead of legacy bounceUp. */
        default boolean fractionalBounce(){return false;}
    }
    public record Parameters(double walkingSpeed,double width,double height,double stepHeight,double gravity,double jumpVelocity,double airAcceleration,boolean allowDamagingFalls) {
        public Parameters(double speed,double width,double height,double step,double gravity,double jump,double air){this(speed,width,height,step,gravity,jump,air,false);}
        public Parameters allowingFalls(){return new Parameters(walkingSpeed,width,height,stepHeight,gravity,jumpVelocity,airAcceleration,true);}
        public static Parameters defaults(){return new Parameters(.1,.6,1.8,.6,.08,(double).42f,.02);}
    }
    private static final float[] SIN=new float[65536];
    static{for(int i=0;i<SIN.length;i++)SIN[i]=(float)Math.sin(i*Math.PI*2/65536.0);}
    private static float sin(float r){return SIN[(int)(r*10430.378f)&65535];}
    private static float cos(float r){return SIN[(int)(r*10430.378f+16384f)&65535];}
    public static Box bounds(State s,Parameters p){double w=p.width/2;return new Box(s.x-w,s.y,s.z-w,s.x+w,s.y+p.height,s.z+w);}
    public static State step(Terrain t,Parameters p,State s,Action a){
        if(!finite(s)||!Float.isFinite(a.yaw)||Math.abs(a.forward)>1||Math.abs(a.strafe)>1)return null;
        Box box=bounds(s,p);
        if(!t.known(box)||!t.safe(box))return null;
        Surface floor=s.onGround?t.groundSurface(box):new Surface(.6,true);
        // Friction is sampled below the feet; on a thin support that sample can
        // be water. Validate the dry body and actual support, not that sample.
        if(s.onGround&&!floor.supported&&t.collisions(box.move(0,-.00001,0)).isEmpty())return null;
        double vx=s.vx,vy=s.vy,vz=s.vz;
        if(t.modernMovement()){if(vx*vx+vz*vz<9e-6)vx=vz=0;}else{if(Math.abs(vx)<.003)vx=0;if(Math.abs(vz)<.003)vz=0;}
        if(Math.abs(vy)<.003)vy=0;
        int cooldown=Math.max(0,s.jumpCooldown-1);
        boolean sprint=a.sprint&&a.forward>0&&!s.sprintBlocked;
        float rad=a.yaw*((float)Math.PI/180f);
        if(a.jump&&s.onGround&&cooldown==0){vy=Math.max(p.jumpVelocity,vy);if(sprint){vx-=sin(rad)*.2;vz+=cos(rad)*.2;}cooldown=10;}
        if(!a.jump)cooldown=0;
        float friction=s.onGround?(float)floor.friction:1f;
        float drag=friction*.91f;
        float speed=(float)(p.walkingSpeed*(sprint?1.3:1));
        float accel=s.onGround?speed*(.21600002f/(friction*friction*friction)):(float)(p.airAcceleration*(sprint?1.3:1));
        float f=a.forward,side=a.strafe;
        if(t.modernMovement()&&f*f+side*side>1){float n=(float)Math.sqrt(f*f+side*side);f/=n;side/=n;}
        f*=.98f;side*=.98f;
        if(t.modernMovement()){
            // LocalPlayer.modifyInputSpeedForSquareMovement (1.21.10+).
            float len=(float)Math.sqrt(f*f+side*side);
            if(len>0){float uf=f/len,us=side/len;float ratio=Math.min(Math.abs(uf),Math.abs(us))/Math.max(Math.abs(uf),Math.abs(us));float square=(float)Math.sqrt(1+ratio*ratio);float magnitude=Math.min(len*square,1);f=uf*magnitude;side=us*magnitude;}
        }
        double n=Math.sqrt(f*f+side*side);double scale=n>1?accel/n:accel;
        vx+=(side*cos(rad)-f*sin(rad))*scale;vz+=(f*cos(rad)+side*sin(rad))*scale;
        boolean climbing=t.climbable(s.x,s.y,s.z);
        if(climbing){vx=Math.max(-.15f,Math.min(.15f,vx));vz=Math.max(-.15f,Math.min(.15f,vz));vy=Math.max(vy,-.15f);}
        Box sweep=box.expand(vx,vy,vz).expand(0,p.stepHeight,0).inflate(1e-7);
        if(!t.known(sweep))return null;
        List<Box> colliders=t.collisions(sweep);
        double[] moved=collide(box,vx,vy,vz,colliders);
        double stepLift=0;
        boolean down=vy<0&&vy!=moved[1];
        if(p.stepHeight>0&&(s.onGround||down)&&(moved[0]!=vx||moved[2]!=vz)){
            if(t.legacyStep()){
                double[] candidate=collide(box,vx,p.stepHeight,vz,colliders);
                double up=clip(box.expand(vx,0,vz),p.stepHeight,1,colliders);
                if(up<p.stepHeight){double[] flat=collide(box.move(0,up,0),vx,0,vz,colliders);flat[1]=up;if(horizontal(flat)>horizontal(candidate))candidate=flat;}
                if(horizontal(candidate)>horizontal(moved)){stepLift=candidate[1];double descend=clip(box.move(candidate[0],candidate[1],candidate[2]),vy-candidate[1],1,colliders);candidate[1]+=descend;moved=candidate;}
            }else{
                Box base=down?box.move(0,moved[1],0):box;
                TreeSet<Float> heights=new TreeSet<>();
                for(Box b:colliders)for(double y:new double[]{b.minY,b.maxY}){float h=(float)(y-base.minY);if(h>=0&&h<=p.stepHeight&&h!=(float)moved[1])heights.add(h);}
                for(float h:heights){double[] candidate=collide(base,vx,h,vz,colliders);if(horizontal(candidate)>horizontal(moved)){candidate[1]+=base.minY-box.minY;stepLift=Math.max(0,candidate[1]);moved=candidate;break;}}
            }
        }
        // Collision clipping may stop a fall above water (e.g. a lily pad).
        // Check the volume actually traversed, including the step-up excursion.
        Box traversed=box.expand(moved[0],moved[1],moved[2]).expand(0,Math.max(0,stepLift-Math.max(0,moved[1])),0);
        if(!t.safe(traversed))return null;
        boolean ground=vy<0&&vy!=moved[1];
        if(vy==0&&s.onGround)ground=!t.collisions(box.move(moved[0],moved[1]-.00001,moved[2])).isEmpty();
        boolean horizontalCollision=Math.abs(vx-moved[0])>1e-5||Math.abs(vz-moved[2])>1e-5;
        Box landedBody=box.move(moved[0],moved[1],moved[2]);
        boolean slime=ground&&t.slimeContact(landedBody);
        double incomingY=vy;
        if(vx!=moved[0])vx=0;if(vz!=moved[2])vz=0;
        if(vy!=moved[1]){
            if(slime&&incomingY<0&&(!t.fractionalBounce()||-incomingY>=p.gravity)){
                // SlimeBlock.bounceUp (through 26.1.2) reflects the incoming
                // LivingEntity velocity. 26.2 restitutes at collision time.
                double fraction=t.fractionalBounce()?Math.max(0,Math.min(1,moved[1]/incomingY)):0;
                vy=(fraction*p.gravity-incomingY)*(1+fraction*((double).98f-1));
            }else if(!t.fractionalBounce()&&incomingY>0&&t.slimeContact(landedBody)){
                // Legacy SlimeBlock.bounceUp leaves upward velocity unchanged,
                // even when a very low ceiling clipped motion above its block.
                vy=incomingY;
            }else vy=0;
        }
        if((horizontalCollision||a.jump)&&t.climbable(s.x+moved[0],s.y+moved[1],s.z+moved[2]))vy=.2;
        double fall=(s.onGround||climbing?0:s.fallDistance)+Math.max(0,-moved[1]);
        if(!p.allowDamagingFalls&&fall>3&&!slime&&(ground||!slimeBelow(t,landedBody)))return null;
        // Legacy Entity.move applies stepOn before travel gravity/drag. Newer
        // aiStep applies block effects after travel; the vertical sample differs.
        if(slime&&t.legacyStep()&&Math.abs(vy)<.1){double slow=.4+Math.abs(vy)*.2;vx*=slow;vz*=slow;}
        double outX=vx*drag,outY=(vy-p.gravity)*.98f,outZ=vz*drag;
        if(slime&&!t.legacyStep()&&Math.abs(outY)<.1){double slow=.4+Math.abs(outY)*.2;outX*=slow;outZ*=slow;}
        double inputX=side*cos(rad)-f*sin(rad),inputZ=f*cos(rad)+side*sin(rad);
        double inputLength=inputX*inputX+inputZ*inputZ,movedLength=moved[0]*moved[0]+moved[2]*moved[2];
        boolean minor=horizontalCollision&&inputLength>=1e-5f&&movedLength>=1e-5f
            &&Math.acos((inputX*moved[0]+inputZ*moved[2])/Math.sqrt(inputLength*movedLength))<.13962634f;
        State out=new State(s.x+moved[0],s.y+moved[1],s.z+moved[2],outX,outY,outZ,a.yaw,ground,sprint,cooldown,s.tick+1,ground?0:fall,horizontalCollision&&!minor);
        return finite(out)&&t.safe(bounds(out,p))?out:null;
    }
    /** A high drop is admitted only above a known, unobstructed slime support.
     * Every later tick checks this again; normal damaging impacts stay invalid. */
    private static boolean slimeBelow(Terrain t,Box body){
        for(int depth=0;depth<32;depth++){
            Box slice=new Box(body.minX,body.minY-depth-1,body.minZ,body.maxX,body.minY-depth,body.maxZ);
            if(!t.known(slice)||!t.safe(slice))return false;
            double top=Double.NEGATIVE_INFINITY;
            for(Box b:t.collisions(slice))if(b.maxY<=body.minY+1e-7)top=Math.max(top,b.maxY);
            if(top>Double.NEGATIVE_INFINITY)return t.slimeContact(body.move(0,top-body.minY,0));
        }
        return false;
    }
    private static boolean finite(State s){return Double.isFinite(s.x)&&Double.isFinite(s.y)&&Double.isFinite(s.z)&&Double.isFinite(s.vx)&&Double.isFinite(s.vy)&&Double.isFinite(s.vz)&&Float.isFinite(s.yaw);}
    private static double horizontal(double[] d){return d[0]*d[0]+d[2]*d[2];}
    private static double[] collide(Box box,double x,double y,double z,List<Box> blocks){
        y=clip(box,y,1,blocks);box=box.move(0,y,0);
        boolean zFirst=Math.abs(x)<Math.abs(z);
        if(zFirst){z=clip(box,z,2,blocks);box=box.move(0,0,z);}
        x=clip(box,x,0,blocks);box=box.move(x,0,0);
        if(!zFirst)z=clip(box,z,2,blocks);
        return new double[]{x,y,z};
    }
    private static double min(Box b,int axis){return axis==0?b.minX:axis==1?b.minY:b.minZ;}
    private static double max(Box b,int axis){return axis==0?b.maxX:axis==1?b.maxY:b.maxZ;}
    private static double clip(Box box,double delta,int axis,List<Box> blocks){
        if(Math.abs(delta)<1e-7)return 0;
        int a=(axis+1)%3,b=(axis+2)%3;
        for(Box block:blocks){
            if(max(box,a)<=min(block,a)+1e-7||min(box,a)>=max(block,a)-1e-7||max(box,b)<=min(block,b)+1e-7||min(box,b)>=max(block,b)-1e-7)continue;
            if(delta>0&&max(box,axis)<=min(block,axis)+1e-7)delta=Math.min(delta,Math.max(0,min(block,axis)-max(box,axis)));
            else if(delta<0&&min(box,axis)>=max(block,axis)-1e-7)delta=Math.max(delta,Math.min(0,max(block,axis)-min(box,axis)));
        }
        return delta;
    }
}
