package com.cheatprogram.mixin;

import com.cheatprogram.gui.BuildingWorldPreview;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class BuildingTutorialPlayerMixin {
    @Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
    private void cheatprogram$applyTutorialPose(CallbackInfo ci) {
        // The private preview server already advances this player from the same pose.
        if (BuildingWorldPreview.drivePlayer((LocalPlayer) (Object) this)) ci.cancel();
    }
}
