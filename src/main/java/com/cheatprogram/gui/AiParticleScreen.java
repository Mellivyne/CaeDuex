package com.cheatprogram.gui;

import com.cheatprogram.ai.AiParticleDesigner;
import com.cheatprogram.core.*;
import com.cheatprogram.module.visual.ParticleEffects;
import com.cheatprogram.render.*;
import com.cheatprogram.util.ParticleStudio;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

/** A generated composition has a private native preview. Adding it to the editor is an explicit action. */
public final class AiParticleScreen extends GuiBase {
    private static final float W=920,H=620;
    private static final java.util.concurrent.ExecutorService EXCHANGE_IO=java.util.concurrent.Executors.newFixedThreadPool(2,task->{Thread thread=new Thread(task,"CaeDuex local design exchange");thread.setDaemon(true);return thread;});
    private final Screen parent;
    private final ParticleEffects module;
    private final AiParticleDesigner designer=new AiParticleDesigner();
    private final TextField endpoint=new TextField(2048),key=new TextField(4096),prompt=new TextField();
    private final AiConnectionPicker models=new AiConnectionPicker(AiParticleDesigner.CONNECTION);
    private final AiImagePicker images=new AiImagePicker();
    private final Scroller formScroll=new Scroller();
    private final ParticleLayoutPreview particles;
    private final ParticlePlayerPreview player=new ParticlePlayerPreview();
    private AiParticleDesigner.Design result;
    private com.cheatprogram.ai.AiDesignExchange.Ticket exchange;
    private boolean exchangePolling;
    private long nextExchangePoll;
    private volatile boolean closed;
    private boolean busy;
    private volatile long generation;
    private int roundIndex;
    private float yaw=-35,pitch=20,zoom=1;
    private String status="ui.aiParticles.hint";

