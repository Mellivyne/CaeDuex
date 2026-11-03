package com.cheatprogram.mixin;

import com.cheatprogram.render.RearMirrorRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
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
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"))
	private void cheatprogram$rearScene(LevelRenderer renderer, GraphicsResourceAllocator allocator, boolean outline,
			net.minecraft.client.renderer.state.level.CameraRenderState camera, com.mojang.blaze3d.buffers.GpuBufferSlice fog, org.joml.Vector4f fogColor, boolean sky, boolean post, Operation<Void> original) {
		RearMirrorRenderer.capture(renderer, allocator, net.minecraft.client.Minecraft.getInstance().getDeltaTracker(), net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera(), net.minecraft.client.Minecraft.getInstance().gameRenderer, fog, fogColor, sky);
		original.call(renderer, allocator, outline, camera, fog, fogColor, sky, post);
	}
}
