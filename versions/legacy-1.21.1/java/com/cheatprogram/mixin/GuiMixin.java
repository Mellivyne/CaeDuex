package com.cheatprogram.mixin;

import com.cheatprogram.gui.GuiBase;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.parkour.ParkourClient;
import com.cheatprogram.render.WorldControlPanel;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method="renderCrosshair",at=@At("HEAD"),cancellable=true)
	private void cheatprogram$hidePanelCrosshair(CallbackInfo ci) {
		if (ParkourClient.screen() instanceof GuiBase || WorldControlPanel.inputActive()) ci.cancel();
	}
	@Inject(method = "render", at = @At("TAIL"))
	private void cheatprogram$renderHud(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
		HudRenderer.render(graphics, delta.getGameTimeDeltaPartialTick(false));

	}
}
