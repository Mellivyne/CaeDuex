package com.cheatprogram.dev;
import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Actual-state suffix replay must not repeat already executed controls or release mid-jump. */
public final class ParkourRecoveryCheck {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        var terrain=new ParkourPhysicsCheck.TerrainFixture().block(-2,-1,-3,4,1,5).block(-2,-1,4,4,1,8);
        var p=Parameters.defaults();var initial=ParkourPhysicsCheck.initial(0,0,0);Goal goal=new Goal(0,0,7,.4,.2);
        Result route=ParkourPlanner.search(terrain,p,initial,List.of(goal),new Options(100,300000,96,3_000_000_000L,true,true,true,45));
        require(route.reached(),"Gap fixture reachable");
        int cut=1;while(cut<route.actions().size()&&route.states().get(cut).onGround())cut++;
        require(cut<route.actions().size(),"Has airborne suffix");State old=route.states().get(cut);
        State actual=new State(old.x()+.01,old.y(),old.z(),old.vx(),old.vy(),old.vz(),old.yaw(),old.onGround(),old.sprinting(),old.jumpCooldown(),old.tick(),old.fallDistance());
        Result repair=ParkourRecovery.remaining(route,cut,terrain,p,actual);
        require(repair!=null&&repair.states().getFirst().equals(actual),"Safe airborne correction begins at actual state");
        require(repair.actions().equals(route.actions().subList(cut,route.actions().size())),"Executed prefix never repeated");
        require(repair.states().getLast().tick()==route.states().getLast().tick()&&goal.reached(repair.states().getLast()),"Suffix timing and goal retained");
        terrain.unsafe=true;require(ParkourRecovery.remaining(route,cut,terrain,p,actual)==null,"Unsafe suffix rejected");
        require(ParkourRecovery.remaining(route,route.actions().size(),terrain,p,actual)==null,"No exhausted suffix replay");
        int corrected=0,previouslyUnsafe=0;
        for(boolean modern:new boolean[]{false,true}){
            var scene=ParkourNeoChainCheck.scene(1,8,0,-6);scene.world().modern=modern;
            Result neo=ParkourPlanner.search(scene.world(),p,scene.start(),scene.goals(),ParkourNeoChainCheck.O);
            require(neo.reached(),"Tracking triple-neo fixture");
            for(int at=1;at<neo.actions().size()-6;at++){
                // Begin before the final constrained landing turn. A last-moment
                // miss is allowed to fail closed rather than exceed turn limits.
                State baseline=neo.states().get(at);if(baseline.onGround()||baseline.x()< -1.5||baseline.x()>1.0)continue;
                for(double shift:new double[]{-.003,.003}){
                    State drift=new State(baseline.x(),baseline.y(),baseline.z()+shift,baseline.vx(),baseline.vy(),baseline.vz(),baseline.yaw(),baseline.onGround(),baseline.sprinting(),baseline.jumpCooldown(),baseline.tick(),baseline.fallDistance(),baseline.sprintBlocked());
                    Result tracked=ParkourRecovery.track(neo,at,scene.world(),p,drift,25);
                    require(tracked!=null&&scene.goals().getLast().reached(tracked.states().getLast()),"Small airborne corner drift receives a complete safe correction");
                    require(tracked.actions().size()==neo.actions().size()-at,"Tracking inserts no waiting ticks");
                    require(tracked.states().getFirst().equals(drift),"Tracking begins from observed position/velocity");
                    State replay=drift;
                    for(Action a:tracked.actions()){
                        double turn=a.yaw()-replay.yaw();turn-=Math.floor((turn+180)/360)*360;
                        require(Math.abs(turn)<=25.0001,"Tracking obeys the configured turn limit");
                        replay=ParkourPhysics.step(scene.world(),p,replay,a);require(replay!=null,"Safe tracking replay");
                    }
                    require(replay.equals(tracked.states().getLast()),"Tracked state replay agrees exactly");
                    if(ParkourRecovery.remaining(neo,at,scene.world(),p,drift)==null)previouslyUnsafe++;
                    corrected++;
                }
            }
        }
        require(corrected>=24&&previouslyUnsafe>0,"Tracking rescues corner suffixes which unchanged input replay rejects");
        System.out.println("TRACKING_PASS corrected="+corrected+" unchangedReplayUnsafe="+previouslyUnsafe+" complete collision replay, native yaw bounds, no inserted waits");
        System.out.println("RECOVERY_PASS actual airborne state, unexecuted suffix only, timing/goal preserved, unsafe result rejected");
    }
}
