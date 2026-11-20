package com.cheatprogram.render;

import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;

/** Bounded album scenes; rendering and picking consume the very same transforms. */
public final class AlbumSpaceModel {
    public record Card(int index, Matrix4f matrix) { }
    private static final float PI = (float)Math.PI;
    private int count, selected;
    private String layout = "stars";
    private float position,target,zoom=1,targetZoom=1,orbit,targetOrbit,tilt,targetTilt,time;
    private float startX,startY,startTarget,startOrbit,startTilt;
    private boolean held;
    private final java.util.Map<Integer,Float> emphasis = new java.util.HashMap<>();
    public static boolean supports(String value) {
        return "stars".equals(value) || "sphere".equals(value);
    }
    public int selected() { return selected; }
    public void reset(String kind,int index,int size) {
        layout=supports(kind)?kind:"stars"; count=Math.max(0,size); selected=bound(index);
        position=target=selected;
        orbit=targetOrbit=tilt=targetTilt=0; zoom=targetZoom=1;
        held=false; time=0;
        emphasis.clear(); emphasis.put(selected,1f);
        if(layout.equals("sphere")) { targetOrbit=-sphereLongitude(selected); targetTilt=sphereLatitude(selected); orbit=targetOrbit;tilt=targetTilt; }
    }
    private int bound(int index) { return Math.max(0,Math.min(Math.max(0,count-1),index)); }
    private float clampPosition(float value) { return Math.max(0,Math.min(Math.max(0,count-1),value)); }
    public void select(int index) {
        selected=bound(index); target=selected;
        emphasis.putIfAbsent(selected,0f);
        if(layout.equals("sphere")) {
            float wanted=-sphereLongitude(selected);
            targetOrbit+=(float)Math.atan2(Math.sin(wanted-targetOrbit),Math.cos(wanted-targetOrbit));
            targetTilt=sphereLatitude(selected);
        }
    }
    public void begin(float x,float y) {
        if(!Float.isFinite(x)||!Float.isFinite(y))return;
        held=true;startX=x;startY=y;startTarget=target;startOrbit=targetOrbit;startTilt=targetTilt;
    }
    public void drag(float x,float y) {
        if(!held||!Float.isFinite(x)||!Float.isFinite(y))return;
        float dx=x-startX,dy=y-startY;
        switch(layout) {
            case "sphere" -> {
                targetOrbit=startOrbit+dx*.009f;
                targetTilt=Math.max(-PI*.48f,Math.min(PI*.48f,startTilt+dy*.007f));
                if(count>85)target=clampPosition(startTarget+dy/45);
            }
            default -> {
                target=clampPosition(startTarget-dx/76+dy/48*7);
                targetOrbit=startOrbit+dx*.0015f;targetTilt=Math.max(-.25f,Math.min(.25f,startTilt+dy*.001f));
            }
        }
    }
    public boolean release(float x,float y) {
        boolean click=held&&Float.isFinite(x)&&Float.isFinite(y)&&Math.hypot(x-startX,y-startY)<4;
        held=false;
        return click;
    }
    public void cancel(){held=false;}
    public void scroll(double amount) {
        if(!Double.isFinite(amount))return;
        float limited=(float)Math.max(-30,Math.min(30,amount));
        float maximum = layout.equals("sphere") ? 2f : 2.4f;
        targetZoom = Math.max(.35f, Math.min(maximum, targetZoom * (float)Math.pow(1.12, limited)));
    }
    public void update(float seconds) {
        float dt=Float.isFinite(seconds)?Math.max(0,Math.min(.1f,seconds)):0,a=1-(float)Math.exp(-11*dt);
        time+=dt; position+=(target-position)*a; if(Math.abs(target-position)<.0005f)position=target; zoom+=(targetZoom-zoom)*a;
        orbit+=(targetOrbit-orbit)*a;tilt+=(targetTilt-tilt)*a;
        emphasis.replaceAll((i,v)->v+((i==selected?1:0)-v)*a);
        emphasis.entrySet().removeIf(e->e.getKey()!=selected&&e.getValue()<.001f);
    }
    private float focus(int index){return emphasis.getOrDefault(index,0f);}
    private float sphereLongitude(int index){return (float)((Math.floorMod(index,Math.min(85,Math.max(1,count)))*2.399963229728653)% (Math.PI*2));}
    private float sphereLatitude(int index){int slots=Math.min(85,Math.max(1,count)),slot=Math.floorMod(index,slots);return (float)Math.asin(1-2*(slot+.5)/slots);}
    private Matrix4f scene() {
        if(layout.equals("sphere"))return new Matrix4f().translate(0,0,-8.3f).rotateX(tilt).rotateY(orbit).scale(zoom);
        return new Matrix4f().translate(0,0,-7).rotateY(orbit).rotateX(tilt).scale(zoom).translate(0,0,7);
    }
    public List<Card> cards() {
        var cards=new ArrayList<Card>();if(count==0)return cards;
        int center=bound(Math.round(position));
        int first=Math.max(0,center-42),last=(int)Math.min((long)count-1,(long)center+42);
        if(layout.equals("sphere")){int visible=Math.min(85,count);first=Math.max(0,Math.min(count-visible,center-visible/2));last=first+visible-1;}
        Matrix4f scene=scene();
        for(int i=first;i<=last;i++) {
            float f=focus(i),x,y,z,rx=0,ry=0,size;
            if(layout.equals("sphere")) {
                float lon=sphereLongitude(i),lat=sphereLatitude(i),radiusSphere=2.7f+f*.75f;
                x=radiusSphere*(float)(Math.cos(lat)*Math.sin(lon));y=radiusSphere*(float)Math.sin(lat);z=radiusSphere*(float)(Math.cos(lat)*Math.cos(lon));
                ry=lon;rx=-lat;size=(float)(.42f+.14f*(1-Math.min(85,count)/85f))+f*.45f;
            }else {
                float row=i/7,base=position/7;
                x=(i%7-3)*1.75f;y=(row-base)*1.25f+.16f*(float)Math.sin(time*.45+i*2.4);
                z=-8-(float)(Math.sin(i*2.399)*1.3);ry=.15f*(float)Math.sin(i*1.8+time*.15);size=.64f+.12f*(float)((Math.sin(i*5.3)+1)*.5);
                x*=1-f*.8f;y*=1-f*.9f;z+=(-3.8f-z)*f;ry*=1-f;size+=f*.25f;
            }
            Matrix4f matrix=new Matrix4f(scene).translate(x,y,z);
            if(layout.equals("sphere"))matrix.rotateY(ry).rotateX(rx);else matrix.rotateX(rx).rotateY(ry);
            cards.add(new Card(i,matrix.scale(size)));
        }
        return cards;
    }
    /** Browse and prefetch follow the viewport, independently of the playing song. */
    public int browseIndex() { return bound(Math.round(position)); }
    public List<Integer> preloadIndices() {
        var indices = new java.util.LinkedHashSet<Integer>();
        if (count == 0) return List.of();
        int center = browseIndex(); indices.add(center);
        for (var card : cards()) indices.add(card.index());
        int direction = target >= startTarget ? 1 : -1;
        for (int i = 43; i < 59; i++) {
            int index = center + direction * i;
            if (index >= 0 && index < count) indices.add(index);
        }
        return new ArrayList<>(indices);
    }
}
