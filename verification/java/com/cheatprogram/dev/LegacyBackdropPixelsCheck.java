package com.cheatprogram.dev;

import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Shaders;
import com.cheatprogram.render.RenderStateSnapshot;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import java.nio.ByteBuffer;

public final class LegacyBackdropPixelsCheck {
    private static void pixel(int texture,String label){
        expectedPixel(texture,label,64,140,204);
    }
    private static void expectedPixel(int texture,String label,int expectedR,int expectedG,int expectedB){
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(texture);
        int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
        ByteBuffer p=ByteBuffer.allocateDirect(w*h*4);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,p);
        int at=(h/2*w+w/2)*4,r=p.get(at)&255,g=p.get(at+1)&255,b=p.get(at+2)&255;
        int alpha=p.get(at+3)&255;
        System.out.println(label+" RGB="+r+","+g+","+b+" alpha="+alpha+" GL="+GL11.glGetError());
        if(Math.abs(r-expectedR)>3||Math.abs(g-expectedG)>3||Math.abs(b-expectedB)>3)throw new AssertionError(label+" lost source color");
        if(alpha!=255)throw new AssertionError(label+" overwrote opaque scene alpha: "+alpha);
    }
    private static net.minecraft.client.renderer.ShaderInstance worldShader()throws Exception{
        var pack=new net.minecraft.server.packs.PathPackResources("Isolated vanilla resources",java.nio.file.Path.of("."),true);
        net.minecraft.server.packs.resources.ResourceProvider provider=id->{
            String path="/assets/"+id.getNamespace()+"/"+id.getPath();
            return LegacyBackdropPixelsCheck.class.getResource(path)==null?java.util.Optional.empty():java.util.Optional.of(new net.minecraft.server.packs.resources.Resource(pack,()->LegacyBackdropPixelsCheck.class.getResourceAsStream(path)));
        };
        return new net.minecraft.client.renderer.ShaderInstance(provider,"position_color",com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
    }
    private static void world(net.minecraft.client.renderer.ShaderInstance shader){
        world(shader,64,140,204);
    }
    private static void world(net.minecraft.client.renderer.ShaderInstance shader,int red,int green,int blue){
        Minecraft.main.bindWrite(true);RenderSystem.disableDepthTest();RenderSystem.disableCull();RenderSystem.depthMask(true);
        RenderSystem.setProjectionMatrix(new org.joml.Matrix4f(),com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
        com.cheatprogram.compat.legacy.LegacyRenderSystem.getModelViewStack().identity();
        RenderSystem.setShader(()->shader);RenderSystem.setShaderColor(1,1,1,1);
        var buffer=com.cheatprogram.compat.legacy.LegacyTesselator.getInstance().begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        buffer.addVertex(-1,-1,0).setColor(red,green,blue,255);buffer.addVertex(1,-1,0).setColor(red,green,blue,255);buffer.addVertex(1,1,0).setColor(red,green,blue,255);buffer.addVertex(-1,1,0).setColor(red,green,blue,255);
        com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(buffer.buildOrThrow());
    }
    private static void glass(){
        var shader=Shaders.get(Shaders.GLASS);RenderSystem.setShader(()->shader);
        RenderSystem.setShaderTexture(0,Backdrop.texture());RenderSystem.setShaderTexture(1,Backdrop.sharpTexture());
        shader.safeGetUniform("Size").set(96f,96f);shader.safeGetUniform("Radius").set(10f,10f,10f,10f);shader.safeGetUniform("Glass").set(4f,8f,0f,0f);shader.safeGetUniform("Rim").set(1f,0f,0f,1f);
        RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        var buffer=com.cheatprogram.compat.legacy.LegacyTesselator.getInstance().begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex(-1,-1,0).setUv(0,96).setColor(255,255,255,0);buffer.addVertex(1,-1,0).setUv(96,96).setColor(255,255,255,0);buffer.addVertex(1,1,0).setUv(96,0).setColor(255,255,255,0);buffer.addVertex(-1,1,0).setUv(0,0).setColor(255,255,255,0);
        com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(buffer.buildOrThrow());
    }
    public static void main(String[] args)throws Exception{
        if(!GLFW.glfwInit())throw new AssertionError("GLFW unavailable");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,2);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window=GLFW.glfwCreateWindow(96,96,"Hidden legacy backdrop check",0,0);if(window==0)throw new AssertionError("Hidden context unavailable");
        try{
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();RenderSystem.beginInitialization();RenderSystem.initGameThread(false);
            Minecraft.main=new TextureTarget(96,96,false,false);Minecraft.main.setClearColor(.25f,.55f,.8f,1);Minecraft.main.clear(false);Minecraft.main.bindWrite(true);
            RenderSystem.disableDepthTest();RenderSystem.disableCull();RenderSystem.depthMask(false);
            pixel(Backdrop.capture(0),"COPY");
            pixel(Backdrop.capture(.5f),"BLURRED");
            Backdrop.drawFullscreen(Shaders.BACKDROP,Backdrop.texture(),0,0,0,1);
            pixel(Minecraft.main.getColorTextureId(),"COMPOSITED");
            var vanilla=worldShader();
            for(int frame=0;frame<3;frame++){
                world(vanilla);
                var state=new RenderStateSnapshot();
                Backdrop.capture(.5f);Backdrop.drawFullscreen(Shaders.BACKDROP,Backdrop.texture(),0,0,0,1);
                glass();
                state.restore();pixel(Minecraft.main.getColorTextureId(),"STATE_RESTORED_"+frame);
                Minecraft.main.setClearColor(0,0,0,1);Minecraft.main.clear(false);world(vanilla);pixel(Minecraft.main.getColorTextureId(),"NEXT_WORLD_FRAME_"+frame);
            }
            // A blended caller is restored even when native shader draws change its state.
            RenderSystem.enableBlend();RenderSystem.blendFuncSeparate(GL11.GL_ZERO,GL11.GL_ZERO,GL11.GL_ONE,GL11.GL_ZERO);
            var blendedCaller=new RenderStateSnapshot();
            world(vanilla);blendedCaller.restore();
            Minecraft.main.clear(false);world(vanilla);pixel(Minecraft.main.getColorTextureId(),"AFTER_RESTORED_BLEND");
            RenderSystem.blendFunc(GL11.GL_ONE,GL11.GL_ZERO);
            RenderSystem.blendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
            com.cheatprogram.forge.LegacyBlendState.standard();
            if(GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA)!=GL11.GL_ONE||GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA)!=GL11.GL_ONE_MINUS_SRC_ALPHA)throw new AssertionError("Legacy alpha cache remained stale");
            Backdrop.capture(0);RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            Backdrop.drawFullscreen(Shaders.BACKDROP,Backdrop.texture(),.5f,0,0,.25f);
            expectedPixel(Minecraft.main.getColorTextureId(),"BACKDROP_PARTIAL_OPACITY",56,123,179);
            // Mirror vanilla's vignette: a black source with ZERO / ONE_MINUS_SRC_COLOR
            // must preserve the scene. A stale BlendMode cache overrides those factors.
            new com.mojang.blaze3d.shaders.BlendMode().apply();
            new com.mojang.blaze3d.shaders.BlendMode(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,org.lwjgl.opengl.GL14.GL_FUNC_ADD).apply();
            Minecraft.main.clear(false);world(vanilla);var beforePanel=new RenderStateSnapshot();
            Backdrop.capture(.5f);Backdrop.drawFullscreen(Shaders.BACKDROP,Backdrop.texture(),0,0,0,1);glass();beforePanel.restore();
            RenderSystem.enableBlend();RenderSystem.blendFuncSeparate(GL11.GL_ZERO,GL11.GL_ONE_MINUS_SRC_COLOR,GL11.GL_ZERO,GL11.GL_ONE);
            world(vanilla,0,0,0);
            pixel(Minecraft.main.getColorTextureId(),"VANILLA_VIGNETTE_AFTER_PANEL");
            System.out.println("LEGACY_BACKDROP_PIXELS_PASS native shader compilation, framebuffer copy, Kawase passes and compositing");
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
