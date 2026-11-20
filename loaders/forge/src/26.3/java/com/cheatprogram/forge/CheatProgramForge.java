package com.cheatprogram.forge;

import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.render.AppCastPointer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("cheatprogram")
public final class CheatProgramForge {
    public CheatProgramForge(FMLJavaModLoadingContext context) {
        FMLClientSetupEvent.getBus(context.getModBusGroup()).addListener(event ->
                event.enqueueWork(() -> {
                    new com.cheatprogram.CheatProgram().onInitializeClient();
                    ScreenEvent.Render.Post.BUS.addListener(CheatProgramForge::afterScreen);
                }));
    }

    private static void afterScreen(ScreenEvent.Render.Post event) {
        if (event.getScreen() != Minecraft.getInstance().gui.screen()) return;
        HudRenderer.afterScreen(event.getGuiGraphics(), event.getScreen());
        AppCastPointer.afterScreen(event.getGuiGraphics(), event.getScreen());
    }
}
