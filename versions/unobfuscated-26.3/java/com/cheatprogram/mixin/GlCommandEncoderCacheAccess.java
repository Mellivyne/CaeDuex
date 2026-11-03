package com.cheatprogram.mixin;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="com.mojang.blaze3d.opengl.GlCommandEncoder")
public interface GlCommandEncoderCacheAccess {
    @Accessor("lastPipeline") void cheatprogram$lastPipeline(com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline);
    @Accessor("lastProgram") void cheatprogram$lastProgram(GlProgram program);

    @Mixin(com.mojang.renderpearl.frontend.FrontendGpuDevice.class)
    public interface DeviceBackendAccess {
        @Accessor("backend") com.mojang.blaze3d.systems.GpuDeviceBackend cheatprogram$backend();
    }
    @Mixin(targets="com.mojang.blaze3d.opengl.GlDevice")
    public interface OpenGlBackendAccess {
        @Accessor("directStateAccess") com.mojang.blaze3d.opengl.DirectStateAccess cheatprogram$directStateAccess();
        @Accessor("frameBufferCache") com.mojang.blaze3d.opengl.FrameBufferCache cheatprogram$frameBufferCache();
    }
}
