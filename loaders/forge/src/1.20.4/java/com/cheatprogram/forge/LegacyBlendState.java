package com.cheatprogram.forge;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.shaders.BlendMode;
import com.cheatprogram.mixin.LegacyBlendModeAccessor;

/** 1.20.1's glBlendFunc cache does not update its cached alpha factors. */
public final class LegacyBlendState {
    private LegacyBlendState() { }
    public static BlendMode capture(){return LegacyBlendModeAccessor.cheatprogram$getLastApplied();}
    public static void restore(BlendMode mode){LegacyBlendModeAccessor.cheatprogram$setLastApplied(mode);}
    public static void standard(){factors(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);}
    public static void factors(int sourceRgb,int destinationRgb,int sourceAlpha,int destinationAlpha){
        // Change a cached alpha value first, so the second call cannot skip a stale native state.
        RenderSystem.blendFuncSeparate(sourceRgb,destinationRgb,sourceAlpha==GL11.GL_ZERO?GL11.GL_ONE:GL11.GL_ZERO,destinationAlpha);
        RenderSystem.blendFuncSeparate(sourceRgb,destinationRgb,sourceAlpha,destinationAlpha);
    }
}
