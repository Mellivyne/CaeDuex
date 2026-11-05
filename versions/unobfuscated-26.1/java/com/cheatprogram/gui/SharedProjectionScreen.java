package com.cheatprogram.gui;

import com.cheatprogram.capture.SharedProjectionSource;
import com.cheatprogram.core.Lang;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** An invitation grants watching only. The relay and invites are never selected automatically. */
public final class SharedProjectionScreen extends GuiBase {
    private final Screen parent;
    private final SharedProjectionSource source;
    private final TextField address=new TextField(512),invite=new TextField(512);
    private String message="";
    private String revealHint="",lastPublishedInvite="";
    private boolean publishedInviteVisible;
    private final Scroller scroll=new Scroller();
    public SharedProjectionScreen(Screen parent,SharedProjectionSource source){
        super(Lang.t("ui.appcast.share.title"));this.parent=parent;this.source=source;address.setText(source.shareRelay());
        invite.masked=true;invite.onChange=value->invite.masked=true;invite.onEnter=this::watch;
    }
    private void hideInvites(){publishedInviteVisible=false;invite.masked=true;}
    private void create(){hideInvites();source.shareRelay(address.text);message=source.startSharing()?"":"ui.appcast.share.chooseSource";}
    private void revoke(){hideInvites();source.revokeSharing();}
    private void watch(){if(invite.text.isBlank())return;hideInvites();message="";source.watchSharing(invite.text.strip());}
    private void revealButton(Object key,float x,float y,boolean visible,Runnable action){
        iconButton(key,Icon.EYE,x,y,14,visible?Theme.TEXT:Theme.TEXT_3,action);
        if(over(x-14,y-14,28,28))revealHint=visible?"ui.appcast.share.hideInvite":"ui.appcast.share.showInvite";
    }
    @Override public void render(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){
        revealHint="";beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);
        try{
            float h=layoutPanel(880,640);drawPanelSurface(graphics,880,h,22);
            c.textC(BOLD,Lang.t("ui.appcast.share.title"),24,30,16,Theme.TEXT);
            iconButton("share.close",Icon.CLOSE,851,29,14,Theme.TEXT_2,this::onClose);
            c.textC(REG,Lang.t(source.videoSharing()?"ui.appcast.share.videoSubtitle":"ui.appcast.share.nativeSubtitle"),26,55,10,Theme.TEXT_2);
            float top=76,available=h-130;scroll.update(dt);hit(24,top,832,available).scroll(scroll::scroll);
            c.clip(24,top,832,available);pushHitClip(24,top,832,available);
            try{
                float y=top-scroll.get();
                c.textC(BOLD,Lang.t("ui.appcast.share.host"),28,y+15,12,Theme.TEXT);y+=30;
                textField(address,Lang.t("ui.appcast.share.relayPlaceholder"),Icon.SERVER,24,y,832,32);y+=42;
                if(source.videoSharing()){
                    button("share.quality",Lang.t(source.shareFrameRate()==0?"ui.appcast.share.sourceQuality":source.shareFrameRate()==120?"ui.appcast.share.hd120":"ui.appcast.share.hd60"),Icon.LAYOUT,24,y,832,30,source.shareSourceQuality(),()->{if(!source.sharing().publishing())source.cycleShareQuality();});y+=38;
                }
                for(String line:c.wrap(REG,Lang.t(source.videoSharing()?"ui.appcast.share.qualityHint":"ui.appcast.share.nativeHint"),9,816)){c.textC(REG,line,28,y+11,9,Theme.TEXT_3);y+=15;}y+=8;
                boolean publishing=source.sharing().publishing();
                button("share.create",Lang.t(publishing?"ui.appcast.share.regenerate":"ui.appcast.share.create"),Icon.REFRESH,24,y,405,32,publishing,this::create);
                button("share.revoke",Lang.t("ui.appcast.share.revoke"),Icon.TRASH,441,y,415,32,false,this::revoke);y+=43;
                String code=source.sharing().invite();
                if(!code.equals(lastPublishedInvite)){publishedInviteVisible=false;lastPublishedInvite=code;}
                float codeTop=y;
                String displayedCode=code.isEmpty()?Lang.t(source.sharing().publishStatus()):publishedInviteVisible?code:"•".repeat(28);
                for(String line:c.wrap(REG,displayedCode,10,640)){c.textC(REG,line,28,y+12,10,Theme.TEXT_2);y+=17;}
                if(!code.isEmpty()){
                    revealButton("share.revealPublished",686,codeTop+12,publishedInviteVisible,()->publishedInviteVisible=!publishedInviteVisible);
                    button("share.copy",Lang.t("ui.appcast.share.copy"),Icon.LINK,716,codeTop-2,140,28,false,()->{mc.keyboardHandler.setClipboard(source.sharing().invite());message="ui.appcast.share.copied";});
                    y=Math.max(y,codeTop+32);
                }
                if(!code.isEmpty()){c.textC(REG,Lang.t(source.sharing().publishStatus()),28,y+12,9,Theme.TEXT_3);y+=22;}
                String codec=source.sharing().encoderName();
                if(!codec.isEmpty()){c.textC(REG,codec+" · "+Lang.t("ui.appcast.share.sent")+" "+source.sharing().sentFrames(),28,y+12,9,Theme.TEXT_3);y+=22;}
                y+=16;c.textC(BOLD,Lang.t("ui.appcast.share.viewer"),28,y+15,12,Theme.TEXT);y+=31;
                textField(invite,Lang.t("ui.appcast.share.invitePlaceholder"),Icon.LINK,24,y,790,32);
                if(!invite.text.isEmpty())revealButton("share.revealViewer",836,y+16,!invite.masked,()->invite.masked=!invite.masked);y+=43;
                button("share.watch",Lang.t("ui.appcast.share.watch"),Icon.EYE,24,y,405,32,source.sharing().watching(),this::watch);
                button("share.leave",Lang.t("ui.appcast.share.leave"),Icon.CLOSE,441,y,415,32,false,source::leaveSharing);y+=43;
                c.textC(REG,Lang.t(source.sharing().watchStatus())+" · "+Lang.t("ui.appcast.share.received")+" "+source.sharing().receivedFrames(),28,y+12,9,Theme.TEXT_2);y+=26;
                if(source instanceof com.cheatprogram.module.visual.AppCast cast){button("share.local",Lang.t("ui.appcast.share.localPlacement"),Icon.NAVIGATION,24,y,832,30,cast.shareLocal(),()->cast.shareLocal(!cast.shareLocal()));y+=40;}
                for(String key:new String[]{"ui.appcast.share.viewerHint","ui.appcast.share.privacy","ui.appcast.share.relayHint"}){
                    for(String line:c.wrap(REG,Lang.t(key),9,816)){c.textC(REG,line,28,y+11,9,Theme.TEXT_3);y+=15;}y+=8;
                }
                scroll.max=Math.max(0,y-(top-scroll.get())+8-available);
            }finally{popHitClip();c.unclip();}
            panelScrollBar("share.scroll",scroll,862,top,available);
            button("share.back",Lang.t("ui.back"),Icon.BACK,24,h-42,120,28,false,this::onClose);
            String footer=revealHint.isEmpty()?message:revealHint;
            if(!footer.isEmpty())c.textC(REG,c.ellipsize(REG,Lang.t(footer),10,684),164,h-27,10,Theme.TEXT_2);
        }finally{R2D.end();endFrame();}
    }
    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=null;return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return focused!=null&&focused.keyPressed(key,mods);}
    @Override public boolean charTyped(char ch,int mods){return focused!=null&&focused.charTyped(ch);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){hideInvites();super.removed();}
    @Override public void onClose(){hideInvites();focused=null;mc.setScreen(parent);}
}
