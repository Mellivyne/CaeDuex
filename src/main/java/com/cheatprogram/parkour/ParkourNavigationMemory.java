package com.cheatprogram.parkour;

import com.cheatprogram.parkour.ParkourPhysics.State;
import java.util.ArrayDeque;

/** Small per-checkpoint memory for completed prefixes, never individual ticks. */
public final class ParkourNavigationMemory {
    private final ArrayDeque<State> endpoints=new ArrayDeque<>();
    private int checkpoint=-1;
    public void clear(){endpoints.clear();checkpoint=-1;}
    public boolean repeated(int point,State state){
        if(point!=checkpoint){endpoints.clear();checkpoint=point;}
        boolean repeated=false;int index=0;
        for(State previous:endpoints){
            // Adjacent short prefixes can legitimately finish on the same
            // corner while braking/turning. Detect returns to an earlier leg.
            if(index++<endpoints.size()-1&&Math.hypot(previous.x()-state.x(),previous.z()-state.z())<.8&&Math.abs(previous.y()-state.y())<.3)repeated=true;
        }
        endpoints.addLast(state);if(endpoints.size()>8)endpoints.removeFirst();
        return repeated;
    }
}
