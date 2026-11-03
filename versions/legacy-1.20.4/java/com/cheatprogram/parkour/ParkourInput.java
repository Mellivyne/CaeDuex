package com.cheatprogram.parkour;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import com.cheatprogram.compat.legacy.LegacyInput;
/** Only native movement intent; no position, velocity or packet manipulation. */
public final class ParkourInput {
    private static ParkourPhysics.Action action;
    public static void set(ParkourPhysics.Action next){action=next;}
    public static void clear(){action=null;}
    public static boolean active(){return action!=null;}
    public static void apply(KeyboardInput input){
        var mc=Minecraft.getInstance();var a=action;if(a==null||mc.player==null||input!=mc.player.input)return;
        new LegacyInput(a.forward()>0,a.forward()<0,a.strafe()>0,a.strafe()<0,a.jump(),false,a.sprint()).apply(input);
        input.forwardImpulse=a.forward();input.leftImpulse=a.strafe();
    }
    public static void beforeMovement(net.minecraft.client.player.LocalPlayer player){
        var a=action;if(a==null||player!=Minecraft.getInstance().player)return;
        if(!(player.input instanceof KeyboardInput keyboard))return;
        apply(keyboard);player.setYRot(a.yaw());
        player.setSprinting(a.sprint()&&a.forward()>0&&(!player.horizontalCollision||player.minorHorizontalCollision));
    }
}
