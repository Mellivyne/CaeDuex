package com.cheatprogram.mixin;

import com.cheatprogram.module.legit.TimeChanger;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Time adjustment wraps only the overworld clock, preserving its native rate. */
@Mixin(ClientClockManager.class)
public abstract class ClientLevelDataMixin {
    @Unique private final java.util.Map<ClientClockManager.ClientClockInstance,ClientClockManager.ClientClockInstance> cheatprogram$clocks=new java.util.IdentityHashMap<>();
    @ModifyReturnValue(method="getInstance(Lnet/minecraft/core/Holder;)Lnet/minecraft/client/ClientClockManager$ClientClockInstance;",at=@At("RETURN"))
    private ClientClockManager.ClientClockInstance cheatprogram$dayTime(ClientClockManager.ClientClockInstance original,Holder<WorldClock> clock){
        if(!clock.is(WorldClocks.OVERWORLD))return original;
        return cheatprogram$clocks.computeIfAbsent(original,AdjustedClock::new);
    }
    public static final class AdjustedClock extends ClientClockManager.ClientClockInstance {
        private final ClientClockManager.ClientClockInstance delegate;
        public AdjustedClock(ClientClockManager.ClientClockInstance delegate){this.delegate=delegate;}
        @Override public long totalTicks(){return TimeChanger.apply(delegate.totalTicks());}
        @Override public float partialTick(){return delegate.partialTick();}
        @Override public float rate(){return delegate.rate();}
        @Override public boolean isPaused(){return delegate.isPaused();}
    }
}
