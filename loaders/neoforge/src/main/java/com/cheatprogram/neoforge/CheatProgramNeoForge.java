package com.cheatprogram.neoforge;

import com.cheatprogram.CheatProgram;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = CheatProgram.MOD_ID, dist = Dist.CLIENT)
public final class CheatProgramNeoForge {
    public CheatProgramNeoForge(IEventBus modBus) {
        modBus.addListener(this::setupClient);
    }

    private void setupClient(FMLClientSetupEvent event) {
        event.enqueueWork(() -> new CheatProgram().onInitializeClient());
    }
}
