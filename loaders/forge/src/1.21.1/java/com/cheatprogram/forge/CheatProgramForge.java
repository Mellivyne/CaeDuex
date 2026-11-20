package com.cheatprogram.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Client initialization uses the native shared mixins for input, ticks and HUD. */
@Mod("cheatprogram")
public final class CheatProgramForge {
    @Mod.EventBusSubscriber(modid="cheatprogram",bus=Mod.EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> new com.cheatprogram.CheatProgram().onInitializeClient());
        }
    }
}
