package com.cheatprogram.mixin;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.capture.AppCaptureEngine;
import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.module.legit.Zoom;
import com.cheatprogram.module.visual.NoHurtCam;
import com.cheatprogram.module.visual.WorldColors;
import com.cheatprogram.render.AppCastPointer;
import com.cheatprogram.render.Render3D;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void cheatprogram$pointerLifecycle(CallbackInfo ci) {
        AppCaptureEngine.frameBoundary();
        HudRenderer.beginFrame();
        AppCastPointer.beforeFrame();
    }

    @WrapOperation(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
    private void cheatprogram$fullscreenPointer(Screen screen, GuiGraphics graphics, int x, int y, float partial,
                                               Operation<Void> original) {
        original.call(screen, graphics, x, y, partial);
        HudRenderer.afterScreen(graphics, screen);
        AppCastPointer.afterScreen(graphics, screen);
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void cheatprogram$worldColors(CallbackInfo ci) {
        WorldColors colors = WorldColors.active();
        if (colors != null) colors.draw();
    }

    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private double cheatprogram$zoom(double original, Camera camera, float partialTick, boolean useFovSetting) {
        return useFovSetting ? Zoom.apply((float) original) : original;
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void cheatprogram$noHurtCam(PoseStack pose, float partialTick, CallbackInfo ci) {
        if (NoHurtCam.active()) ci.cancel();
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void cheatprogram$render3d(LevelRenderer renderer, DeltaTracker delta, boolean outline, Camera camera,
                                      GameRenderer gameRenderer, LightTexture light, Matrix4f view, Matrix4f projection,
                                      Operation<Void> original) {
        original.call(renderer, delta, outline, camera, gameRenderer, light, view, projection);
        LegacyRenderSystem.getModelViewStack().set(RenderSystem.getModelViewMatrix());
        try {
            ModuleManager.onRender3D(new Render3D(camera, new Matrix4f(view), projection,
                    delta.getGameTimeDeltaPartialTick(false)));
        } catch (Throwable failure) {
            CheatProgram.LOGGER.error("World overlay rendering failed", failure);
        }
    }
}
