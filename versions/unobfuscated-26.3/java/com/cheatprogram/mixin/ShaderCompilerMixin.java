package com.cheatprogram.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GlslCompiler.class)
public abstract class ShaderCompilerMixin {
    // Avoid overlapping native shaderc calls. Leave GPU pipeline submission on its native path.
    @WrapMethod(method = "compileToSpv")
    private SpvModule cheatprogram$compileSerially(String name, String code, ShaderType type,
            ShaderDefines defines, ShaderSource source, Operation<SpvModule> original) {
        synchronized (GlslCompiler.class) {
            return original.call(name, code, type, defines, source);
        }
    }
}
