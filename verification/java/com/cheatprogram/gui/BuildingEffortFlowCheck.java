package com.cheatprogram.gui;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Standalone geometry and visual timing checks, without a game, model API or GPU. */
public final class BuildingEffortFlowCheck {
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static BufferedImage frame(double time){
        BufferedImage image=new BufferedImage(912,156,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(4,4);
        BuildingEffortFlow.draw((x,y,w,h,r,color)->{
            require(w>=0&&h>=0&&Float.isFinite(x)&&Float.isFinite(y),"invalid flow geometry");
            g.setColor(new Color(color,true));g.fill(new RoundRectangle2D.Float(x,y,w,h,r*2,r*2));
        },12,9,204,22,time,1);
        g.dispose();return image;
    }
    public static void main(String[] args)throws Exception {
        require(BuildingEffortFlow.fraction(5,6)==1,"Ultra does not reach the end");
        require(BuildingEffortFlow.fraction(0,6)==0,"Low does not reach the start");
        float left=12,width=204,r=11;
        require(BuildingEffortFlow.thumb(left,width,r,1)+r==left+width,"empty space after Ultra thumb");
        require(BuildingEffortFlow.thumb(left,width,r,0)-r==left,"empty space before Low thumb");
        for(int i=0;i<6;i++){
            float center=BuildingEffortFlow.thumb(left,width,r,(float)i/5);
            require(BuildingEffortFlow.selection(center,left,width,r,6)==i,"drag selection does not match rendered knob");
        }
        require(BuildingEffortFlow.halfHeight(0,width,22)==0&&BuildingEffortFlow.halfHeight(width,width,22)==0,"caps are square");
        require(BuildingEffortFlow.halfHeight(width/2,width,22)==11,"track center does not fill");
        BufferedImage first=frame(0),next=frame(.15);
        int changed=0,painted=0;
        for(int y=0;y<first.getHeight();y++)for(int x=0;x<first.getWidth();x++){
            if((first.getRGB(x,y)>>>24)>0){painted++;if(first.getRGB(x,y)!=next.getRGB(x,y))changed++;}
        }
        require(changed>painted*.7,"Ultra flow is too slow or stationary");
        if(args.length>0){
            BufferedImage sheet=new BufferedImage(912,156*3,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=sheet.createGraphics();g.setColor(new Color(22,25,47));g.fillRect(0,0,912,468);
            g.drawImage(first,0,0,null);g.drawImage(next,0,156,null);g.drawImage(frame(.3),0,312,null);g.dispose();
            ImageIO.write(sheet,"png",Path.of(args[0]).toFile());
        }
        System.out.println("BUILDING_EFFORT_FLOW_PASS exact endpoints, drag alignment, curved capsule, rapid visible motion");
    }
}
