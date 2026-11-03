package com.cheatprogram.dev;
import com.cheatprogram.parkour.*;
import com.cheatprogram.parkour.ParkourPhysics.*;
import com.cheatprogram.parkour.ParkourPlanner.*;
import java.util.*;

/** Overlapping prediction is computed before its handoff, without inserted idle inputs. */
public final class ParkourRollingCheck {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        int lead=ParkourPrediction.leadTicks(18,8,350);
        require(lead>=20&&ParkourPrediction.leadTicks(18,8,350,50)>=58,"Scan/search latency plus margin included");
        require(ParkourPrediction.anchor(3,80,lead)<80,"Prediction anchored before route end");
        require(ParkourPrediction.anchor(0,8,lead)==8,"Short route uses endpoint and startup buffer");
        var terrain=new ParkourPhysicsCheck.TerrainFixture().block(-5,-1,-5,10,1,70);
        var physics=Parameters.defaults();State live=ParkourPhysicsCheck.initial(0,0,0);
        List<Goal> goals=new ArrayList<>();for(int i=1;i<=20;i++)goals.add(new Goal(0,0,i*2,.4,.2));
        int checkpoint=0,total=0,handoffs=0;boolean overlapping=false;
        Options options=new Options(100,100000,96,350_000_000L,true,true,true,25);
        Result route=ParkourPlanner.search(terrain,physics,live,ParkourCourse.window(goals,0,ParkourPrediction.WINDOW),options);
        while(checkpoint<goals.size()&&handoffs<30){
            require(!route.actions().isEmpty(),"Rolling route makes progress: "+route.reason());
            int anchor=ParkourPrediction.anchor(route.states(),0,lead);
            int futureCheckpoint=ParkourContinuity.reachedThrough(goals,checkpoint,route.states().subList(0,anchor+1));
            State predicted=route.states().get(anchor);
            // Synchronously emulate a worker finishing before the chosen future tick.
            Result ahead=futureCheckpoint<goals.size()?ParkourPlanner.search(terrain,physics,predicted,ParkourCourse.window(goals,futureCheckpoint,ParkourPrediction.WINDOW),options):null;
            for(int tick=0;tick<anchor;tick++){
                live=ParkourPhysics.step(terrain,physics,live,route.actions().get(tick));require(live!=null,"Safe prefix");
                checkpoint=ParkourCourse.advance(goals,checkpoint,live);total++;
            }
            require(ParkourPrediction.exactStart(predicted,live),"Exact worker start adopts without full replay");
            if(checkpoint==goals.size())break;
            require(ahead!=null&&checkpoint==futureCheckpoint,"Future checkpoint known before arrival");
            require(ParkourPrediction.exactStart(ahead.states().getFirst(),live),"Momentum and cooldown retained");
            overlapping|=anchor<route.actions().size();route=ahead;handoffs++;
        }
        require(checkpoint==goals.size()&&overlapping&&handoffs>1,"Twenty points finish using overlapping predictions");
        State wrongCooldown=new State(live.x(),live.y(),live.z(),live.vx(),live.vy(),live.vz(),live.yaw(),live.onGround(),live.sprinting(),live.jumpCooldown()+1,live.tick(),live.fallDistance());
        require(!ParkourPrediction.exactStart(live,wrongCooldown),"Mismatched cooldown cannot use fast adoption");
        require(!ParkourPrediction.improves(route,0,5,new Result(route.states(),route.actions(),false,0,0,"TEST"),4),"An early handoff must not lose achieved checkpoint coverage");
        require(ParkourPrediction.improves(route,0,4,new Result(route.states(),route.actions(),false,0,0,"TEST"),5),"Genuine new checkpoint progress can replace the suffix");
        var complete=new Result(route.states(),route.actions(),true,0,0,"TEST");
        require(!ParkourPrediction.improves(complete,1,5,complete,5),"Do not replace an already valid finish with a longer repeat");
        require(ParkourPrediction.improves(complete,complete.actions().size(),5,complete,5),"An exhausted prefix can accept its next leg");
        System.out.println("ROLLING_PASS 20 ordered points, early overlapping anchors, exact momentum/cooldown adoption, latency-aware lead; handoffs="+handoffs+" ticks="+total);
    }
}
