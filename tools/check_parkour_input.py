"""Run all production parkour input adapters without starting Minecraft."""
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(os.environ.get('JAVA_HOME','D:/Java/jdk-27'))/'bin'
STUBS={
'net/minecraft/world/entity/player/Input.java':'''package net.minecraft.world.entity.player;
public record Input(boolean forward,boolean backward,boolean left,boolean right,boolean jump,boolean shift,boolean sprint) {}''',
'net/minecraft/client/player/ClientInput.java':'''package net.minecraft.client.player;
public class ClientInput { public float forwardImpulse,leftImpulse; public net.minecraft.world.entity.player.Input keyPresses; }''',
'net/minecraft/client/player/KeyboardInput.java':'''package net.minecraft.client.player; public class KeyboardInput extends ClientInput {}''',
'net/minecraft/client/player/LocalPlayer.java':'''package net.minecraft.client.player;
public class LocalPlayer { public final ClientInput input=new KeyboardInput(); public boolean horizontalCollision,minorHorizontalCollision; private boolean sprint; private float yaw;
public void setYRot(float value){yaw=value;} public float getYRot(){return yaw;} public void setSprinting(boolean value){sprint=value;} public boolean isSprinting(){return sprint;} }''',
'net/minecraft/client/Minecraft.java':'''package net.minecraft.client;
public class Minecraft { private static final Minecraft INSTANCE=new Minecraft(); public final net.minecraft.client.player.LocalPlayer player=new net.minecraft.client.player.LocalPlayer(); public static Minecraft getInstance(){return INSTANCE;} }''',
'com/cheatprogram/compat/InputBridge.java':'''package com.cheatprogram.compat;
public class InputBridge { public static void set(net.minecraft.client.player.KeyboardInput input,int f,int s){input.forwardImpulse=f;input.leftImpulse=s;} }''',
'com/cheatprogram/compat/legacy/LegacyInput.java':'''package com.cheatprogram.compat.legacy;
public record LegacyInput(boolean f,boolean b,boolean l,boolean r,boolean j,boolean shift,boolean sprint) {
public void apply(net.minecraft.client.player.KeyboardInput input){input.keyPresses=new net.minecraft.world.entity.player.Input(f,b,l,r,j,shift,sprint);} }'''
}
for name,adapter in {
    'base':'src/main/java/com/cheatprogram/parkour/ParkourInput.java',
    'legacy':'versions/legacy-1.20.4/java/com/cheatprogram/parkour/ParkourInput.java',
    'modern':'versions/modern-1.21.10/java/com/cheatprogram/parkour/ParkourInput.java',
}.items():
    out=ROOT/'build/parkour-native-input'/name
    sources=[]
    for path,source in STUBS.items():
        p=out/'stubs'/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source,encoding='utf-8');sources.append(p)
    sources += [ROOT/adapter,ROOT/'src/main/java/com/cheatprogram/parkour/ParkourPhysics.java',ROOT/'verification/java/com/cheatprogram/dev/ParkourNativeInputCheck.java']
    subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-d',str(out),*[str(p) for p in sources]],check=True)
    subprocess.run([str(JAVA/'java.exe'),'-cp',str(out),'com.cheatprogram.dev.ParkourNativeInputCheck'],check=True)
    print('INPUT_ADAPTER_PASS',name,flush=True)
