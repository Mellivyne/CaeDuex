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
    private final Minecraft mc=Minecraft.getInstance();
    private boolean tickReported,hudReported,keyReported,imeFailureReported,pollReported,physicalReported;
    private boolean panelKeyDown,polledActive;
    private int polledPanelKey=Integer.MIN_VALUE;
    private ForgeClientEvents() { }
    public static synchronized void register(){
        if(registered||!CheatProgram.initialized())return;
        MinecraftForge.EVENT_BUS.register(new ForgeClientEvents());registered=true;
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
        boolean fresh=true,panel=event.getKey()==ClientSettings.INSTANCE.guiKey;
        if(panel){
            if(polledPanelKey!=event.getKey())panelKeyDown=false;
            polledPanelKey=event.getKey();polledActive=mc.isWindowActive();
            if(event.getAction()==GLFW.GLFW_RELEASE)panelKeyDown=false;
            else if(event.getAction()==GLFW.GLFW_PRESS){fresh=!panelKeyDown;panelKeyDown=true;}
            if(event.getAction()==GLFW.GLFW_PRESS&&!keyReported){keyReported=true;CheatProgram.LOGGER.info("Forge panel key event: screen={} overlay={} focused={}",mc.screen==null?"none":mc.screen.getClass().getName(),mc.getOverlay()==null?"none":mc.getOverlay().getClass().getName(),mc.isWindowActive());}
        }
        if(event.getAction()==GLFW.GLFW_REPEAT||mc.player==null||mc.screen!=null||mc.getOverlay()!=null||panel&&event.getAction()==GLFW.GLFW_PRESS&&!fresh)return;
        ModuleManager.onInput(event.getKey(),event.getAction());
    }
    /** Poll only the configured panel key inside Minecraft's own GLFW window. */
    private void pollPanelKey(){
        int key=ClientSettings.INSTANCE.guiKey;boolean active=mc.isWindowActive();
        if(key<GLFW.GLFW_KEY_SPACE||key>GLFW.GLFW_KEY_LAST||mc.getWindow()==null){panelKeyDown=false;polledPanelKey=key;polledActive=active;return;}
        var state=ForgePanelKeyState.read(mc.getWindow().getWindow(),key,active);active=state.focused();boolean down=state.down();
        if(down&&!physicalReported){physicalReported=true;CheatProgram.LOGGER.info("Forge panel key state: binding={} physical={} focused={} player={} screen={} overlay={}",key,state.physical(),active,mc.player!=null,mc.screen==null?"none":mc.screen.getClass().getName(),mc.getOverlay()==null?"none":mc.getOverlay().getClass().getName());}
        if(key!=polledPanelKey||active&&!polledActive){polledPanelKey=key;panelKeyDown=down;polledActive=active;return;}
        polledActive=active;boolean fresh=down&&!panelKeyDown;panelKeyDown=down;
        if(!fresh||!active||mc.player==null||mc.screen!=null||mc.getOverlay()!=null)return;
        if(!pollReported){pollReported=true;CheatProgram.LOGGER.info("Forge panel key from window polling");}
        ModuleManager.onInput(key,GLFW.GLFW_PRESS);
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
