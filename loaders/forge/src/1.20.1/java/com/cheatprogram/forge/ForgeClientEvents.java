package com.cheatprogram.forge;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.dev.DevTools;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.util.ImeSupport;
import com.cheatprogram.util.KeyNames;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/** Forge owns the GUI overlay and native input/tick events on 1.20.1. */
public final class ForgeClientEvents {
    private static boolean registered;
    private static ForgeClientEvents instance;
    private final Minecraft mc=Minecraft.getInstance();
    private boolean tickReported,hudReported,imeFailureReported,physicalReported;
    private boolean panelKeyDown,polledActive;
    private int polledPanelKey=Integer.MIN_VALUE;
    private ForgeClientEvents() { }
    public static synchronized void register(){
        if(registered||!CheatProgram.initialized())return;
        instance=new ForgeClientEvents();
        MinecraftForge.EVENT_BUS.register(instance);registered=true;
        CheatProgram.LOGGER.info("Forge native client events registered");
        CheatProgram.LOGGER.info("Forge panel binding loaded: {}",ClientSettings.INSTANCE.guiKey);
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event){
        if(CheatProgram.initialized()&&event.phase==TickEvent.Phase.START)pollPanelKey();
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){
        if(!CheatProgram.initialized())return;
        if(event.phase==TickEvent.Phase.START){
            if(!tickReported){tickReported=true;CheatProgram.LOGGER.info("Forge native client tick active");}
            try{ImeSupport.tick();}catch(Throwable failure){if(!imeFailureReported){imeFailureReported=true;CheatProgram.LOGGER.warn("Forge IME update failed",failure);}}
            pollPanelKey();ModuleManager.onTick();DevTools.tick();
        }else ModuleManager.onPostTick();
    }
    @SubscribeEvent public void key(InputEvent.Key event){
        if(!CheatProgram.initialized())return;
        if(panelKey(event.getKey(),event.getAction()))return;
        if(event.getKey()==ClientSettings.INSTANCE.guiKey||event.getAction()==GLFW.GLFW_REPEAT
                ||mc.player==null||mc.screen!=null||mc.getOverlay()!=null)return;
        ModuleManager.onInput(event.getKey(),event.getAction());
    }
    /** Raw callbacks, Forge events and the physical-key fallback share one press edge. */
    public static boolean panelKey(int key,int action){
        if(instance==null||!CheatProgram.initialized()||key!=ClientSettings.INSTANCE.guiKey||key<0)return false;
        return instance.handlePanelKey(key,action);
    }
    private boolean handlePanelKey(int key,int action){
        if(polledPanelKey!=key)panelKeyDown=false;
        polledPanelKey=key;
        if(action==GLFW.GLFW_RELEASE){panelKeyDown=false;return false;}
        boolean ownsInput=mc.screen instanceof com.cheatprogram.gui.GuiBase
                ||mc.screen instanceof com.cheatprogram.gui.AppCastInputScreen
                ||mc.screen instanceof com.cheatprogram.gui.WorldPanelInputScreen
                ||mc.player!=null&&mc.screen==null&&mc.getOverlay()==null;
        if(action!=GLFW.GLFW_PRESS)return ownsInput;
        if(panelKeyDown)return ownsInput;
        panelKeyDown=true;
        if(com.cheatprogram.gui.BuildingWorldPreview.key(key,action)
                ||com.cheatprogram.gui.ClickGuiScreen.captureBindingInput(key,action)
                ||com.cheatprogram.parkour.ParkourWaypointEditor.key(key,action)
                ||com.cheatprogram.gui.PanelShortcut.handle(key,action))return true;
        if(mc.player==null||mc.getOverlay()!=null)return false;
        if(com.cheatprogram.render.WorldMusicController.key(key,action)
                ||com.cheatprogram.render.WorldControlPanel.key(key,action)
                ||com.cheatprogram.module.visual.AppCast.key(key,action))return true;
        if(mc.screen!=null)return false;
        ModuleManager.onInput(key,action);
        return true;
    }
    /** Poll only the configured panel key inside Minecraft's own GLFW window. */
    private void pollPanelKey(){
        int key=ClientSettings.INSTANCE.guiKey;boolean active=mc.isWindowActive();
        if(key<GLFW.GLFW_KEY_SPACE||key>GLFW.GLFW_KEY_LAST||mc.getWindow()==null){panelKeyDown=false;polledPanelKey=key;polledActive=active;return;}
        var state=ForgePanelKeyState.read(mc.getWindow().getWindow(),key,active);active=state.focused();boolean down=state.down();
        if(down&&!physicalReported){physicalReported=true;CheatProgram.LOGGER.info("Forge panel key state: binding={} physical={} focused={} player={} screen={} overlay={}",key,state.physical(),active,mc.player!=null,mc.screen==null?"none":mc.screen.getClass().getName(),mc.getOverlay()==null?"none":mc.getOverlay().getClass().getName());}
        if(key!=polledPanelKey||active&&!polledActive){polledPanelKey=key;panelKeyDown=down;polledActive=active;return;}
        polledActive=active;boolean fresh=down&&!panelKeyDown;panelKeyDown=down;
        if(!fresh||!active||mc.player==null||mc.getOverlay()!=null)return;
        panelKeyDown=false;
        handlePanelKey(key,GLFW.GLFW_PRESS);
    }
    @SubscribeEvent public void mouse(InputEvent.MouseButton.Post event){
        if(!CheatProgram.initialized()||event.getButton()<=1||mc.player==null||mc.screen!=null||mc.getOverlay()!=null)return;
        ModuleManager.onInput(KeyNames.MOUSE_OFFSET+event.getButton(),event.getAction());
    }
    @SubscribeEvent public void hud(RenderGuiEvent.Post event){
        if(!CheatProgram.initialized())return;
        if(!hudReported){hudReported=true;CheatProgram.LOGGER.info("Forge native GUI render active");}
        HudRenderer.render(event.getGuiGraphics(),event.getPartialTick());
    }
}
