package com.cheatprogram.parkour;
import net.minecraft.client.player.LocalPlayer;
public final class ParkourPlayerPhysics {
    public static double gravity(LocalPlayer player){return player.getGravity();}
    public static int minimumY(net.minecraft.client.multiplayer.ClientLevel level){return level.getMinY();}
}
