package com.cheatprogram.render;

import java.lang.reflect.Proxy;
import java.util.Random;

/** Uses the actual 26.3 render-pass validator with a recording backend, without opening a window. */
public final class FramebufferClipCheck {
    private static int submitted;
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Object validator(int width, int height) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        Class<?> type, areaType, backendType;
        try {
            type = Class.forName("com.mojang.renderpearl.frontend.FrontendRenderPass");
            areaType = Class.forName("com.mojang.renderpearl.api.commands.RenderPass$RenderArea");
            backendType = Class.forName("com.mojang.renderpearl.backend.api.RenderPassBackend");
        } catch (ClassNotFoundException older) {
            type = Class.forName("com.mojang.blaze3d.systems.RenderPass");
            areaType = Class.forName("com.mojang.blaze3d.systems.RenderPass$RenderArea");
            backendType = Class.forName("com.mojang.blaze3d.systems.RenderPassBackend");
        }
        Object pass = unsafe.allocateInstance(type);
        var area = type.getDeclaredField("renderArea"); area.setAccessible(true);
        area.set(pass, areaType.getConstructor(int.class,int.class,int.class,int.class).newInstance(0,0,width,height));
        Object backend = Proxy.newProxyInstance(backendType.getClassLoader(),new Class<?>[]{backendType},(proxy,method,args)-> {
            if (method.getName().equals("enableScissor")) submitted++;
            return null;
        });
        var fieldBackend = type.getDeclaredField("backend"); fieldBackend.setAccessible(true); fieldBackend.set(pass,backend);
        return pass;
    }
    private static void submit(Object pass, FramebufferClip clip) throws Exception {
        if (!clip.empty()) pass.getClass().getMethod("enableScissor",int.class,int.class,int.class,int.class)
                .invoke(pass,clip.x(),clip.y(),clip.width(),clip.height());
    }
    public static void main(String[] args) throws Exception {
        Object pass = validator(853,479);
        int rejected = 0;
        for (int[] bad : new int[][]{{0,0,10,0},{0,0,0,10},{-1,0,10,10},{850,470,10,10}}) {
            try { pass.getClass().getMethod("enableScissor",int.class,int.class,int.class,int.class)
                    .invoke(pass,bad[0],bad[1],bad[2],bad[3]); }
            catch (java.lang.reflect.InvocationTargetException failure) {
                require(failure.getCause() instanceof IllegalArgumentException,"Unexpected native rejection");
                rejected++;
            }
        }
        require(rejected==4,"Native zero-size/out-of-bounds regression must be reproduced");
        require(FramebufferClip.topLeft(10,10,0,4,853,479,null).empty(),"Fractional/zero width must stay empty");
        require(FramebufferClip.topLeft(Float.NaN,0,10,10,853,479,null).empty(),"NaN is invisible");
        require(FramebufferClip.topLeft(0,0,Float.POSITIVE_INFINITY,10,853,479,null).empty(),"Infinity is invisible");
        require(FramebufferClip.intersect(Integer.MIN_VALUE,0,Integer.MAX_VALUE,10,853,479).empty(),"Overflow must not create visible pixels");
        submit(pass,FramebufferClip.intersect(-4,-8,20,20,853,479));
        var parent=FramebufferClip.topLeft(20.5f,40.2f,200,100,853,479,null);
        require(FramebufferClip.topLeft(30,400,100,20,853,479,parent).empty(),"Off-list row must stay clipped");
        Random random=new Random(20261104);
        int empty=0;
        for(int i=0;i<50000;i++){
            float scale=new float[]{.2f,.55f,1f,1.25f,2f,3f}[i%6];
            float x=(random.nextInt(3000)-1500)*scale,y=(random.nextInt(2500)-1250)*scale;
            float width=(random.nextInt(1000)-50)*scale,height=(random.nextInt(400)-20)*scale;
            var clip=FramebufferClip.topLeft(x,y,width,height,853,479,i%2==0?parent:null);
            require(clip.x()>=0&&clip.y()>=0&&clip.x()<=853&&clip.y()<=479,"Canvas clip escaped the framebuffer");
            var nativeClip=FramebufferClip.intersect(clip.x(),479-clip.y()-clip.height(),clip.width(),clip.height(),853,479);
            if(nativeClip.empty())empty++;else submit(pass,nativeClip);
            var direct=FramebufferClip.intersect(random.nextInt(),random.nextInt(),random.nextInt(),random.nextInt(),853,479);
            submit(pass,direct);
        }
        require(submitted>100&&empty>100,"Visible and invisible cases must both run");
        System.out.println("PASS: actual native validator rejects old zero/out-of-bounds clips; 50000 varied nested/scaled cases remain valid, invisible rows skipped");
    }
}
