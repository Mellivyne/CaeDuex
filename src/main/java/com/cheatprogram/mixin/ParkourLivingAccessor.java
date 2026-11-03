package com.cheatprogram.mixin;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(LivingEntity.class)
public interface ParkourLivingAccessor {
    @Accessor("noJumpDelay") int cheatprogram$jumpDelay();
    @Invoker("getJumpPower") float cheatprogram$jumpPower();
}
