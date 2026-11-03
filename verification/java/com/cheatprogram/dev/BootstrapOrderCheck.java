package com.cheatprogram.dev;

import com.cheatprogram.render.WorldPanelPreferences;
import com.cheatprogram.render.WorldControlPanel;
import net.minecraft.client.renderer.RenderTargetProbe;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.zip.ZipFile;

/** Executes the shipped target hook against startup sentinels, without starting Minecraft. */
public final class BootstrapOrderCheck {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static ClassNode read(ZipFile jar,String name)throws Exception{
        var node=new ClassNode();new ClassReader(jar.getInputStream(jar.getEntry(name+".class"))).accept(node,0);return node;
    }
    public static void main(String[] args)throws Exception{
        Path dir=Path.of(System.getProperty("bootstrap.test.directory"));Files.createDirectories(dir);
        Path file=dir.resolve("world-panel.json");Files.deleteIfExists(file);
        // No ClientSettings class exists in this process, reproducing the early-loader condition.
        WorldPanelPreferences prefs=WorldPanelPreferences.load();check(prefs.interactionKey==345,"Missing preferences default");
        Files.writeString(file,"{\"acknowledged\":true,\"interactionKey\":340}");
        prefs=WorldPanelPreferences.load();check(prefs.acknowledged&&prefs.interactionKey==340,"Saved preference lost");
        prefs.validateKey(340);check(prefs.interactionKey==345,"GUI-key conflict not resolved");
        prefs.validateKey(345);check(prefs.interactionKey==346,"Fallback still conflicts with GUI key");
        for(int invalid:new int[]{-1,0,31,349,10000}){prefs.interactionKey=invalid;prefs.validateKey(344);check(prefs.interactionKey==345,"Invalid binding retained");}
        Files.writeString(file,"invalid json");check(WorldPanelPreferences.load().interactionKey==345,"Invalid file prevented startup");
        Files.writeString(file," ".repeat(4097));check(WorldPanelPreferences.load().interactionKey==345,"Oversized file prevented startup");
        Files.delete(file);
        for(String argument:args)try(var jar=new ZipFile(argument)){
            var node=read(jar,"com/cheatprogram/mixin/WorldPanelTargetMixin");
            node.access&=~Opcodes.ACC_ABSTRACT; // Instantiate the real private callback; leave its code unchanged.
            var writer=new ClassWriter(0);
            // Bind Fabric's intermediary render-target type to the same isolated sentinel.
            node.accept(new ClassRemapper(writer,new Remapper(){@Override public String map(String name){return name.equals("net/minecraft/class_276")?"com/mojang/blaze3d/pipeline/RenderTarget":name;}}));
            byte[] bytes=writer.toByteArray();
            class HookLoader extends ClassLoader{HookLoader(){super(BootstrapOrderCheck.class.getClassLoader());}Class<?> hook(){return defineClass("com.cheatprogram.mixin.WorldPanelTargetMixin",bytes,0,bytes.length);}}
            Class<?> type=new HookLoader().hook();var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object hook=constructor.newInstance();
            var method=type.getDeclaredMethod("cheatprogram$worldPanel",RenderTarget.class);method.setAccessible(true);
            RenderTarget original=new RenderTarget();RenderTargetProbe.ready=false;RenderTargetProbe.calls=0;
            check(method.invoke(hook,original)==original,"Pre-setup native target was changed");
            check(RenderTargetProbe.calls==0,"Pre-setup world-panel hook was touched");
            RenderTargetProbe.ready=true;WorldControlPanel.target=new RenderTarget();
            check(method.invoke(hook,original)==WorldControlPanel.target,"Ready panel target failed");
            WorldControlPanel.target=null;check(method.invoke(hook,original)==original,"Inactive panel did not preserve native target");
            var panel=read(jar,"com/cheatprogram/render/WorldControlPanel");
            for(var m:panel.methods)if(m.name.equals("<clinit>"))for(var instruction:m.instructions)
                if(instruction instanceof MethodInsnNode call)check(!call.owner.equals("com/cheatprogram/render/WorldPanelPreferences"),"Panel still eagerly loads preferences");
            System.out.println("BOOTSTRAP_ORDER_PASS "+Path.of(argument).getFileName()+" early passthrough, late redirect, lazy preferences");
        }
        System.out.println("PREFERENCES_STARTUP_PASS no ClientSettings, saved/missing/malformed/oversized files, key conflicts and bounds");
    }
}
