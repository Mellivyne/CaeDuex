package com.cheatprogram.parkour;
import net.minecraft.client.Minecraft;
public final class ParkourClient {
    public static void inputActivity(){Minecraft.getInstance().getFramerateLimitTracker().onInputReceived();}
    public static net.minecraft.client.gui.screens.Screen screen(){return Minecraft.getInstance().gui.screen();}
    public static void open(net.minecraft.client.gui.screens.Screen screen){Minecraft.getInstance().gui.setScreen(screen);}
    public static boolean screenOpen(){var mc=Minecraft.getInstance();return mc.gui.screen()!=null||mc.gui.overlay()!=null;}
}
