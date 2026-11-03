package com.cheatprogram.parkour;

import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Validated edge preparation and airborne side-wrap candidates, using native inputs only. */
public final class ParkourManeuvers {
    private ParkourManeuvers(){}
    public record Attempt(Result route,int expanded){}
    private static final class Budget {
        final Terrain terrain;final Parameters physics;final long deadline;final int limit;int used;Chain chain;
        Budget(Terrain t,Parameters p,long deadline,int limit){terrain=t;physics=p;this.deadline=deadline;this.limit=limit;}
        boolean alive(){return used<limit&&System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted();}
        State step(State s,Action a){if(!alive())return null;used++;return ParkourPhysics.step(terrain,physics,s,a);}
    }
    private record Chain(List<Box> walls,Goal target){}
    private record AirNode(State state,AirNode parent,Action action,double score){}
    private record Axes(boolean x,double sign){
        double u(State s){return sign*(x?s.x():s.z());}double v(State s){return x?s.z():s.x();}
        double vu(State s){return sign*(x?s.vx():s.vz());}double vv(State s){return x?s.vz():s.vx();}
        double u(Goal g){return sign*(x?g.x():g.z());}double v(Goal g){return x?g.z():g.x();}
        double lowU(Box b){return sign>0?(x?b.minX():b.minZ()):-(x?b.maxX():b.maxZ());}
        double highU(Box b){return sign>0?(x?b.maxX():b.maxZ()):-(x?b.minX():b.minZ());}
        double lowV(Box b){return x?b.minZ():b.minX();}double highV(Box b){return x?b.maxZ():b.maxX();}
        float heading(double u,double v){double wx=x?u*sign:v,wz=x?v:u*sign;return (float)Math.toDegrees(Math.atan2(-wx,wz));}
        Box box(double lowU,double highU,double lowV,double highV,double lowY,double highY){
            double lo=sign>0?lowU:-highU,hi=sign>0?highU:-lowU;
            return x?new Box(lo,lowY,lowV,hi,highY,highV):new Box(lowV,lowY,lo,highV,highY,hi);
        }
    }
    public static Attempt search(Terrain terrain,Parameters p,State start,List<Goal> goals,Options options,long deadline,int limit){
        Budget budget=new Budget(terrain,p,deadline,limit);
        if(goals.isEmpty()||!start.onGround()||!options.jump()||!options.airControl()||!options.sprint())return new Attempt(null,0);
        var actions=new ArrayList<Action>();var states=new ArrayList<State>();states.add(start);
        State current=start;int point=ParkourCourse.advance(goals,0,start);
        if(current.sprintBlocked()&&Math.hypot(current.vx(),current.vz())<.06){
            // The last manual push into a wall leaves horizontalCollision set
            // even after the player has stopped. Observe one legal neutral tick
            // before preparing sprint momentum; never invent a cleared flag.
            Action neutral=new Action(current.yaw(),0,0,false,false);
            State settled=budget.step(current,neutral);
            if(settled!=null&&settled.onGround()){
                actions.add(neutral);states.add(settled);current=settled;
                point=ParkourCourse.advance(goals,point,start,current);
            }
        }
        while((point<goals.size()||!goals.getLast().reached(current))&&actions.size()<options.horizon()&&budget.alive()){
            Options remaining=new Options(options.horizon()-actions.size(),options.maxNodes(),options.beamWidth(),options.budgetNanos(),options.sprint(),options.jump(),options.airControl(),options.maxTurnDegrees());
            // The compound must be planned beyond a contact waypoint. Stopping
            // on its centre destroys the momentum needed by the next wall.
            Goal aim=goals.get(Math.min(point,goals.size()-1));
            for(int i=point;i<goals.size()-1&&goals.get(i).region()!=null;i++){
                Goal next=goals.get(i+1);
                // Contact lookahead preserves a same-level compound. A later
                // staircase/high platform is a separate transition, not the
                // landing elevation of all preceding side wraps.
                if(Math.abs(next.y()-current.y())>.001)break;
                aim=next;
            }
            Result leg=segment(budget,current,aim,remaining);
            if(leg==null||leg.actions().isEmpty())break;
            for(int i=0;i<leg.actions().size();i++){
                actions.add(leg.actions().get(i));State previous=current;current=leg.states().get(i+1);states.add(current);
                point=ParkourCourse.advance(goals,point,previous,current);
            }
        }
        Result result=actions.isEmpty()?null:new Result(states,actions,point==goals.size()&&goals.getLast().reached(current),0,0,"VALIDATED_SIDE_WRAPS");
        return new Attempt(result,budget.used);
    }
    /** Internal obstacle transitions are inferred from geometry, not extra user waypoints. */
    private static Result segment(Budget budget,State start,Goal target,Options options){
        budget.chain=null;
        Terrain terrain=budget.terrain;Parameters p=budget.physics;
        double dx=target.x()-start.x(),dz=target.z()-start.z();
        Axes axes=new Axes(Math.abs(dx)>=Math.abs(dz),Math.abs(dx)>=Math.abs(dz)?Math.signum(dx):Math.signum(dz));
        // The user checkpoint can be arbitrarily distant. Never turn its
        // coordinates into an unbounded collision-array scan.
        double targetV=clamp(axes.v(target),axes.v(start)-6,axes.v(start)+6);
        Box query=axes.box(axes.u(start)-6,Math.min(axes.u(target),axes.u(start)+36)+1.5,
            Math.min(axes.v(start),targetV)-1.5,Math.max(axes.v(start),targetV)+1.5,start.y()+.01,start.y()+p.height()+1.5);
        List<Box> components=new ArrayList<>(terrain.collisions(query));
        if(components.size()>128)return null;
        // Native voxel shapes arrive block by block. Merge connected obstacle
        // components above the floor; never include the supporting floor itself.
        boolean changed=true;while(changed&&budget.alive()){
            changed=false;
            outer:for(int i=0;i<components.size();i++)for(int j=i+1;j<components.size();j++)if(components.get(i).inflate(.000001).intersects(components.get(j))){
                Box a=components.get(i),b=components.remove(j);components.set(i,new Box(Math.min(a.minX(),b.minX()),Math.min(a.minY(),b.minY()),Math.min(a.minZ(),b.minZ()),Math.max(a.maxX(),b.maxX()),Math.max(a.maxY(),b.maxY()),Math.max(a.maxZ(),b.maxZ())));changed=true;break outer;
            }
        }
        components.sort(Comparator.comparingDouble(axes::lowU));
        if(Math.abs(target.y()-start.y())>.001){
            // A sole distant high endpoint must not disable the same-level
            // neos in front of it. Infer a real supported exit from their voxel
            // geometry, then let the rolling planner solve the raised section.
            Goal exit=levelExit(budget,start,target,axes,components);
            if(exit==null)return null;
            target=exit;
        }
        Goal segmentTarget=target;
        for(Box wall:components){
            double minU=axes.lowU(wall),maxU=axes.highU(wall),minV=axes.lowV(wall),maxV=axes.highV(wall);
            if(wall.minY()>start.y()+.1||wall.maxY()<start.y()+p.height()||maxU-minU>3.1||maxV-minV>1.5
                    ||minU<axes.u(start)-.1||maxU>axes.u(target)-.2)continue;
            double nextWall=Double.POSITIVE_INFINITY;
            for(Box b:components)if(axes.lowU(b)>maxU+.01&&b.minY()<=start.y()+.1&&b.maxY()>start.y()+p.height())nextWall=Math.min(nextWall,axes.lowU(b));
            boolean wide=maxU-minU>2.5;
            if(wide)budget.chain=compactChain(budget,components,wall,axes,target,options.horizon());
            double endU=Math.min(axes.u(target),Math.min(wide?maxU+p.width()/2+.12:Double.POSITIVE_INFINITY,Double.isFinite(nextWall)?(maxU+nextWall)/2:maxU+2)),endV=clamp(axes.v(target),minV,maxV);
            double landingRadius=Double.isFinite(nextWall)?Math.max(.08,Math.min(.3,(nextWall-maxU-p.width())*.45)):.3;
            State targetState=new State(target.x(),target.y(),target.z(),0,0,0,start.yaw(),true,false,0,0);
            boolean finalLeg=terrain.known(ParkourPhysics.bounds(targetState,p))
                &&components.stream().noneMatch(b->axes.lowU(b)>maxU+.01&&axes.highU(b)<axes.u(segmentTarget));
            double runupU=minU-4.05;
            for(Box previous:components)if(axes.highU(previous)<minU-.01)
                runupU=Math.max(runupU,axes.highU(previous)+p.width()/2+.10);
            for(int side:new int[]{1,-1}){
                double landingV=wide?(side>0?maxV:minV)+side*(p.width()/2-.06):endV;
                Goal landing=axes.x()?new Goal(endU*axes.sign(),start.y(),landingV,landingRadius,.2):new Goal(landingV,start.y(),endU*axes.sign(),landingRadius,.2);
                List<Goal> local=List.of(finalLeg?target:landing);
                Result candidate=attempt(budget,start,local,options,axes,minU,maxU,side>0?maxV:minV,side,runupU);
                if(candidate!=null)return candidate;
                if(!budget.alive())break;
            }
            return null;
        }
        // A short clear exit can finish the actual checkpoint after the last wrap.
        if(Math.hypot(dx,dz)<=3)return finishJump(budget,start,List.of(target),options,axes,Double.NEGATIVE_INFINITY,0,1,new ArrayList<>(),new ArrayList<>(List.of(start)));
        return null;
    }
    private static Goal levelExit(Budget budget,State start,Goal target,Axes axes,List<Box> components){
        Box last=null;
        for(Box wall:components){
            double length=axes.highU(wall)-axes.lowU(wall);
            if(wall.minY()>start.y()+.1||wall.maxY()<start.y()+budget.physics.height()
                ||length<.9||length>3.1||axes.highV(wall)-axes.lowV(wall)>1.5
                ||axes.lowU(wall)<axes.u(start)-.1||axes.highU(wall)>axes.u(target)-.2)continue;
            if(last!=null&&(axes.lowU(wall)-axes.highU(last)>1.1
                ||Math.abs(axes.lowV(wall)-axes.lowV(last))>.001||Math.abs(axes.highV(wall)-axes.highV(last))>.001))break;
            last=wall;
        }
        if(last==null)return null;
        double end=axes.highU(last),v=(axes.lowV(last)+axes.highV(last))*.5;
        Box query=axes.box(end+.01,Math.min(end+3,axes.u(target)),v-.1,v+.1,start.y()-.01,start.y()+.001);
        Goal best=null;double furthest=end;
        for(Box floor:budget.terrain.collisions(query)){
            if(Math.abs(floor.maxY()-start.y())>.00001)continue;
            double u=Math.min(axes.highU(floor)-.2,end+2);
            if(u<end+budget.physics.width()/2+.1||u<=furthest)continue;
            Goal candidate=axes.x()?new Goal(u*axes.sign(),start.y(),v,.15,.2):new Goal(v,start.y(),u*axes.sign(),.15,.2);
            State endState=new State(candidate.x(),candidate.y(),candidate.z(),0,0,0,start.yaw(),true,false,0,0);
            Box body=ParkourPhysics.bounds(endState,budget.physics);
            if(!budget.terrain.known(body)||!budget.terrain.safe(body)||!budget.terrain.collisions(body.inflate(-1e-6)).isEmpty())continue;
            best=candidate;furthest=u;
        }
        return best;
    }
    private static Result attempt(Budget budget,State start,List<Goal> goals,Options options,Axes axes,double minU,double maxU,double edge,int side,double runupU){
        boolean runup=maxU-minU>2.5&&budget.terrain.groundSurface(ParkourPhysics.bounds(start,budget.physics)).friction()<.9;
        if(runup&&start.jumpCooldown()==0&&axes.vu(start)>.1&&axes.u(start)>=minU-.65
            &&side*(axes.v(start)-edge)>budget.physics.width()/2)
            return attemptAt(budget,start,goals,options,axes,minU,maxU,edge,side,runupU);
        double supported=runup?runupStart(budget,start,axes,runupU,minU,edge+side*(budget.physics.width()/2-.025)):runupU;
        if(!Double.isFinite(supported))return null;
        boolean shortened=runup&&supported>runupU+.05;
        // A short runway changes the tick phase of the preparation hop.
        // Try bounded launch alignments with shared work/time accounting.
        double[] phases=shortened?new double[]{-.375,-.125,0,.125,-.25,.25}:new double[]{0};
        for(double phase:phases){
            if(!budget.alive())break;
            Budget trial=new Budget(budget.terrain,budget.physics,budget.deadline,
                shortened?Math.min(12000,budget.limit-budget.used):budget.limit-budget.used);
            trial.chain=budget.chain;
            if(shortened&&trial.chain!=null&&trial.chain.walls().size()>2){
                // Validate the entry and next contact jointly. The rolling
                // worker continues the compound from that real momentum.
                Box last=trial.chain.walls().get(1),next=trial.chain.walls().get(2);
                double u=(axes.highU(last)+axes.lowU(next))*.5,v=(axes.lowV(last)+axes.highV(last))*.5;
                Goal exit=axes.x()?new Goal(u*axes.sign(),start.y(),v,.15,.2):new Goal(v,start.y(),u*axes.sign(),.15,.2);
                trial.chain=new Chain(List.copyOf(trial.chain.walls().subList(0,2)),exit);
            }
            Result result=attemptAt(trial,start,goals,options,axes,minU,maxU,edge,side,supported+phase);
            budget.used+=trial.used;
            if(result!=null)return result;
        }
        return null;
    }
    private static Result attemptAt(Budget budget,State start,List<Goal> goals,Options options,Axes axes,double minU,double maxU,double edge,int side,double runupU){
        var actions=new ArrayList<Action>();var states=new ArrayList<State>();states.add(start);State state=start;
        double clearance=budget.terrain.groundSurface(ParkourPhysics.bounds(start,budget.physics)).friction()>.9?.004:.008;
        double inside=edge+side*(budget.physics.width()/2-.025),outside=edge+side*(budget.physics.width()/2+clearance);
        boolean runup=maxU-minU>2.5&&budget.terrain.groundSurface(ParkourPhysics.bounds(start,budget.physics)).friction()<.9;
        if(runup&&start.jumpCooldown()==0&&axes.vu(start)>.1&&axes.u(start)>=minU-.65){
            Result immediate=delayedJump(budget,start,goals,options,axes,minU,maxU,edge,side,actions,states,clearance);
            if(immediate!=null)return immediate;
        }
        if(runup&&!start.sprintBlocked()&&axes.vu(start)>.1&&side*(axes.v(start)-edge)>.22&&axes.u(start)<minU-2){
            Result rolling=momentumJump(budget,start,goals,options,axes,minU,maxU,edge,side,actions,states);
            if(rolling!=null)return rolling;
        }
        boolean aligned=false;
        for(int tick=0;tick<options.horizon()&&budget.alive();tick++){
            if(!state.onGround())return null;
            double vError=inside-axes.v(state);
            if(Math.abs(vError)<.035&&Math.abs(axes.vv(state))<.025&&(!runup||Math.abs(axes.u(state)-runupU)<.06&&Math.abs(axes.vu(state))<.035))aligned=true;
            if(aligned&&(state.sprintBlocked()||Math.abs(angle(state.yaw(),axes.heading(1,0)))>8)){
                Action ready=new Action(bounded(state.yaw(),axes.heading(1,0),options.maxTurnDegrees()),0,0,false,false);
                State prepared=budget.step(state,ready);
                if(prepared!=null&&prepared.onGround()&&!budget.terrain.collisions(ParkourPhysics.bounds(prepared,budget.physics).move(0,-.00001,0)).isEmpty()){
                    actions.add(ready);states.add(prepared);state=prepared;continue;
                }
            }
            if(aligned&&side*(axes.v(state)-edge)>.24&&axes.u(state)>minU-1.8&&axes.u(state)<minU-.3){
                Result delayed=delayedJump(budget,state,goals,options,axes,minU,maxU,edge,side,actions,states,clearance);
                if(delayed!=null)return delayed;
                for(boolean sprintJump:new boolean[]{true,false})for(double bias:new double[]{-.002,0,.002,.006}){
                    double wanted=outside+side*bias;
                    Surface floor=budget.terrain.groundSurface(ParkourPhysics.bounds(state,budget.physics));
                    boolean effectiveSprint=sprintJump&&!state.sprintBlocked();
                    double accel=budget.physics.walkingSpeed()*(effectiveSprint?1.3:1)*.21600002/Math.pow(floor.friction(),3)*.98;
                    double lateral=(wanted-axes.v(state)-movingV(budget,state,axes))/((effectiveSprint?.2:0)+accel);
                    if(Math.abs(lateral)>.65)continue;
                    float yaw=bounded(state.yaw(),axes.heading(Math.sqrt(1-lateral*lateral),lateral),options.maxTurnDegrees());
                    Action jump=new Action(yaw,1,0,true,sprintJump);State airborne=budget.step(state,jump);
                    if(airborne==null||airborne.onGround()||axes.vu(airborne)<.08||side*(axes.v(airborne)-outside)<-.0038)continue;
                    var trialActions=new ArrayList<>(actions);trialActions.add(jump);var trialStates=new ArrayList<>(states);trialStates.add(airborne);
                    Result trial=finishJump(budget,airborne,goals,options,axes,maxU,outside,side,trialActions,trialStates);
                    if(trial!=null)return trial;
                }
            }
            if(aligned&&axes.u(state)<minU-2.0){
                Result momentum=momentumJump(budget,state,goals,options,axes,minU,maxU,edge,side,actions,states);
                if(momentum!=null)return momentum;
            }
            double targetU=aligned?minU-1.0:runup?runupU:minU-2.0;
            double targetV=aligned?edge+side*(budget.physics.width()/2-.006):inside;
            double desiredU=aligned?.26:clamp((targetU-axes.u(state))*.35,-.10,runup?.10:.04);
            double desiredV=clamp((targetV-axes.v(state))*.35,-.09,.09);
            Action chosen=steer(budget,state,axes,desiredU,desiredV,targetV,options.maxTurnDegrees(),true);
            if(chosen==null)return null;state=budget.step(state,chosen);if(state==null)return null;
            actions.add(chosen);states.add(state);
            if(aligned&&axes.u(state)>minU-budget.physics.width()/2+.02)return null;
        }
        return null;
    }
    /** Entry platforms can be shorter than the nominal 4.05-block runway.
     * Select actual support for the preparation hop rather than walking into
     * the gap behind the previous obstacle. The resulting hop still has to pass
     * the complete native tick simulation; this does not widen jump reach. */
    private static double runupStart(Budget budget,State start,Axes axes,double nominal,double wall,double v){
        for(double u=nominal;u<=wall-1.6;u+=.125){
            double x=axes.x()?u*axes.sign():v,z=axes.x()?v:u*axes.sign();
            State sample=new State(x,start.y(),z,0,-.0784000015258789,0,start.yaw(),true,false,0,0);
            Box body=ParkourPhysics.bounds(sample,budget.physics);
            if(budget.terrain.known(body)&&budget.terrain.safe(body)
                &&budget.terrain.collisions(body.inflate(-1e-6)).isEmpty()){
                for(Box support:budget.terrain.collisions(body.move(0,-.00001,0)))
                    if(u>=axes.lowU(support)+.02&&u<=axes.highU(support)-.02)return u;
            }
        }
        return Double.NaN;
    }
    private static Result momentumJump(Budget budget,State start,List<Goal> goals,Options options,Axes axes,double minU,double maxU,double edge,int side,List<Action> prefix,List<State> prefixStates){
        double clearance=budget.terrain.groundSurface(ParkourPhysics.bounds(start,budget.physics)).friction()>.9?.004:.008;
        double inside=edge+side*(budget.physics.width()/2-.01),outside=edge+side*(budget.physics.width()/2+.002);
        for(boolean sprintJump:new boolean[]{true,false})for(int waiting:new int[]{-1,0,1,-2,2,3,4})for(int coast=0;coast<4;coast++){
            if(!budget.alive())break;
            State state=start;var actions=new ArrayList<>(prefix);var states=new ArrayList<>(prefixStates);
            boolean launched=false;
            for(int tick=0;tick<24&&budget.alive()&&actions.size()<options.horizon();tick++){
                boolean landing=!state.onGround()&&state.y()+state.vy()<=start.y()+.00001&&state.vy()<0;
                double targetV=landing?outside:inside;
                double desiredV=clamp((targetV-axes.v(state))*(landing?1:.5),-.025,.025);
                double acceleration=state.onGround()?budget.physics.walkingSpeed()*1.3*.98:budget.physics.airAcceleration()*1.3*.98;
                double cosine=clamp((desiredV-movingV(budget,state,axes))/acceleration,landing?-.95:-.25,landing?.95:.25);
                float yaw=bounded(state.yaw(),axes.heading(Math.sqrt(1-cosine*cosine),cosine),options.maxTurnDegrees());
                boolean jump=!launched&&tick==Math.abs(waiting);
                boolean drifting=launched&&tick<=Math.abs(waiting)+coast;
                Action action=new Action(drifting?state.yaw():yaw,drifting?0:1,0,jump,!drifting&&(!jump||sprintJump)&&(launched||jump||waiting>=0));State next=budget.step(state,action);
                if(next==null)break;
                actions.add(action);states.add(next);state=next;
                launched|=jump;
                if(launched&&state.onGround()){
                    Result result=delayedJump(budget,state,goals,options,axes,minU,maxU,edge,side,actions,states,clearance);
                    if(result!=null)return result;
                    break;
                }
                if(axes.u(state)>minU-budget.physics.width()/2+.001)break;
            }
        }
        return null;
    }
    /** Retain the native one-tick ground contact after moving off a ledge.
     * It is usable only inside a completely replayed jump, never as a safe endpoint. */
    private static Result delayedJump(Budget budget,State start,List<Goal> goals,Options options,Axes axes,double minU,double maxU,double edge,int side,List<Action> prefix,List<State> prefixStates,double clearance){
        double contact=edge+side*budget.physics.width()/2,outside=contact+side*clearance;
        for(int edgeTick=0;edgeTick<=1&&budget.alive();edgeTick++)for(double margin:new double[]{.0002,.002,.004,.008}){
            if(edgeTick==0&&margin!=.0002)continue;
            State state=start;var actions=new ArrayList<>(prefix);var states=new ArrayList<>(prefixStates);
            if(edgeTick==1){
                double acceleration=budget.physics.walkingSpeed()*1.3*.21600002/Math.pow(budget.terrain.groundSurface(ParkourPhysics.bounds(state,budget.physics)).friction(),3)*.98;
                double cosine=(contact+side*margin-axes.v(state)-movingV(budget,state,axes))/acceleration;
                if(Math.abs(cosine)>.35)continue;
                Action step=new Action(bounded(state.yaw(),axes.heading(Math.sqrt(1-cosine*cosine),cosine),options.maxTurnDegrees()),1,0,false,true);
                state=budget.step(state,step);if(state==null||!state.onGround())continue;
                actions.add(step);states.add(state);
            }
            if(axes.vu(state)<.1||axes.u(state)<minU-.65||axes.u(state)>minU-budget.physics.width()/2+.001||side*(axes.v(state)-contact)<.00005)continue;
            double cosine=clamp((outside-axes.v(state)-movingV(budget,state,axes))/.3274,-.1,.1);
            Action jump=new Action(bounded(state.yaw(),axes.heading(Math.sqrt(1-cosine*cosine),cosine),options.maxTurnDegrees()),1,0,true,true);
            State next=budget.step(state,jump);if(next==null||next.onGround()||axes.vu(next)<.08)continue;
            actions.add(jump);states.add(next);
            Result result=finishJump(budget,next,goals,options,axes,maxU,outside,side,actions,states);
            if(result!=null)return result;
        }
        return null;
    }
    private static Result finishJump(Budget budget,State state,List<Goal> goals,Options options,Axes axes,double maxU,double outside,int side,List<Action> actions,List<State> states){
        if(budget.chain!=null){
            Chain compound=budget.chain;budget.chain=null;
            try{return finishChain(budget,state,compound,options,axes,side,actions,states);}
            finally{budget.chain=compound;}
        }
        int point=ParkourCourse.advance(goals,0,state);
        while(actions.size()<options.horizon()&&budget.alive()){
            if(point==goals.size()&&goals.getLast().reached(state)&&state.onGround()&&ParkourPlanner.safeRelease(budget.terrain,budget.physics,state))
                return new Result(states,actions,true,0,0,"VALIDATED_SIDE_WRAP");
            Goal target=goals.get(Math.min(point,goals.size()-1));Action action;
            if(!state.onGround()){
                boolean past=axes.u(state)+Math.max(0,axes.vu(state))+.025>=maxU+budget.physics.width()/2;
                double targetV=past?axes.v(target):outside;
                double lateral=movingV(budget,state,axes);
                double desired=clamp((targetV-axes.v(state))*.5,-.025,.025);
                double acceleration=clamp(desired-lateral,-.026,.026);
                double cosine=acceleration/.026;
                float yaw=bounded(state.yaw(),axes.heading(Math.sqrt(Math.max(0,1-cosine*cosine)),cosine),options.maxTurnDegrees());
                action=new Action(yaw,1,0,false,true);
            }else{
                double desiredU=clamp((axes.u(target)-axes.u(state))*.18,-.16,.22),desiredV=clamp((axes.v(target)-axes.v(state))*.25,-.12,.12);
                action=steer(budget,state,axes,desiredU,desiredV,axes.v(target),options.maxTurnDegrees(),false);
                if(action==null)return null;
            }
            State next=budget.step(state,action);if(next==null)return null;
            if(!state.onGround()&&next.onGround()&&axes.u(next)<maxU+budget.physics.width()/2-.01)return null;
            actions.add(action);states.add(next);point=ParkourCourse.advance(goals,point,state,next);state=next;
        }
        return null;
    }
    /** A one-block gap between long neos is a contact transition, not a place
     * to stop and start another independent run-up. Plan the entire compound. */
    private static Chain compactChain(Budget budget,List<Box> components,Box first,Axes axes,Goal target,int horizon){
        var walls=new ArrayList<Box>();walls.add(first);Box previous=first;
        for(Box next:components){
            if(axes.lowU(next)<=axes.highU(previous)+.01)continue;
            if(axes.highU(next)>axes.u(target)-.2)break;
            double gap=axes.lowU(next)-axes.highU(previous);
            if(gap<.95||gap>1.05||axes.highU(next)-axes.lowU(next)<2.9
                    ||axes.highU(next)-axes.lowU(next)>3.1
                    ||Math.abs(axes.lowV(next)-axes.lowV(first))>.001
                    ||Math.abs(axes.highV(next)-axes.highV(first))>.001
                    ||Math.abs(next.minY()-first.minY())>.001
                    ||next.maxY()<first.minY()+1.8)break;
            walls.add(next);previous=next;
        }
        // Leave room for approach and a safe release tail. Longer compounds
        // return a verified rolling prefix rather than discarding every jump
        // merely because the distant final point exceeds this horizon.
        int maximum=Math.max(2,(horizon-35)/12);
        State destination=new State(target.x(),target.y(),target.z(),0,0,0,0,true,false,0,0);
        if(walls.size()>maximum||!budget.terrain.known(ParkourPhysics.bounds(destination,budget.physics))){
            boolean cut=false;
            for(int count=Math.min(maximum,walls.size()-1);count>=2;count--){
                Box last=walls.get(count-1),next=walls.get(count);
                double u=(axes.highU(last)+axes.lowU(next))*.5,v=(axes.lowV(last)+axes.highV(last))*.5;
                Goal exit=axes.x()?new Goal(u*axes.sign(),target.y(),v,.15,.2):new Goal(v,target.y(),u*axes.sign(),.15,.2);
                State end=new State(exit.x(),exit.y(),exit.z(),0,0,0,0,true,false,0,0);
                if(!budget.terrain.known(ParkourPhysics.bounds(end,budget.physics).inflate(.02)))continue;
                target=exit;walls=new ArrayList<>(walls.subList(0,count));cut=true;break;
            }
            if(!cut)return null;
        }
        return walls.size()>1?new Chain(List.copyOf(walls),target):null;
    }
    private static Result finishChain(Budget budget,State state,Chain chain,Options options,Axes axes,int side,List<Action> prefix,List<State> prefixStates){
        // Preparation is part of this same tick horizon. Starting against a
        // wall can consume most of the nominal approach allowance while backing
        // up. Do not discard every launch by asking it to cross too many walls.
        // The overlap worker continues from a predicted contact before this
        // supported, fully simulated prefix ends.
        int count=Math.max(1,(options.horizon()-prefix.size()-8)/12);
        if(count<chain.walls().size()){
            Box last=chain.walls().get(count-1),next=chain.walls().get(count);
            double u=(axes.highU(last)+axes.lowU(next))*.5,v=(axes.lowV(last)+axes.highV(last))*.5;
            Goal exit=axes.x()?new Goal(u*axes.sign(),chain.target().y(),v,.15,.2)
                :new Goal(v,chain.target().y(),u*axes.sign(),.15,.2);
            chain=new Chain(List.copyOf(chain.walls().subList(0,count)),exit);
        }
        return finishChainAt(budget,state,chain,0,options,axes,side,prefix,prefixStates);
    }
    private static Result finishChainAt(Budget budget,State state,Chain chain,int index,Options options,Axes axes,int side,List<Action> prefix,List<State> prefixStates){
        if(!budget.alive())return null;
        if(index+1<chain.walls().size()){
            Box wall=chain.walls().get(index),next=chain.walls().get(index+1);
            double edge=side>0?axes.highV(wall):axes.lowV(wall);
            double preferred=axes.u(state)-axes.lowU(wall)>.15?.55:.66;
            for(double landingOffset:new double[]{preferred,.60,preferred==.55?.66:.55}){
            List<AirNode> bridges=contactJumps(budget,state,prefixStates.getFirst().y(),axes,wall,next,edge,side,options,options.horizon()-prefix.size(),landingOffset);
            for(AirNode bridge:bridges){
            var actions=new ArrayList<>(prefix);var states=new ArrayList<>(prefixStates);
            var reverse=new ArrayList<AirNode>();for(AirNode n=bridge;n.parent()!=null;n=n.parent())reverse.add(n);
            for(int n=reverse.size()-1;n>=0;n--){AirNode step=reverse.get(n);actions.add(step.action());states.add(step.state());}
            Result continuation=finishChainAt(budget,bridge.state(),chain,index+1,options,axes,side,actions,states);
            if(continuation!=null)return continuation;
            }
            }
            return null;
        }
        Box last=chain.walls().getLast();double edge=side>0?axes.highV(last):axes.lowV(last);
        return finishJump(budget,state,List.of(chain.target()),options,axes,axes.highU(last),edge+side*(budget.physics.width()/2+.008),side,new ArrayList<>(prefix),new ArrayList<>(prefixStates));
    }
    /** Search air controls jointly with the NEXT takeoff. A landing whose
     * momentum cannot launch the following neo never becomes a chosen exit. */
    private static List<AirNode> contactJumps(Budget budget,State initial,double floor,Axes axes,Box wall,Box nextWall,double edge,int side,Options options,int remaining,double landingOffset){
        if(remaining<3)return List.of();
        double maxU=axes.highU(wall),nextU=axes.lowU(nextWall),contact=edge+side*budget.physics.width()/2;
        double targetU=maxU+landingOffset,targetV=contact-side*.015;
        List<AirNode> frontier=List.of(new AirNode(initial,null,null,0));
        for(int tick=0;tick<Math.min(18,remaining-1)&&budget.alive();tick++){
            var candidates=new ArrayList<AirNode>();var launches=new ArrayList<AirNode>();
            for(AirNode node:frontier){
                State live=node.state();int left=airTicks(live,floor,budget.physics);
                double wantedV=left<=1?contact+side*.012:axes.u(live)+Math.max(0,axes.vu(live))+.025<maxU+budget.physics.width()/2?contact+side*.008:targetV;
                for(Action control:airControls(live,axes,targetU,wantedV,left,options.maxTurnDegrees())){
                    State n=budget.step(live,control);
                    if(n==null||n.sprintBlocked()||n.y()<floor-.00001||axes.u(n)>nextU-budget.physics.width()/2+.00001)continue;
                    AirNode candidate=new AirNode(n,node,control,airScore(n,axes,targetU,targetV,airTicks(n,floor,budget.physics)));
                    if(n.onGround()){
                        if(axes.u(n)<maxU+budget.physics.width()/2||side*(axes.v(n)-contact)<=.00005||side*(axes.v(n)-contact)>.06)continue;
                        double lateral=clamp((contact+side*.008-axes.v(n)-movingV(budget,n,axes))/.3274,-.4,.4);
                        float aimed=axes.heading(Math.sqrt(1-lateral*lateral),lateral);
                        for(float heading:new float[]{aimed,axes.heading(1,0),axes.heading(1,-.0875*side),axes.heading(1,-.1763*side)}){
                            Action jump=new Action(bounded(n.yaw(),heading,options.maxTurnDegrees()),1,0,true,true);
                            State launched=budget.step(n,jump);
                            if(launched!=null&&!launched.onGround()&&!launched.sprintBlocked()&&axes.vu(launched)>.30
                                    &&side*(axes.v(launched)-contact)>0&&side*(axes.v(launched)-contact)<.25)
                                launches.add(new AirNode(launched,candidate,jump,
                                    square(axes.vu(launched)-.32)*5+square(axes.vv(launched))*10
                                    +square(axes.v(launched)-contact-side*.008)*10+square(axes.u(launched)-nextU-.15)*.1));
                        }
                    }else candidates.add(candidate);
                }
            }
            if(!launches.isEmpty()){
                launches.sort(Comparator.comparingDouble(AirNode::score));var distinct=new ArrayList<AirNode>();var keys=new HashSet<String>();
                for(AirNode node:launches){State s=node.state();String key=Math.round(axes.u(s)*50)+","+Math.round(axes.v(s)*100)+","+Math.round(axes.vu(s)*100)+","+Math.round(axes.vv(s)*200);
                    if(keys.add(key))distinct.add(node);if(distinct.size()==6)break;
                }
                return distinct;
            }
            candidates.sort(Comparator.comparingDouble(AirNode::score));
            var retained=new ArrayList<AirNode>();var seen=new HashSet<String>();
            for(AirNode node:candidates){State s=node.state();String key=Math.round(axes.u(s)*200)+","+Math.round(axes.v(s)*200)+","+Math.round(axes.vu(s)*200)+","+Math.round(axes.vv(s)*200);
                if(seen.add(key))retained.add(node);if(retained.size()==32)break;
            }
            if(retained.isEmpty())return List.of();frontier=retained;
        }
        return List.of();
    }
    private static int airTicks(State state,double floor,Parameters p){
        double y=state.y(),v=state.vy();for(int i=1;i<20;i++){y+=v;v=(v-p.gravity())*.98f;if(y<=floor)return i;}return 20;
    }
    private static double airScore(State s,Axes axes,double u,double v,int ticks){
        double ax=(u-axes.u(s))/ticks-axes.vu(s),az=(v-axes.v(s))/ticks-axes.vv(s);
        return square(Math.max(0,Math.hypot(ax,az)-.026*.98*ticks*.5))*30+square(ax)+square(az)*3+square(axes.v(s)-v)*.002;
    }
    private static List<Action> airControls(State s,Axes axes,double u,double v,int ticks,float turn){
        double ax=(u-axes.u(s))/ticks-axes.vu(s),az=(v-axes.v(s))/ticks-axes.vv(s);
        float wanted=axes.heading(ax,az);
        double lateral=clamp(((v-axes.v(s))*.5-axes.vv(s))/(.026*.98),-1,1);
        var controls=new ArrayList<Action>();
        for(float heading:new float[]{axes.heading(Math.sqrt(1-lateral*lateral),lateral),wanted,axes.heading(1,0),axes.heading(1,-.4663),axes.heading(1,.4663),s.yaw()})
            controls.add(new Action(bounded(s.yaw(),heading,turn),1,0,false,true));
        controls.add(new Action(s.yaw(),0,0,false,false));
        controls.add(new Action(bounded(s.yaw(),wanted+180,turn),-1,0,false,false));
        return controls;
    }
    private static Action steer(Budget budget,State state,Axes axes,double wantedU,double wantedV,double targetV,float turn,boolean edgeGuard){
        double friction=budget.terrain.groundSurface(ParkourPhysics.bounds(state,budget.physics)).friction(),drag=friction*.91f;
        float desired=axes.heading(wantedU/drag-axes.vu(state),wantedV/drag-axes.vv(state));
        Action best=null;double score=Double.POSITIVE_INFINITY;
        for(int f=-1;f<=1;f++)for(int strafe=-1;strafe<=1;strafe++)for(boolean sprint:new boolean[]{false,true}){
            if(sprint&&f!=1)continue;
            float yaw=f==0&&strafe==0?state.yaw():bounded(state.yaw(),desired+(float)Math.toDegrees(Math.atan2(strafe,f)),turn);
            Action action=new Action(yaw,f,strafe,false,sprint);State next=budget.step(state,action);
            if(next==null||!next.onGround()||budget.terrain.collisions(ParkourPhysics.bounds(next,budget.physics).move(0,-.00001,0)).isEmpty())continue;
            double error=square(axes.vu(next)-wantedU)+square(axes.vv(next)-wantedV)*3+square(axes.v(next)-targetV)*.05;
            double view=yaw-axes.heading(1,0);view-=Math.floor((view+180)/360)*360;error+=square(view)*.0000002;
            if(wantedU>=.2&&(f!=1||strafe!=0||!sprint))error+=.0002;
            if(edgeGuard&&Math.abs(axes.v(next)-targetV)>.2)error+=.05;
            if(error<score){score=error;best=action;}
        }
        return best;
    }
    private static double square(double x){return x*x;}
    private static double movingV(Budget b,State s,Axes a){
        double v=a.vv(s);
        if(b.terrain.modernMovement())return s.vx()*s.vx()+s.vz()*s.vz()<9e-6?0:v;
        return Math.abs(v)<.003?0:v;
    }
    private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    private static double angle(double a,double b){return b-a-Math.floor((b-a+180)/360)*360;}
    private static float bounded(float from,float to,float max){float d=(float)(to-from-Math.floor((to-from+180)/360)*360);return from+Math.max(-max,Math.min(max,d));}
}
