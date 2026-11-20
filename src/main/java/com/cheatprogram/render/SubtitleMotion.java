package com.cheatprogram.render;

import java.util.ArrayList;
import java.util.List;

/** Deterministic subtitle animation and fixed-anchor math, independent of the game/render clock. */
public final class SubtitleMotion {
    public static final String DEFAULT_STYLE="lyrics_float";
    private static final List<String> STYLES=List.of(DEFAULT_STYLE,"lyrics_typewriter","lyrics_shake","lyrics_none");

    public static String[] styles(){return STYLES.toArray(String[]::new);}

    public static String currentStyle(String mode){
        if("lyrics_word".equals(mode))return "lyrics_typewriter";
        return mode!=null&&STYLES.contains(mode)?mode:DEFAULT_STYLE;
    }

    private static double progress(double age,double duration){
        return bounded(finite(age,0)/Math.max(.01,finite(duration,.45)),0,1,0);
    }
    public record Frame(float alpha,float x,float y,float scale,float rotation) { }
    public record Glyph(float x,float y,float rotation) { }
    public record Point(double x,double y,double z) { }
    public record View(Point point,float yaw,float pitch) { }
    /** Camera-relative orthonormal placement; yaw and pitch are sampled anew every frame. */
    public static View view(double x,double y,double z,double yaw,double pitch,double distance,double side,double height){
        double a=Math.toRadians(finite(yaw,0)),p=Math.toRadians(bounded(pitch,-90,90,0)),s=Math.sin(a),c=Math.cos(a),sp=Math.sin(p),cp=Math.cos(p);
        double d=bounded(distance,.5,48,3.5),r=bounded(side,-16,16,0),h=bounded(height,-8,12,0);
        return new View(new Point(finite(x,0)-s*cp*d-c*r-s*sp*h,finite(y,0)-sp*d+cp*h,finite(z,0)+c*cp*d-s*r+c*sp*h),(float)(finite(yaw,0)+180),(float)-bounded(pitch,-90,90,0));
    }
    /** Relative offsets use the same Y/X/Z plane rotation as the subtitle glyph renderer. */
    public static Point relative(Point center,double yaw,double pitch,double roll,double side,double height,double depth){
        double y=Math.toRadians(finite(yaw,0)),p=Math.toRadians(finite(pitch,0)),r=Math.toRadians(finite(roll,0));
        double x1=side*Math.cos(r)-height*Math.sin(r),y1=side*Math.sin(r)+height*Math.cos(r),y2=y1*Math.cos(p)-depth*Math.sin(p),z2=y1*Math.sin(p)+depth*Math.cos(p);
        return new Point(center.x()+x1*Math.cos(y)-z2*Math.sin(y),center.y()+y2,center.z()+x1*Math.sin(y)+z2*Math.cos(y));
    }
    /** Captured once per placement/phrase; editing offsets never captures the player's pose again. */
    public record Anchor(double x,double y,double z,double yaw) {
        public Anchor {x=finite(x,0);y=finite(y,0);z=finite(z,0);yaw=finite(yaw,0);}
        public Point target(double distance,double side,double height){
            double a=Math.toRadians(finite(yaw,0)),s=Math.sin(a),c=Math.cos(a);
            double d=bounded(distance,.5,48,3.5),r=bounded(side,-16,16,0),h=bounded(height,-8,12,0);
            return new Point(x-s*d+c*r,y+h,z+c*d+s*r);
        }
    }
    private SubtitleMotion() { }
    public static double smoothing(double dt,double seconds){
        return seconds<=0?1:-Math.expm1(-bounded(dt,0,.1,0)/Math.max(.01,finite(seconds,.22)));
    }
    public static Frame frame(String mode,double age,double duration){
        double t=progress(age,duration),ease=t*t*(3-2*t);
        float alpha=(float)ease,x=0,y=0,scale=1,rotation=0;
        switch(mode==null?"":mode){
            case "lyrics_none" -> alpha=1;
            case "lyrics_float" -> y=(float)(.22*(1-ease));
            // Fade has no displacement. Glyph modes animate only their letters.
            default -> { }
        }
        return new Frame(alpha,x,y,scale,rotation);
    }
    /** Positions are fractions of a glyph's nominal height, with no accumulating random state. */
    public static Glyph glyph(String mode,double age,int ordinal,double amount,double speed){
        double a=bounded(amount,0,5,.08),w=bounded(speed,.1,24,8)*Math.PI*2;
        double time=bounded(age,0,1e9,0),phase=ordinal*2.3999632297,x=0,y=0,rotation=0;
        switch(mode==null?"":mode){
            case "lyrics_shake" -> {x=a*(Math.sin(time*w+phase)+.35*Math.sin(time*w*1.79+phase*2)) /1.35;y=a*(Math.cos(time*w*1.13+phase)+.3*Math.sin(time*w*.73-phase))/1.3;rotation=3*a*Math.sin(time*w*.91+phase);}
            case "lyrics_wave" -> y=a*Math.sin(time*w*.22-ordinal*.65);
            case "lyrics_bounce" -> y=-a*Math.pow(Math.max(0,Math.sin(time*w*.35-ordinal*.55)),2);
            default -> { }
        }
        return new Glyph((float)x,(float)y,(float)rotation);
    }
    public static boolean glyphsMove(String mode){return "lyrics_shake".equals(mode)||"lyrics_wave".equals(mode)||"lyrics_bounce".equals(mode);}
    /** History may contain only cues whose start has already passed; future cue indices never escape. */
    public static int[] playedHistory(int current,int requested,List<Integer> seen){
        if(current<=0||requested<=1||seen==null)return new int[0];List<Integer> result=new ArrayList<>();
        int count=Math.max(0,Math.min(4,requested)-1);
        for(int i=seen.size()-1;i>=0&&result.size()<count;i--){Integer cue=seen.get(i);if(cue!=null&&cue>=0&&cue<current&&!result.contains(cue))result.add(cue);}
        return result.stream().mapToInt(Integer::intValue).toArray();
    }
    private static double finite(double value,double fallback){return Double.isFinite(value)?value:fallback;}
    private static double bounded(double value,double min,double max,double fallback){return Math.max(min,Math.min(max,finite(value,fallback)));}
}
