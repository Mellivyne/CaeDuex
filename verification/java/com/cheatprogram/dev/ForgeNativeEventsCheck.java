package com.cheatprogram.dev;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.forge.ForgeClientEvents;
import com.cheatprogram.forge.ForgePanelKeyState;
import com.cheatprogram.gui.AppCastInputScreen;
import com.cheatprogram.gui.BuildingWorldPreview;
import com.cheatprogram.gui.ClickGuiScreen;
import com.cheatprogram.gui.GuiBase;
import com.cheatprogram.gui.PanelShortcut;
import com.cheatprogram.gui.WorldPanelInputScreen;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.module.visual.AppCast;
import com.cheatprogram.parkour.ParkourWaypointEditor;
import com.cheatprogram.render.WorldControlPanel;
import com.cheatprogram.util.ImeSupport;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/** Run production event routing against deterministic event/game sentinels. */
public final class ForgeNativeEventsCheck {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        ClientSettings.INSTANCE=null;ForgeClientEvents.register();check(MinecraftForge.EVENT_BUS.count==0,"Registered before setup");
        CheatProgram.ready=true;ClientSettings.INSTANCE=new ClientSettings();
        ForgeClientEvents.register();ForgeClientEvents.register();check(MinecraftForge.EVENT_BUS.count==1,"Event bus registered twice");
        ForgeClientEvents events=(ForgeClientEvents)MinecraftForge.EVENT_BUS.listener;
        Minecraft mc=Minecraft.getInstance();mc.player=new Object();
        CheatProgram.ready=false;ClientSettings.INSTANCE=null;
        events.key(new InputEvent.Key(344,1));events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));events.hud(new RenderGuiEvent.Post(.5f));
        check(ModuleManager.inputs==0&&ModuleManager.ticks==0&&HudRenderer.calls==0,"Pre-setup event touched features");
        CheatProgram.ready=true;ClientSettings.INSTANCE=new ClientSettings();
        events.key(new InputEvent.Key(344,1));check(ModuleManager.inputs==1&&mc.screen!=null,"Right Shift failed to request panel");
        events.key(new InputEvent.Key(344,1));check(ModuleManager.inputs==1,"Open screen received duplicate global shortcut");
        mc.screen=null;events.key(new InputEvent.Key(70,2));check(ModuleManager.inputs==1,"Repeat triggered global shortcut");
        events.key(new InputEvent.Key(70,1));events.key(new InputEvent.Key(70,0));check(ModuleManager.inputs==3&&ModuleManager.lastAction==0,"Press/release routing lost");
        events.mouse(new InputEvent.MouseButton.Post(0,1));check(ModuleManager.inputs==3,"Attack click was routed as a module bind");
        events.mouse(new InputEvent.MouseButton.Post(2,1));check(ModuleManager.inputs==4&&ModuleManager.lastKey==1002,"Extra mouse binding lost");
        mc.overlay=new Object();events.key(new InputEvent.Key(71,1));check(ModuleManager.inputs==4,"Overlay did not retain input");mc.overlay=null;
        mc.player=null;events.key(new InputEvent.Key(71,1));check(ModuleManager.inputs==4,"Menu input toggled world feature");mc.player=new Object();
        ClientSettings.INSTANCE.guiKey=65;events.key(new InputEvent.Key(65,1));check(ModuleManager.inputs==5&&mc.screen!=null,"Custom GUI binding ignored");mc.screen=null;
        events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        check(ModuleManager.ticks==1&&ModuleManager.posts==1,"Tick phases were lost or doubled");
        ImeSupport.fail=true;events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));check(ModuleManager.ticks==2,"IME failure disabled feature tick");
        events.hud(new RenderGuiEvent.Post(.75f));check(HudRenderer.calls==1&&HudRenderer.partial==.75f,"Forge GUI did not reach HUD");
        // Reproduce a swallowed raw/Forge key callback: only GLFW's key state changes.
        org.lwjgl.glfw.GLFW.DOWN.add(65);events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        check(ModuleManager.inputs==6&&mc.screen!=null,"Window polling did not open panel without a raw event");
        events.key(new InputEvent.Key(65,1));check(ModuleManager.inputs==6,"Polling plus native event opened twice");
        mc.screen=null;events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==6,"Held key reopened closed screen");
        org.lwjgl.glfw.GLFW.DOWN.remove(65);events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        org.lwjgl.glfw.GLFW.DOWN.add(65);events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==7,"Polling release did not rearm key");
        mc.screen=null;mc.active=false;org.lwjgl.glfw.GLFW.DOWN.remove(65);events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        mc.active=true;org.lwjgl.glfw.GLFW.DOWN.add(65);events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==7,"Held key activated on focus return");
        ClientSettings.INSTANCE.guiKey=66;org.lwjgl.glfw.GLFW.DOWN.add(66);events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==7,"Held key activated on rebinding");
        org.lwjgl.glfw.GLFW.DOWN.remove(66);events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        org.lwjgl.glfw.GLFW.DOWN.add(66);events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==8,"New polled binding failed");
        ClientSettings.INSTANCE.guiKey=-1;events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==8,"Unbound key activated panel");
        // Reproduce the live failure: Windows sees Right Shift, while GLFW and the raw event do not.
        mc.screen=null;mc.active=false;org.lwjgl.glfw.GLFW.DOWN.clear();ClientSettings.INSTANCE.guiKey=344;
        ForgePanelKeyState.physical=true;ForgePanelKeyState.physicalDown=false;
        events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        ForgePanelKeyState.physicalDown=true;
        events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.END));check(ModuleManager.inputs==8,"Render END polled panel input");
        events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        check(ModuleManager.inputs==9&&mc.screen!=null,"Physical-only Right Shift did not open panel at frame start");
        events.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));events.key(new InputEvent.Key(344,1));
        check(ModuleManager.inputs==9,"Frame/tick/native routes duplicated physical press");
        mc.screen=null;events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==9,"Held physical key reopened panel");
        ForgePanelKeyState.physicalFocused=false;events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==9,"Physical key activated outside Minecraft");
        ForgePanelKeyState.physicalFocused=true;events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==9,"Physical held key activated on focus return");
        ForgePanelKeyState.physicalDown=false;events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        mc.overlay=new Object();ForgePanelKeyState.physicalDown=true;events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==9,"Physical key bypassed overlay ownership");
        mc.overlay=null;events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==9,"Physical key reopened after overlay closed while held");
        ForgePanelKeyState.physicalDown=false;events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        ForgePanelKeyState.physicalDown=true;events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));check(ModuleManager.inputs==10&&mc.screen!=null,"Released physical key did not rearm panel");
        checkPageShortcutRoutes(events,mc);
        System.out.println("FORGE_NATIVE_EVENTS_PASS setup gating, one registration, Right Shift/custom panel keys, screen/overlay ownership, repeat, press/release, mouse binds, tick phases, IME isolation and HUD forwarding");
        System.out.println("FORGE_PANEL_FALLBACK_PASS swallowed callback, GLFW-only press/release, native/poll deduplication, held-key close, focus return, rebinding and disabled binding");
        System.out.println("FORGE_PHYSICAL_KEY_PASS physical-only frame input, native focus ownership, frame/tick/event deduplication, held key, focus return, overlay and release rearm");
    }

    private static void release(ForgeClientEvents events){
        ForgePanelKeyState.physicalDown=false;
        events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        ForgeClientEvents.panelKey(ClientSettings.INSTANCE.guiKey,0);
    }

    /** The transformed raw callback and the Forge event both call the production edge detector. */
    private static void checkPageShortcutRoutes(ForgeClientEvents events,Minecraft mc){
        release(events);
        mc.screen=new GuiBase();
        int closes=PanelShortcut.closes,inputs=ModuleManager.inputs;
        ForgePanelKeyState.physicalDown=true;
        events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        check(mc.screen==null&&PanelShortcut.closes==closes+1,"Polled Shift did not close CaeDuex child page");
        check(ForgeClientEvents.panelKey(344,1),"Raw callback did not consume an already polled close");
        events.key(new InputEvent.Key(344,1));
        check(mc.screen==null&&ModuleManager.inputs==inputs&&PanelShortcut.closes==closes+1,"Poll/raw/event reopened a page on the same press");
        check(ForgeClientEvents.panelKey(344,2),"Held shortcut repeat escaped to game input");

        release(events);
        check(ForgeClientEvents.panelKey(344,1),"Raw Shift failed to request panel");
        Object opened=mc.screen;
        events.key(new InputEvent.Key(344,1));
        ForgePanelKeyState.physicalDown=true;
        events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        check(opened!=null&&mc.screen==opened&&ModuleManager.inputs==inputs+1&&PanelShortcut.closes==closes+1,"Raw/event/poll immediately closed the newly opened panel");

        release(events);
        mc.screen=new WorldPanelInputScreen();WorldControlPanel.active=true;
        int worldPresses=WorldControlPanel.presses;
        ForgePanelKeyState.physicalDown=true;
        events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        check(ForgeClientEvents.panelKey(344,1),"World-panel native proxy lost ownership after polled press");
        events.key(new InputEvent.Key(344,1));
        check(WorldControlPanel.presses==worldPresses+1,"One Shift counted as a double press in world-panel proxy");
        WorldControlPanel.active=false;

        release(events);
        mc.screen=new AppCastInputScreen();AppCast.active=true;
        int castPresses=AppCast.presses;
        ForgePanelKeyState.physicalDown=true;
        events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        check(ForgeClientEvents.panelKey(344,1),"Projection native proxy lost ownership after polled press");
        events.key(new InputEvent.Key(344,1));
        check(AppCast.presses==castPresses+1,"One Shift counted as a double press in projection proxy");
        AppCast.active=false;

        release(events);
        mc.screen=new GuiBase();Object capturePage=mc.screen;ClickGuiScreen.waiting=true;
        int captures=ClickGuiScreen.captures;closes=PanelShortcut.closes;
        ForgePanelKeyState.physicalDown=true;
        events.renderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START));
        check(ClickGuiScreen.captures==captures+1&&ClickGuiScreen.assigned==344,"Polling failed to give a waiting binding its key");
        check(ForgeClientEvents.panelKey(344,1),"Captured key callback escaped duplicate handling");
        events.key(new InputEvent.Key(344,1));
        check(mc.screen==capturePage&&PanelShortcut.closes==closes&&ClickGuiScreen.captures==captures+1,"Binding capture also toggled the panel or assigned twice");
        release(events);
        ClickGuiScreen.captureBindingInput(344,0);

        mc.screen=new GuiBase();Object previewPage=mc.screen;BuildingWorldPreview.active=true;
        check(ForgeClientEvents.panelKey(344,1)&&mc.screen==previewPage,"Panel shortcut overrode native building preview ownership");
        BuildingWorldPreview.active=false;
        release(events);
        release(events);
        ParkourWaypointEditor.capturing=true;
        check(ForgeClientEvents.panelKey(344,1)&&mc.screen==previewPage,"Panel shortcut overrode waypoint key capture");
        ParkourWaypointEditor.capturing=false;
        release(events);

        Object vanilla=new Object();mc.screen=vanilla;
        inputs=ModuleManager.inputs;
        check(!ForgeClientEvents.panelKey(344,1)&&mc.screen==vanilla,"Panel shortcut took ownership of a vanilla screen");
        events.key(new InputEvent.Key(344,1));
        check(ModuleManager.inputs==inputs,"Vanilla screen leaked a module shortcut");
        System.out.println("FORGE_PAGE_SHORTCUT_PASS child-page close, raw/event/poll press ownership, native proxy double-tap protection, binding capture priority and vanilla ownership");
    }
}
