package com.cheatprogram.mixin;

import com.cheatprogram.parkour.ParkourInput;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Finalize native sprint intent after vanilla's input-dependent toggles. */
@Mixin(LocalPlayer.class)
public abstract class ParkourSprintMixin {
    @Inject(method="serverAiStep",at=@At("HEAD"))
    private void cheatprogram$parkourSprint(CallbackInfo ci){
        ParkourInput.beforeMovement((LocalPlayer)(Object)this);
    }
}
