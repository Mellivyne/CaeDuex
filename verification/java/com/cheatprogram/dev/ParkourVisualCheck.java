package com.cheatprogram.dev;

import com.cheatprogram.parkour.ParkourColors;
import com.cheatprogram.parkour.ParkourLineHistory;
import java.util.Map;

public final class ParkourVisualCheck {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        var history=new ParkourLineHistory<String,String>(3);
        long second=1_000_000_000L;
        check(history.update(Map.of("a","first"),0,3).getFirst().opacity()==1,"Active line faded");
        history.update(Map.of("a","recolored"),second,3);
        check(history.size()==1,"Animation accumulated duplicate geometry");
        var held=history.update(Map.of(),second*3,3);
        check(held.size()==1&&held.getFirst().value().equals("recolored")&&held.getFirst().opacity()==1,"Hold duration incorrect");
        double fade=history.update(Map.of(),second*3+second*3/4,3).getFirst().opacity();
        check(fade>0&&fade<1,"Missing terminal fade");
        check(history.update(Map.of(),second*4,3).isEmpty(),"Expired line remained");
        history.update(Map.of("a","a"),second*5,30);
        check(history.update(Map.of(),second*5,0).isEmpty(),"Zero duration retained old lines");
        for(int i=0;i<10000;i++)history.update(Map.of("line"+i,"value"),second*6+i,30);
        check(history.size()==3,"History grew beyond bounded storage");
        history.clear();check(history.size()==0,"World/disable cleanup failed");
        check(history.update(Map.of("active","line"),second*8,0).size()==1,"Zero hold hid current lines");
        check(history.update(Map.of(),second*8,Double.NaN).isEmpty(),"Nonfinite duration retained old lines");
        check(ParkourColors.rainbow(0)==ParkourColors.rainbow(1),"Rainbow seam");
        check(ParkourColors.rainbow(-.25)==ParkourColors.rainbow(.75),"Negative palette phase");
        check(ParkourColors.rainbow(0)!=ParkourColors.rainbow(.5),"Rainbow has no distinct colors");
        int last=ParkourColors.rainbow(0);
        for(int i=1;i<=2048;i++){
            int color=ParkourColors.rainbow(i/2048.0);
            for(int shift:new int[]{0,8,16})check(Math.abs(((color>>shift)&255)-((last>>shift)&255))<12,"Abrupt rainbow transition");
            last=color;
        }
        System.out.println("PARKOUR_VISUAL_PASS lifetime, fade, recolor deduplication, zero hold, 10000 updates, cleanup and rainbow continuity");
    }
}
