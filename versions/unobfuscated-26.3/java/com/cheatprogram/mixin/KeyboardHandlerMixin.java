package com.cheatprogram.mixin;

import com.cheatprogram.core.ModuleManager;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"),cancellable=true)
	private void cheatprogram$keyPress(long window, int action, net.minecraft.client.input.KeyEvent event, CallbackInfo ci) {
        if(!com.cheatprogram.CheatProgram.initialized())return;
		int key=com.cheatprogram.compat.sdl.GLFW.legacy(event.key());
		Minecraft mc = Minecraft.getInstance();
		if (window != mc.getWindow().handle()) return;
		// SDL uses -1 for repeat, while shared bindings use GLFW's 2.
		if (action == -1) action = com.cheatprogram.compat.sdl.GLFW.GLFW_REPEAT;
		if(window==mc.getWindow().handle()&&com.cheatprogram.gui.ClickGuiScreen.captureBindingInput(key,action)){ci.cancel();return;}
		if(window==mc.getWindow().handle()&&com.cheatprogram.parkour.ParkourWaypointEditor.key(key,action)){ci.cancel();return;}
		if (com.cheatprogram.compat.PanelKey.event(key, action)) { ci.cancel(); return; }
		if(window==mc.getWindow().handle()&&com.cheatprogram.render.WorldMusicController.key(key,action)){ci.cancel();return;}
		if(window==mc.getWindow().handle()&&action==org.lwjgl.glfw.GLFW.GLFW_PRESS&&mc.options.keyFullscreen.matches(event))com.cheatprogram.capture.AppCastWindowCompatibility.release();
		if(window==mc.getWindow().handle()&&com.cheatprogram.render.WorldControlPanel.key(key,action)){ci.cancel();return;}
		if(window==mc.getWindow().handle()&&com.cheatprogram.module.visual.AppCast.key(key,action)){ci.cancel();return;}
		if (window == mc.getWindow().handle() && mc.gui.screen() == null && mc.gui.overlay() == null && mc.player != null) {
			ModuleManager.onInput(key, action);
		}
	}
}
