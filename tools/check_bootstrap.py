"""Exercise shipped startup render hooks in an isolated JVM, with no Minecraft session or user config."""
from pathlib import Path
import os
import subprocess
import sys

root=Path(__file__).resolve().parents[1]
out=root/'build/bootstrap-checks';out.mkdir(parents=True,exist_ok=True)
java=Path(os.environ.get('JAVA_HOME','D:/Java/jdk-27'))/'bin'
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
dependencies=[next((cache/family).rglob(pattern)) for family,pattern in [('com.google.code.gson/gson','gson-*.jar'),('org.ow2.asm/asm','asm-*.jar'),('org.ow2.asm/asm-tree','asm-tree-*.jar'),('org.ow2.asm/asm-commons','asm-commons-*.jar')]]
sources=[]
stubs={
'net/minecraft/client/renderer/RenderTargetProbe.java':'package net.minecraft.client.renderer; public final class RenderTargetProbe { public static boolean ready; public static int calls; }',
'com/cheatprogram/CheatProgram.java':'package com.cheatprogram; public final class CheatProgram { public static boolean initialized(){return net.minecraft.client.renderer.RenderTargetProbe.ready;} }',
'com/mojang/blaze3d/pipeline/RenderTarget.java':'package com.mojang.blaze3d.pipeline; public class RenderTarget { }',
'com/cheatprogram/render/WorldControlPanel.java':'package com.cheatprogram.render; public final class WorldControlPanel { public static com.mojang.blaze3d.pipeline.RenderTarget target; public static com.mojang.blaze3d.pipeline.RenderTarget activeTarget(){net.minecraft.client.renderer.RenderTargetProbe.calls++; if(!net.minecraft.client.renderer.RenderTargetProbe.ready)throw new AssertionError("Panel accessed before setup"); return target;} }',
'com/cheatprogram/core/Config.java':'package com.cheatprogram.core; public final class Config { public static java.nio.file.Path directory(){return java.nio.file.Path.of(System.getProperty("bootstrap.test.directory"));} public static Object persistenceLock(){return Config.class;} public static boolean persistenceAllowed(){return false;} }',
'org/lwjgl/glfw/GLFW.java':'package org.lwjgl.glfw; public final class GLFW {public static final int GLFW_KEY_RIGHT_CONTROL=345,GLFW_KEY_RIGHT_ALT=346;}',
}
for name,text in stubs.items():
    path=out/'stubs'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');sources.append(path)
sources+=[root/'src/main/java/com/cheatprogram/render/WorldPanelPreferences.java',root/'verification/java/com/cheatprogram/dev/BootstrapOrderCheck.java']
classpath=os.pathsep.join(map(str,dependencies))
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',classpath,'-d',str(out),*map(str,sources)],check=True)
subprocess.run([str(java/'java.exe'),f'-Dbootstrap.test.directory={out / "isolated-preferences"}','-cp',str(out)+os.pathsep+classpath,'com.cheatprogram.dev.BootstrapOrderCheck',*sys.argv[1:]],check=True)
