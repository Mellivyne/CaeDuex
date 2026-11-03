package com.cheatprogram.dev;

import com.cheatprogram.parkour.ParkourInput;
import com.cheatprogram.parkour.ParkourPhysics.Action;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Executes each production input adapter against isolated test-only client stubs. */
public final class ParkourNativeInputCheck {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        var mc=Minecraft.getInstance();var player=mc.player;
        ParkourInput.set(new Action(0,1,1,false,false));ParkourInput.apply((net.minecraft.client.player.KeyboardInput)player.input);
        require(player.input.forwardImpulse==1&&player.input.leftImpulse==1,"Native movement intent preserved");
        player.setSprinting(true); // Vanilla's double-tap or held sprint key runs after KeyboardInput.tick.
        player.input.forwardImpulse=-1;player.input.leftImpulse=0;player.setYRot(123);
        player.input.keyPresses=new net.minecraft.world.entity.player.Input(false,true,false,false,true,true,true);
        ParkourInput.beforeMovement(player);
        require(!player.isSprinting(),"Requested walking must survive vanilla's later sprint toggle");
        require(player.getYRot()==0&&player.input.forwardImpulse==1&&player.input.leftImpulse==1&&!player.input.keyPresses.jump()&&!player.input.keyPresses.shift(),"Final native input and yaw reject intervening auto-jump/key changes");
        ParkourInput.set(new Action(0,1,0,true,true));player.horizontalCollision=true;player.minorHorizontalCollision=true;
        player.setSprinting(false);ParkourInput.beforeMovement(player);
        require(player.isSprinting(),"Minor wall graze must not cancel a planned sprint jump");
        player.minorHorizontalCollision=false;player.setSprinting(true);ParkourInput.beforeMovement(player);
        require(!player.isSprinting(),"A real sprint-stopping collision must remain enforced");
        player.horizontalCollision=false;ParkourInput.beforeMovement(player);
        require(player.isSprinting(),"Native sprint becomes available after collision clears");
        for(int forward:new int[]{0,-1}){
            ParkourInput.set(new Action(0,forward,0,false,true));player.setSprinting(true);ParkourInput.beforeMovement(player);
            require(!player.isSprinting(),"Neutral/backward intent does not inherit physical sprint");
        }
        ParkourInput.set(new Action(0,1,0,false,false));var other=new LocalPlayer();other.setSprinting(true);ParkourInput.beforeMovement(other);
        require(other.isSprinting(),"Only the controlled local player is changed");
        ParkourInput.clear();player.setSprinting(true);ParkourInput.beforeMovement(player);
        require(player.isSprinting(),"Disabled automation leaves vanilla sprint untouched");
        System.out.println("NATIVE_INPUT_PASS late vanilla sprint override, minor/major collision, neutral/backward, owner and disabled guards");
    }
}
