"""Check the production Forge event router without opening Minecraft or accessing user settings."""
from pathlib import Path
import os
import subprocess

root=Path(__file__).resolve().parents[1];out=root/'build/forge-event-checks';out.mkdir(parents=True,exist_ok=True)
java=Path(os.environ.get('JAVA_HOME','D:/Java/jdk-27'))/'bin'
stubs={
'com/cheatprogram/forge/ForgePanelKeyState.java':'package com.cheatprogram.forge;public final class ForgePanelKeyState {public static boolean physical,physicalDown,physicalFocused=true;public record State(boolean focused,boolean down,boolean physical){}public static State read(long window,int key,boolean focus){return physical?new State(physicalFocused,physicalFocused&&physicalDown,true):new State(focus,org.lwjgl.glfw.GLFW.DOWN.contains(key),false);}}',
'com/cheatprogram/CheatProgram.java':'package com.cheatprogram; public final class CheatProgram {public static boolean ready; public static boolean initialized(){return ready;} public static final Log LOGGER=new Log(); public static class Log {public void info(String s,Object... args){} public void warn(String s,Throwable t){}}}',
'com/cheatprogram/core/ClientSettings.java':'package com.cheatprogram.core; public final class ClientSettings {public static ClientSettings INSTANCE; public int guiKey=344;}',
'com/cheatprogram/core/ModuleManager.java':'package com.cheatprogram.core; public final class ModuleManager {public static int ticks,posts,inputs,lastKey,lastAction; public static void onTick(){ticks++;} public static void onPostTick(){posts++;} public static void onInput(int k,int a){inputs++;lastKey=k;lastAction=a;if(a==1&&k==ClientSettings.INSTANCE.guiKey)net.minecraft.client.Minecraft.getInstance().screen=new com.cheatprogram.gui.GuiBase();}}',
'com/cheatprogram/gui/GuiBase.java':'package com.cheatprogram.gui; public class GuiBase {}',
'com/cheatprogram/gui/AppCastInputScreen.java':'package com.cheatprogram.gui; public final class AppCastInputScreen {}',
'com/cheatprogram/gui/WorldPanelInputScreen.java':'package com.cheatprogram.gui; public final class WorldPanelInputScreen {}',
'com/cheatprogram/gui/PanelShortcut.java':'package com.cheatprogram.gui; public final class PanelShortcut {public static int closes;public static boolean handle(int key,int action){var mc=net.minecraft.client.Minecraft.getInstance();if(action==1&&mc.screen instanceof GuiBase){closes++;mc.screen=null;return true;}return false;}}',
'com/cheatprogram/gui/BuildingWorldPreview.java':'package com.cheatprogram.gui; public final class BuildingWorldPreview {public static boolean active;public static boolean key(int key,int action){return active&&key==com.cheatprogram.core.ClientSettings.INSTANCE.guiKey;}}',
'com/cheatprogram/gui/ClickGuiScreen.java':'package com.cheatprogram.gui; public final class ClickGuiScreen {private static final com.cheatprogram.util.KeyBindingCapture CAPTURE=new com.cheatprogram.util.KeyBindingCapture();public static boolean waiting;public static int captures,assigned;public static boolean captureBindingInput(int key,int action){return CAPTURE.key(key,action,waiting,code->{waiting=false;captures++;assigned=code;});}}',
'com/cheatprogram/parkour/ParkourWaypointEditor.java':'package com.cheatprogram.parkour; public final class ParkourWaypointEditor {public static boolean capturing;public static boolean key(int key,int action){return capturing;}}',
'com/cheatprogram/render/WorldMusicController.java':'package com.cheatprogram.render; public final class WorldMusicController {public static boolean key(int key,int action){return false;}}',
'com/cheatprogram/render/WorldControlPanel.java':'package com.cheatprogram.render; public final class WorldControlPanel {public static boolean active;public static int presses;public static boolean key(int key,int action){if(!active)return false;if(action==1)presses++;return true;}}',
'com/cheatprogram/module/visual/AppCast.java':'package com.cheatprogram.module.visual; public final class AppCast {public static boolean active;public static int presses;public static boolean key(int key,int action){if(!active)return false;if(action==1)presses++;return true;}}',
'com/cheatprogram/dev/DevTools.java':'package com.cheatprogram.dev; public final class DevTools {public static void tick(){}}',
'com/cheatprogram/hud/HudRenderer.java':'package com.cheatprogram.hud; public final class HudRenderer {public static int calls;public static float partial;public static void render(Object g,float p){calls++;partial=p;}}',
'com/cheatprogram/util/ImeSupport.java':'package com.cheatprogram.util; public final class ImeSupport {public static boolean fail;public static void tick(){if(fail)throw new IllegalStateException("IME sentinel");}}',
'com/cheatprogram/util/KeyNames.java':'package com.cheatprogram.util; public final class KeyNames {public static final int MOUSE_OFFSET=1000;}',
'net/minecraft/client/Minecraft.java':'package net.minecraft.client; public final class Minecraft {private static final Minecraft INSTANCE=new Minecraft();public Object player,screen,overlay;public boolean active=true;public static class Window {public long getWindow(){return 1;}}public boolean isWindowActive(){return active;}public Window getWindow(){return new Window();}public static Minecraft getInstance(){return INSTANCE;}public Object getOverlay(){return overlay;}}',
'net/minecraftforge/client/event/InputEvent.java':'package net.minecraftforge.client.event; public class InputEvent {public static class Key {private int key,action;public Key(int k,int a){key=k;action=a;}public int getKey(){return key;}public int getAction(){return action;}}public static class MouseButton {public static class Post {private int button,action;public Post(int b,int a){button=b;action=a;}public int getButton(){return button;}public int getAction(){return action;}}}}',
'net/minecraftforge/client/event/RenderGuiEvent.java':'package net.minecraftforge.client.event;public class RenderGuiEvent {public static class Post {private float partial;public Post(float p){partial=p;}public Object getGuiGraphics(){return this;}public float getPartialTick(){return partial;}}}',
'net/minecraftforge/common/MinecraftForge.java':'package net.minecraftforge.common;public final class MinecraftForge {public static final Bus EVENT_BUS=new Bus();public static class Bus {public int count;public Object listener;public void register(Object value){count++;listener=value;}}}',
'net/minecraftforge/event/TickEvent.java':'package net.minecraftforge.event;public class TickEvent {public enum Phase {START,END}public static class RenderTickEvent {public Phase phase;public RenderTickEvent(Phase p){phase=p;}}public static class ClientTickEvent {public Phase phase;public ClientTickEvent(Phase p){phase=p;}}}',
'net/minecraftforge/eventbus/api/SubscribeEvent.java':'package net.minecraftforge.eventbus.api; public @interface SubscribeEvent {}',
'org/lwjgl/glfw/GLFW.java':'package org.lwjgl.glfw;public final class GLFW {public static final int GLFW_PRESS=1,GLFW_RELEASE=0,GLFW_REPEAT=2,GLFW_KEY_SPACE=32,GLFW_KEY_LAST=348;public static final java.util.Set<Integer> DOWN=new java.util.HashSet<>();public static int glfwGetKey(long window,int key){return DOWN.contains(key)?1:0;}}',
}
sources=[]
for name,text in stubs.items():
    path=out/'stubs'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');sources.append(path)
sources += [root/'src/main/java/com/cheatprogram/util/KeyBindingCapture.java',
            root/'verification/java/com/cheatprogram/dev/ForgeNativeEventsCheck.java']
for minecraft in ('1.20.1','1.20.4'):
    classes=out/minecraft
    classes.mkdir(exist_ok=True)
    router=root/f'loaders/forge/src/{minecraft}/java/com/cheatprogram/forge/ForgeClientEvents.java'
    subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',str(classes),*map(str,[*sources,router])],check=True)
    print(f'Forge {minecraft} production event routing',flush=True)
    subprocess.run([str(java/'java.exe'),'-cp',str(classes),'com.cheatprogram.dev.ForgeNativeEventsCheck'],check=True)
