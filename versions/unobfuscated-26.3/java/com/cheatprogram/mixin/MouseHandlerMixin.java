package com.cheatprogram.mixin;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.util.Cps;
import com.cheatprogram.util.KeyNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method="onMove",at=@At("HEAD"),cancellable=true)
	private void cheatprogram$appMove(long window,double x,double y,double dx,double dy,CallbackInfo ci){
        if(!com.cheatprogram.CheatProgram.initialized())return;if(window==Minecraft.getInstance().getWindow().handle()&&com.cheatprogram.render.WorldMusicController.move(x,y)){ci.cancel();return;}Minecraft mc=Minecraft.getInstance();if(window==mc.getWindow().handle()&&mc.gui.overlay()==null&&mc.gui.screen() instanceof com.cheatprogram.gui.AppCastInputScreen input){input.rawMouseMoved(x,y,dx,dy);ci.cancel();}}
    @Inject(method="onMove",at=@At("HEAD"),cancellable=true)
    private void cheatprogram$buildingLook(long window,double x,double y,double dx,double dy,CallbackInfo ci){
        if(!com.cheatprogram.CheatProgram.initialized())return;Minecraft mc=Minecraft.getInstance();if(window==mc.getWindow().handle()&&mc.gui.screen() instanceof com.cheatprogram.gui.BuildingBlueprintScreen building&&building.tourLooking()){building.tourMouseMoved(x,y,dx,dy);ci.cancel();}}
	@Inject(method="onScroll",at=@At("HEAD"),cancellable=true)
	private void cheatprogram$appScroll(long window,double horizontal,double vertical,CallbackInfo ci){
        if(!com.cheatprogram.CheatProgram.initialized())return;if(window==Minecraft.getInstance().getWindow().handle()&&com.cheatprogram.render.WorldMusicController.scroll(horizontal,vertical)){ci.cancel();return;}Minecraft mc=Minecraft.getInstance();if(window==mc.getWindow().handle()&&mc.gui.overlay()==null&&mc.gui.screen() instanceof com.cheatprogram.gui.AppCastInputScreen input){input.mouseScrolled(0,0,horizontal,vertical);ci.cancel();}}
	@Inject(method = "onButton", at = @At("HEAD"),cancellable=true)
	private void cheatprogram$onPress(long window, net.minecraft.client.input.MouseButtonInfo event, int action, CallbackInfo ci) {
        if(!com.cheatprogram.CheatProgram.initialized())return;
		int button=GLFW.mouseLegacy(event.button());
		Minecraft mc = Minecraft.getInstance();
		if(window==mc.getWindow().handle()&&com.cheatprogram.render.WorldMusicController.button(button,action)){ci.cancel();return;}
		if(window==mc.getWindow().handle()&&button>1&&com.cheatprogram.module.visual.AppCast.key(KeyNames.MOUSE_OFFSET+button,action)){ci.cancel();return;}
		if(window==mc.getWindow().handle()&&button>1&&com.cheatprogram.render.WorldControlPanel.key(KeyNames.MOUSE_OFFSET+button,action)){ci.cancel();return;}
		if (window != mc.getWindow().handle() || mc.gui.screen() != null || mc.gui.overlay() != null || mc.player == null) return;
		if (action == GLFW.GLFW_PRESS) Cps.click(button);
		if (button > 1) ModuleManager.onInput(KeyNames.MOUSE_OFFSET + button, action);
	}
}
