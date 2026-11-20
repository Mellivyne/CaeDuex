package com.cheatprogram.gui;

/** Shared, device-independent capsule geometry for the effort control and its visual checks. */
final class BuildingEffortFlow {
    @FunctionalInterface interface Painter { void rounded(float x,float y,float width,float height,float radius,int color); }
    private static final int[] COLORS={0xff37cbff,0xff6371ff,0xffae67ff,0xffff79c5,0xffffbb7d,0xff80eddf};
    private BuildingEffortFlow(){ }
    static float fraction(float marker,int levels){return levels<2?0:Math.max(0,Math.min(1,marker/(levels-1)));}
    static float thumb(float left,float width,float radius,float fraction){return left+radius+Math.max(0,width-2*radius)*Math.max(0,Math.min(1,fraction));}
    static int selection(float mouse,float left,float width,float radius,int levels){
        float span=width-2*radius;
        return span<=0?0:Math.round(Math.max(0,Math.min(1,(mouse-left-radius)/span))*(levels-1));
    }
    static float halfHeight(float local,float width,float height){
        float r=Math.min(height/2,width/2),center=height/2;
        if(local<0||local>width)return 0;
        float edge=Math.min(local,width-local);
        return edge>=r?center:(float)Math.sqrt(Math.max(0,r*r-(r-edge)*(r-edge)))+center-r;
    }
    static void draw(Painter painter,float x,float y,float width,float height,double seconds,float opacity){
        if(width<=.01f||height<=.01f||opacity<=0)return;
        float t=(float)(seconds%120),middle=y+height/2;
        float radius=Math.min(height/2,width/2),body=Math.max(0,width-2*radius);
        painter.rounded(x,y,radius*2,height,radius,alpha(color(radius/width*.83f-t*.78f),opacity));
        painter.rounded(x+width-2*radius,y,radius*2,height,radius,
                alpha(color((width-radius)/width*.83f-t*.78f),opacity));
        int columns=Math.max(1,Math.min(144,(int)Math.ceil(body/1.4f)));
        float step=body/columns;
        for(int i=0;i<columns;i++){
            float start=radius+i*step,local=start+step*.5f;
            int color=color(local/width*.83f-t*.78f);
            if(body>0)painter.rounded(x+start,y,step+.2f,height,0,alpha(color,opacity));
        }
        // Short curved trails cross the whole control in well under one second.
        for(int i=0;i<12;i++){
            float speed=1.15f+(i%5)*.13f,head=fract(i*.618034f+t*speed);
            for(int tail=4;tail>=0;tail--){
                float local=head*width-tail*(height*.22f+1);
                if(local<1||local>width-1)continue;
                float half=halfHeight(local,width,height)-1;
                float side=Math.min(height*.16f,1.5f+(4-tail)*.28f);
                if(half<=side)continue;
                float curve=(float)Math.sin(local/width*6.283185f-t*5.6f+i*2.1f);
                float py=middle+curve*(half-side)*.67f;
                float length=(tail==0?height*.2f:height*.38f)*(1-tail*.13f);
                length=Math.min(length,Math.min(local,width-local)*1.7f);
                if(length<=0)continue;
                painter.rounded(x+local-length/2,py-side/2,length,side,side/2,
                        alpha(0xfff5edff,opacity*(.48f-tail*.085f)));
            }
        }
        for(int i=0;i<8;i++){
            float local=fract(i*.381966f+t*(1.4f+i*.027f))*width;
            float half=halfHeight(local,width,height)-1.2f,side=Math.min(1.5f,height*.12f);
            if(half<side)continue;
            float py=middle+(float)Math.sin(i*1.7f+t*7.2f)*(half-side)*.7f;
            painter.rounded(x+local-side/2,py-side/2,side,side,.2f,alpha(0xffffffff,opacity*.9f));
        }
    }
    private static float fract(float value){return value-(float)Math.floor(value);}
    private static int color(float phase){
        float position=fract(phase)*COLORS.length;int first=(int)position;
        float blend=position-first;blend=blend*blend*(3-2*blend);
        int a=COLORS[first],b=COLORS[(first+1)%COLORS.length];
        return 0xff000000|((int)(((a>>16)&255)+(((b>>16)&255)-((a>>16)&255))*blend)<<16)
                |((int)(((a>>8)&255)+(((b>>8)&255)-((a>>8)&255))*blend)<<8)
                |(int)((a&255)+((b&255)-(a&255))*blend);
    }
    private static int alpha(int color,float opacity){return (Math.round(Math.max(0,Math.min(1,opacity))*255)<<24)|(color&0xffffff);}
}
