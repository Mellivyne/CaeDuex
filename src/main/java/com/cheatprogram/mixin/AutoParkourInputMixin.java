package com.cheatprogram.mixin;
import com.cheatprogram.module.movement.AutoParkour;
import com.cheatprogram.parkour.ParkourInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(KeyboardInput.class)
public abstract class AutoParkourInputMixin {
    @Inject(method="tick",at=@At("HEAD"))
    private void cheatprogram$parkourPrepare(CallbackInfo ci){AutoParkour.beforeInputTick();}
    @Inject(method="tick",at=@At("TAIL"))
    private void cheatprogram$parkourApply(CallbackInfo ci){ParkourInput.apply((KeyboardInput)(Object)this);}
}
