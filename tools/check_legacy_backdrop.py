"""Verify native Forge 1.20.1 backdrop pixels in a hidden GL window; never starts Minecraft."""
from pathlib import Path
import argparse
import json
import os
import subprocess

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--minecraft',choices=['1.20.1','1.20.4'],default='1.20.1')
parser.add_argument('--runtime-libraries',type=Path,required=True,help='Local JSON array of resolved 1.20.1 runtime JAR paths; never copied into the project')
args=parser.parse_args()
if not os.environ.get('JAVA_HOME'):parser.error('Set JAVA_HOME to a Java 21 JDK')
java=Path(os.environ['JAVA_HOME'])/'bin'
mc=args.minecraft
target=next(t for t in json.loads((root/'compatibility/forge.json').read_text('utf-8'))['targets'] if t['minecraft']==mc)
out=root/f'build/legacy-backdrop-checks/{mc}';out.mkdir(parents=True,exist_ok=True)
coordinate=f"{mc}-{target['forge']}_mapped_official_{mc}"
mapped=Path.home()/f'.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/{coordinate}/forge-{coordinate}.jar'
extra=Path.home()/f'.gradle/caches/forge_gradle/minecraft_repo/versions/{mc}/client-extra.jar'
libraries=json.loads(args.runtime_libraries.read_text(encoding='utf-8'))
assert isinstance(libraries,list) and all(isinstance(p,str) and Path(p).is_file() for p in libraries)
libraries=[p for p in libraries if not Path(p).name.startswith(('forge-','fmlloader-','fmlcore-','javafmllanguage-','lowcodelanguage-','mclanguage-'))]
assert mapped.is_file() and extra.is_file(),'Build Forge 1.20.1 first to resolve its native classes and assets'
resource_root=json.dumps((root/'src/main/resources').as_posix())
stubs={
 'com/cheatprogram/mixin/LegacyBlendModeAccessor.java':'package com.cheatprogram.mixin;public final class LegacyBlendModeAccessor {private static final java.lang.reflect.Field FIELD;static {try{FIELD=com.mojang.blaze3d.shaders.BlendMode.class.getDeclaredField("lastApplied");FIELD.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}public static com.mojang.blaze3d.shaders.BlendMode cheatprogram$getLastApplied(){try{return (com.mojang.blaze3d.shaders.BlendMode)FIELD.get(null);}catch(Exception e){throw new IllegalStateException(e);}}public static void cheatprogram$setLastApplied(com.mojang.blaze3d.shaders.BlendMode value){try{FIELD.set(null,value);}catch(Exception e){throw new IllegalStateException(e);}}}',
 'net/minecraft/client/Minecraft.java':'package net.minecraft.client;public final class Minecraft {public static final boolean ON_OSX=false;public static com.mojang.blaze3d.pipeline.TextureTarget main;private static final Minecraft INSTANCE=new Minecraft();public static Minecraft getInstance(){return INSTANCE;}public com.mojang.blaze3d.pipeline.RenderTarget getMainRenderTarget(){return main;}public com.mojang.blaze3d.platform.Window getWindow(){return new com.mojang.blaze3d.platform.Window();}}',
 'com/mojang/blaze3d/platform/Window.java':'package com.mojang.blaze3d.platform;public final class Window {public int getWidth(){return 96;}public int getHeight(){return 96;}}',
 'com/cheatprogram/CheatProgram.java':'package com.cheatprogram;public final class CheatProgram {public static final String MOD_ID="cheatprogram";public static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("Isolated shader check");}',
 'com/cheatprogram/module/visual/WearableWeapons.java':'package com.cheatprogram.module.visual;public final class WearableWeapons{}',
 'com/cheatprogram/compat/legacy/LegacyCoreShaders.java':'package com.cheatprogram.compat.legacy;public final class LegacyCoreShaders {public static final java.util.function.Supplier<net.minecraft.client.renderer.ShaderInstance> RENDERTYPE_ENTITY_SOLID=()->null;}',
 'com/cheatprogram/platform/ModPlatform.java':'package com.cheatprogram.platform;public final class ModPlatform {private static final ModPlatform INSTANCE=new ModPlatform();public static ModPlatform getInstance(){return INSTANCE;}public java.util.Optional<ModPlatform> getModContainer(String name){return java.util.Optional.of(INSTANCE);}public java.util.List<java.nio.file.Path> getRootPaths(){return java.util.List.of(java.nio.file.Path.of('+resource_root+'));}}',
}
sources=[]
for name,text in stubs.items():
    p=out/'stubs'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');sources.append(p)
generated=root/f'build/forge/{mc}/generated/main'
sources += [generated/f'java/com/cheatprogram/render/{name}.java' for name in ['Shaders','Backdrop','RenderStateSnapshot']]
sources += [generated/f'java/com/cheatprogram/compat/legacy/{name}.java' for name in ['LegacyRenderSystem','LegacyProjectionType','LegacyFogParameters','LegacyBufferBuilder','LegacyByteBufferBuilder','LegacyVertexConsumer','LegacyTesselator']]
sources += [root/f'loaders/forge/src/{mc}/java/com/cheatprogram/forge/LegacyBlendState.java',root/'verification/java/com/cheatprogram/dev/LegacyBackdropPixelsCheck.java']
classpath=os.pathsep.join([str(mapped),str(extra),*libraries])
result=subprocess.run([str(java/'javac.exe'),'-proc:none','-encoding','UTF-8','-cp',classpath,'-d',str(out),*map(str,sources)])
if result.returncode:raise SystemExit(result.returncode)
classpath=os.pathsep.join([str(out),str(generated/'resources'),str(root/'src/main/resources'),classpath])
result=subprocess.run([str(java/'java.exe'),'-cp',classpath,'com.cheatprogram.dev.LegacyBackdropPixelsCheck'],cwd=out)
raise SystemExit(result.returncode)
