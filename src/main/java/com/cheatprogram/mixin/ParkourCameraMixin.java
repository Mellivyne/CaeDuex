package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.parkour.ParkourCamera;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Filter before vanilla third-person orbit/collision, rather than rotating after placement. */
@Mixin(Camera.class)
public abstract class ParkourCameraMixin {
    @WrapOperation(method="setup",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float cheatprogram$parkourView(Entity entity,float partial,Operation<Float> original){
        float yaw=original.call(entity,partial);
        return entity==Minecraft.getInstance().player&&!Freecam.isActive()?ParkourCamera.yaw(yaw,partial):yaw;
    }
}
