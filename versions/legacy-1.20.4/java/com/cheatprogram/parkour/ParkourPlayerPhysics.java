package com.cheatprogram.parkour;
import net.minecraft.client.player.LocalPlayer;
public final class ParkourPlayerPhysics {
    public static int minimumY(net.minecraft.client.multiplayer.ClientLevel level){return level.getMinBuildHeight();}
    public static double gravity(LocalPlayer player){return .08;}
}
