package com.cheatprogram.mixin;

import com.cheatprogram.render.RearMirrorRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LightTexture;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class RearMirrorGameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void cheatprogram$mirrorLifecycle(CallbackInfo ci) {
		RearMirrorRenderer.checkLifecycle();
	}
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void cheatprogram$rearScene(LevelRenderer renderer, DeltaTracker delta, boolean outline, Camera camera,
                                      GameRenderer gameRenderer, LightTexture light, Matrix4f view,
                                      Matrix4f projection, Operation<Void> original){
        RearMirrorRenderer.capture(renderer, delta, camera, gameRenderer, light);
        original.call(renderer, delta, outline, camera, gameRenderer, light, view, projection);
    }
}
