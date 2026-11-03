package com.cheatprogram.parkour;
import net.minecraft.client.Minecraft;
public final class ParkourClient {
    // Legacy clients have no input-inactivity frame limiter.
    public static void inputActivity(){}
    public static net.minecraft.client.gui.screens.Screen screen(){return Minecraft.getInstance().screen;}
    public static void open(net.minecraft.client.gui.screens.Screen screen){Minecraft.getInstance().setScreen(screen);}
    public static boolean screenOpen(){var mc=Minecraft.getInstance();return mc.screen!=null||mc.getOverlay()!=null;}
}
