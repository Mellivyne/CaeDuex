package com.cheatprogram.mixin;

import com.mojang.blaze3d.shaders.BlendMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** BlendMode caches opacity/factors independently from GlStateManager. */
@Mixin(BlendMode.class)
public interface LegacyBlendModeAccessor {
    @Accessor("lastApplied")
    static BlendMode cheatprogram$getLastApplied(){throw new AssertionError("Mixin accessor was not applied");}
    @Accessor("lastApplied")
    static void cheatprogram$setLastApplied(BlendMode mode){throw new AssertionError("Mixin accessor was not applied");}
}