    public AiParticleScreen(Screen parent,ParticleEffects module){
        super(Lang.t("ui.aiParticles.title"));this.parent=parent;this.module=module;particles=new ParticleLayoutPreview(module);
        endpoint.setText(AiParticleDesigner.CONNECTION.endpoint());key.masked=true;
        endpoint.onChange=value->{key.setText("");models.invalidate();};
        key.onChange=value->models.invalidate();
        prompt.onEnter=this::generate;
    }
    @Override protected void init(){closed=false;}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        pollExchange();beginFrame();if(!preparePanelBackground(graphics,partialTick)){endFrame();return;}
        R2D.begin(graphics);
        try{
            float height=layoutPanel(W,H);drawPanelSurface(graphics,W,height,22);
            c.textC(BOLD,Lang.t("ui.aiParticles.title"),24,29,15,Theme.TEXT);
            iconButton("aiParticles.close",Icon.CLOSE,W-28,29,14,Theme.TEXT_2,this::onClose);
            float top=58,bottom=height-70;
            form(top,bottom);preview(graphics,344,top,W-364,bottom-top);
            c.textC(REG,c.ellipsize(REG,Lang.t(status),9.5f,W-48),24,height-55,9.5f,Theme.TEXT_2);
            button("aiParticles.back",Lang.t("ui.back"),Icon.BACK,24,height-40,94,28,false,this::onClose);
            button("aiParticles.copy",Lang.t("ui.aiParticles.copy"),Icon.LINK,350,height-40,184,28,false,()->{if(result!=null){mc.keyboardHandler.setClipboard(result.code());status="ui.aiParticles.copied";}});
            button("aiParticles.add",Lang.t("ui.aiParticles.add"),Icon.PLUS,544,height-40,W-568,28,true,this::addToEditor);
            models.overlay(this,W,height);
        }finally{R2D.end();endFrame();}
    }
    private void form(float top,float bottom){
        float x=28,width=286,y=top+17;
        c.lens(18,top,310,bottom-top,14,Theme.lift(.025f),Theme.lensGlass(c.scale));
        hit(18,top,310,bottom-top).scroll(formScroll::scroll);
        c.clip(20,top+2,306,bottom-top-4);pushHitClip(20,top+2,306,bottom-top-4);
        y-=formScroll.get();float start=y;
        models.providers(this,x,y,width,endpoint,key,()->{cancelExchange();generation++;busy=false;status="ui.aiParticles.hint";});y+=34;
        for(Object[] row:new Object[][]{{"ui.building.ai.endpoint",endpoint},{"ui.building.ai.key",key},{"ui.aiParticles.request",prompt}}){
            c.textC(REG,Lang.t((String)row[0]),x,y,10,Theme.TEXT_2);y+=13;textField((TextField)row[1],Lang.t((String)row[0]),null,x,y,width,29);y+=46;
        }
        models.draw(this,x,y,width,this::saveConnection);y+=60;
        images.draw(this,x,y,width);y+=66;
        button("aiParticles.generate",Lang.t(busy?"ui.building.ai.cancel":result==null?"ui.aiParticles.generate":"ui.aiParticles.revise"),null,x,y,width,29,true,()->{if(busy){generation++;busy=false;status="ui.building.ai.cancelled";}else generate();});y+=44;
        c.textC(MED,Lang.t("ui.ai.exchange.title"),x,y,10,Theme.TEXT_2);y+=16;
        button("aiParticles.mcpPrompt",Lang.t("ui.ai.exchange.copyPrompt"),Icon.LINK,x,y,width,27,false,this::copyExchange);y+=34;
        button("aiParticles.mcpImport",Lang.t("ui.ai.exchange.import"),Icon.PLUS,x,y,width,27,false,this::importExchange);y+=35;
        if(exchange!=null){button("aiParticles.mcpCancel",Lang.t("ui.ai.exchange.cancel"),Icon.CLOSE,x,y,width,25,false,()->{generation++;cancelExchange();status="ui.building.ai.cancelled";});y+=32;}
        for(String line:c.wrap(REG,Lang.t("ui.ai.exchange.hint"),8.5f,width)){c.textC(REG,line,x,y,8.5f,Theme.TEXT_3);y+=13;}y+=12;
        button("aiParticles.forgetKey",Lang.t("ui.aiParticles.forgetKey"),null,x,y,width,25,false,()->{if(!busy){AiParticleDesigner.CONNECTION.deleteApiKey();key.setText("");status="ui.aiParticles.keyDeleted";}});y+=38;
        for(String line:c.wrap(REG,Lang.t("ui.aiParticles.hint"),9,width)){c.textC(REG,line,x,y,9,Theme.TEXT_3);y+=14;}
        if(result!=null){y+=15;for(String line:c.wrap(REG,Lang.t("ui.aiParticles.codeReady",result.code().length()),9,width)){c.textC(REG,line,x,y,9,Theme.TEXT_2);y+=14;}}
        popHitClip();c.unclip();formScroll.max=Math.max(0,y-start-(bottom-top)+25);formScroll.update(dt);
        panelScrollBar("aiParticles.form.scroll",formScroll,322,top+4,bottom-top-8);
    }
    private void preview(GuiGraphics graphics,float x,float y,float width,float height){
        c.rrect(x,y,width,height,14,0x2810151e);
        c.textC(MED,Lang.t("ui.aiParticles.preview"),x+16,y+20,12,Theme.TEXT);
        if(result==null){c.textMid(REG,Lang.t("ui.aiParticles.previewEmpty"),x+width/2,y+height/2,10,Theme.TEXT_3);return;}
        var rounds=result.prepared().particles().rounds;roundIndex=Math.max(0,Math.min(rounds.size()-1,roundIndex));var round=rounds.get(roundIndex);
        button("aiParticles.prev","‹",null,x+12,y+37,28,25,false,()->{roundIndex=(roundIndex+rounds.size()-1)%rounds.size();particles.close();});
        c.textC(REG,c.ellipsize(REG,(roundIndex+1)+" / "+rounds.size()+" · "+round.name,10,width-100),x+48,y+50,10,Theme.TEXT_2);
        button("aiParticles.next","›",null,x+width-40,y+37,28,25,false,()->{roundIndex=(roundIndex+1)%rounds.size();particles.close();});
        float sceneY=y+75,sceneHeight=Math.max(40,height-112);particles.update(round,true);
        Quaternionf rotation=new Quaternionf().rotationX((float)Math.toRadians(pitch)).rotateY((float)Math.toRadians(yaw));
        Vec3 reference=mc.player==null?Vec3.ZERO:mc.player.position();float bodyYaw=mc.player==null?0:(float)Math.toRadians(mc.player.yBodyRot);
        c.clip(x+3,sceneY,width-6,sceneHeight);
        try{player.draw(graphics,c.px(x+3),c.py(sceneY),(width-6)*c.scale,sceneHeight*c.scale,(width-6)*.5f*c.scale,sceneHeight*.65f*c.scale,50*zoom*c.scale,rotation,particles,reference,bodyYaw);}
        finally{c.unclip();}
        if(!player.ready())c.textMid(REG,Lang.t(player.statusKey()),x+width/2,sceneY+20,9,Theme.TEXT_3);
        c.textC(REG,Lang.t("ui.aiParticles.previewHint"),x+14,y+height-17,8.5f,Theme.TEXT_3);
        hit(x+3,sceneY,width-6,sceneHeight).scroll(amount->zoom=Math.max(.005f,Math.min(32,zoom*(float)Math.pow(1.12,amount)))).left(()->{focused=null;float sx=mx,sy=my,oldYaw=yaw,oldPitch=pitch;drag=(xx,yy)->{yaw=oldYaw+(xx-sx)*.5f;pitch=Math.max(-89,Math.min(89,oldPitch+(yy-sy)*.5f));};});
    }
    private void saveConnection(){
        var config=AiParticleDesigner.CONNECTION;config.endpoint(endpoint.text);if(!key.text.isBlank()){config.apiKey(key.text);key.setText("");}config.save();
    }
    private void generate(){
        if(busy||prompt.text.isBlank())return;
        cancelExchange();saveConnection();var config=AiParticleDesigner.CONNECTION;
        if(!config.hasApiKey()){status="ui.aiParticles.needKey";return;}
        busy=true;status="ui.aiParticles.working";long ticket=++generation;
        try{designer.generate(prompt.text,result==null?null:result.plan(),images.images(),()->!closed&&ticket==generation&&Config.persistenceAllowed()).whenComplete((design,error)->mc.execute(()->{
            if(closed||ticket!=generation)return;busy=false;
            if(error!=null){status=errorKey(error);return;}
            applyDesign(design);
        }));}catch(Exception error){busy=false;status=errorKey(error);}
    }
    private void cancelExchange(){
        var previous=exchange;exchange=null;
        if(previous!=null)EXCHANGE_IO.execute(previous::close);
    }
    private void copyExchange(){
        if(busy)return;
        if(prompt.text.isBlank()){status="ui.ai.exchange.needPrompt";return;}
        cancelExchange();busy=true;long token=++generation;status="ui.ai.exchange.preparing";
        String language=Lang.language();
        String request=prompt.text,instructions=AiParticleDesigner.instructions(language),previous=result==null?"":result.plan().toString();
        int imageCount=images.images().size();
        java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try{return com.cheatprogram.ai.AiDesignExchange.open("particles",request,instructions,previous,imageCount,language);}
            catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
        },EXCHANGE_IO).whenComplete((ticket,error)->mc.execute(()->{
            if(closed||token!=generation){if(ticket!=null)EXCHANGE_IO.execute(ticket::close);return;}
            busy=false;
            if(error!=null){status=errorKey(error);return;}
            exchange=ticket;nextExchangePoll=0;
            mc.keyboardHandler.setClipboard(ticket.prompt());
            status="ui.ai.exchange.promptCopied";
        }));
    }
    private void importExchange(){
        if(busy)return;
        String clipboard=mc.keyboardHandler.getClipboard();
        if(clipboard.length()>com.cheatprogram.ai.AiDesignExchange.MAX_BYTES){status="ui.ai.exchange.tooLarge";return;}
        cancelExchange();busy=true;long token=++generation;status="ui.ai.exchange.importing";
        java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try{return AiParticleDesigner.decode(com.cheatprogram.ai.AiDesignExchange.parseResult(clipboard));}
            catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
        },EXCHANGE_IO).whenComplete((design,error)->mc.execute(()->{
            if(closed||token!=generation)return;busy=false;
            if(error!=null){status=errorKey(error);return;}applyDesign(design);
        }));
    }
    private void pollExchange(){
        if(closed||busy||exchange==null||exchangePolling||System.nanoTime()<nextExchangePoll)return;
        nextExchangePoll=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);exchangePolling=true;
        var ticket=exchange;long token=generation;
        java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try{var data=ticket.poll();return data.isPresent()?AiParticleDesigner.decode(data.get()):null;}
            catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
        },EXCHANGE_IO).whenComplete((design,error)->mc.execute(()->{
            exchangePolling=false;
            if(closed||token!=generation||exchange!=ticket)return;
            if(error!=null){cancelExchange();status=errorKey(error);return;}
            if(design!=null){cancelExchange();applyDesign(design);}
        }));
    }
    private void applyDesign(AiParticleDesigner.Design design){
        if(mc.level!=null)for(var round:design.prepared().particles().rounds)for(var emitter:round.emitters)if(!module.validate(emitter).isBlank()){status="ui.aiParticles.nativeInvalid";return;}
        result=design;roundIndex=0;particles.close();status="ui.aiParticles.ready";
    }
    private void addToEditor(){
        if(result==null||busy)return;
        var current=ParticleStudio.project();var added=result.prepared().particles();
        if(current.rounds.size()+added.rounds.size()>ParticleStudio.MAX_ROUNDS||ParticleStudio.total()+added.rounds.stream().mapToInt(r->r.emitters.size()).sum()>ParticleStudio.MAX_TOTAL){status="ui.aiParticles.projectFull";return;}
        // Append instead of replacing the user's current composition. Validate a fresh
        // decode so repeated additions never share mutable emitters or IDs.
        try{
            var prepared=FeaturePresets.validate(ScopedPresetCodec.decode(ScopedPresetCodec.Scope.PARTICLES,result.code()));
            current.rounds.addAll(prepared.particles().rounds);ParticleStudio.touch();ParticleStudio.flush(true);module.restart();
            mc.setScreen(new ParticleEffectsScreen(parent,module));
        }catch(Exception error){status="ui.aiParticles.invalid";}
    }
    private static String errorKey(Throwable error){while(error.getCause()!=null)error=error.getCause();String value=error.getMessage();return value!=null&&(value.startsWith("ui.aiParticles.")||value.startsWith("ui.ai.images.")||value.startsWith("ui.ai.exchange."))?value:"ui.aiParticles.failed";}
    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=null;return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){if(models.dismiss(this))return true;onClose();return true;}return focused!=null&&focused.keyPressed(key,mods);}
    @Override public boolean charTyped(char value,int mods){return focused!=null&&focused.charTyped(value);}
    @Override public void onClose(){mc.setScreen(parent);}
    @Override public void removed(){closed=true;generation++;busy=false;cancelExchange();particles.close();player.close();images.close();models.close();super.removed();}
}
