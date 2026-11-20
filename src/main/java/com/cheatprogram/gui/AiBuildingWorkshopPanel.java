package com.cheatprogram.gui;

import com.cheatprogram.ai.BuildingDesignWorkshop;
import com.cheatprogram.ai.BuildingDesignWorkshop.Options;
import com.cheatprogram.ai.BuildingDesignWorkshop.Snapshot;
import com.cheatprogram.ai.BuildingDesignWorkshop.State;
import com.cheatprogram.ai.BuildingDesignWorkshop.Task;
import com.cheatprogram.ai.BuildingDesignWorkshop.Tier;
import com.cheatprogram.ai.BuildingDesignWorkshop.Message;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.render.Icon;
import com.cheatprogram.util.Anim;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Local workload choices and a view of actual public task results, never private reasoning. */
final class AiBuildingWorkshopPanel {
    private static final int COLLAPSED_LINES=3;
    private static final ScheduledExecutorService SAVE=Executors.newSingleThreadScheduledExecutor(task->{
        Thread thread=new Thread(task,"Building design preferences");thread.setDaemon(true);return thread;
    });
    private static ScheduledFuture<?> save;
    private final EnumMap<Tier,Integer> counts=new EnumMap<>(Tier.class);
    private final TextField agentCount=new TextField(2);
    private final GuiBase.Scroller progressScroll=new GuiBase.Scroller();
    private final GuiBase.Scroller messageScroll=new GuiBase.Scroller();
    private final Anim completion=new Anim(0);
    private Tier tier=Tier.MAX,runTier=Tier.MAX;
    private Snapshot snapshot=Snapshot.idle();
    private boolean progressOpen;
    private float marker=Tier.MAX.ordinal(),markerSpeed;
    private Snapshot measured;
    private float measuredWidth,measuredScale,taskHeight;
    private long languageRevision=Long.MIN_VALUE;
    private List<Row> rows=List.of();
    private boolean messagesOpen;
    private String conversationTask="";
    private Snapshot measuredMessages;
    private String measuredConversation="";
    private float messageWidth,messageScale,messageHeight;
    private long messageLanguage=Long.MIN_VALUE;
    private List<MessageRow> messageRows=List.of();
    private final Set<String> expandedTasks=new HashSet<>();
    private final Set<String> expandedMessages=new HashSet<>();

    private record FoldedText(List<String> lines,boolean expanded) {
        boolean expandable(){return lines.size()>COLLAPSED_LINES;}
        int visibleLines(){return expanded?lines.size():Math.min(COLLAPSED_LINES,lines.size());}
    }
    private record Row(Task task,List<String> name,List<String> role,FoldedText summary,float offset,float height) { }
    private record MessageRow(Message message,List<String> route,FoldedText body,float offset,float height) { }

    AiBuildingWorkshopPanel(){
        for(Tier value:Tier.values())counts.put(value,value.defaultAgents());
        read();
        marker=tier.ordinal();runTier=tier;agentCount.setText(Integer.toString(counts.get(tier)));
        agentCount.onChange=value->{
            try{int number=Integer.parseInt(value);if(number>=1&&number<=64){counts.put(tier,number);persist();}}
            catch(NumberFormatException ignored){ }
        };
        agentCount.onEnter=()->{
            int value=counts.get(tier);
            try{value=Math.max(1,Math.min(64,Integer.parseInt(agentCount.text)));}catch(NumberFormatException ignored){ }
            setAgents(value);
        };
    }
    Options options(){return new Options(tier,counts.get(tier));}
    void observeRun(Options value){if(value!=null)runTier=value.tier();}
    void snapshot(Snapshot value){
        if(value==null)return;
        if(snapshot.total()==0&&value.total()>0)completion.snap((float)value.completed()/value.total());
        snapshot=value;
    }
    Snapshot snapshot(){return snapshot;}
    void begin(){
        snapshot=Snapshot.idle();runTier=tier;measured=null;measuredMessages=null;
        expandedTasks.clear();expandedMessages.clear();
        messagesOpen=false;conversationTask="";completion.snap(0);
        progressScroll.target=0;progressScroll.value.snap(0);
        messageScroll.target=0;messageScroll.value.snap(0);
        progressOpen=true;
    }
    void cancelled(){terminal(State.CANCELLED,"cancelled","Generation was cancelled; incomplete work was not applied.");}
    void failed(){terminal(State.FAILED,"failed","Generation did not pass all required tasks; the previous preview is preserved.");}
    private void terminal(State state,String phase,String report){
        List<Task> tasks=snapshot.tasks().stream().map(task->task.state()==State.QUEUED||task.state()==State.RUNNING
                ?new Task(task.id(),task.title(),task.role(),state,task.summary()):task).toList();
        int completed=(int)tasks.stream().filter(task->task.state()==State.COMPLETED).count();
        snapshot=new Snapshot(phase,tasks,completed,snapshot.total(),snapshot.usage(),report,snapshot.messages());measured=null;
    }
    boolean overlayOpen(){return progressOpen;}
    boolean dismiss(GuiBase gui){if(!progressOpen)return false;progressOpen=false;gui.focused=null;return true;}

    /** Returns the consumed height; the owner supplies its own outer scrolling clip. */
    float drawSettings(GuiBase gui,float x,float y,float width,boolean locked){
        if(locked&&gui.focused==agentCount)gui.focused=null;
        gui.c.textC(GuiBase.REG,Lang.t("ui.building.workshop.effort"),x,y+3,10,Theme.TEXT_2);
        gui.c.textRight(GuiBase.MED,label(tier),x+width,y+3,10,tierColor(tier));
        float cardY=y+18,cardH=113;
        gui.c.shadow(x,cardY+3,width,cardH,20,12,0x18061428);
        gui.c.lens(x,cardY,width,cardH,20,Theme.lift(.045f),Theme.lensGlass(gui.c.scale));
        float tick=Math.min(.05f,gui.dt),target=tier.ordinal();
        markerSpeed+=(target-marker)*110*tick;markerSpeed*=(float)Math.exp(-16*tick);marker+=markerSpeed*tick;
        int levels=Tier.values().length,columns=3;
        float gap=4,step=(width-20-gap*2)/columns;
        for(int i=0;i<levels;i++){
            Tier value=Tier.values()[i];float tx=x+10+(i%columns)*(step+gap),ty=cardY+8+(i/columns)*28;
            boolean over=gui.over(tx,ty,step,25),selected=value==tier;
            if(selected){
                gui.c.rrect(tx,ty,step,25,12.5f,Anim.lerpColor(0x247c7caa,tierColor(value),.12f));
                gui.c.rrect(tx+1,ty+1,step-2,23,11.5f,0x20ffffff,0x06ffffff);
            }else if(over)gui.c.rrect(tx,ty,step,25,12.5f,0x12ffffff);
            gui.c.textMid(selected?GuiBase.MED:GuiBase.REG,label(value).stripTrailing(),tx+step/2,ty+12.5f,9.2f,selected?Theme.TEXT:Theme.TEXT_3);
            if(!locked)gui.hit(tx,ty,step,25).left(()->select(value,gui));
        }
        float railX=x+12,railY=cardY+77,railW=width-24,railH=22,radius=railH/2;
        float fraction=BuildingEffortFlow.fraction(marker,levels);
        float thumb=BuildingEffortFlow.thumb(railX,railW,radius,fraction);
        gui.c.shadow(railX,railY+2,railW,railH,radius,7,tier==Tier.ULTRA?0x305e5aff:0x18000000);
        gui.c.rrect(railX,railY,railW,railH,radius,0x25213049);
        if(tier==Tier.ULTRA)pixels(gui,railX,railY,railW,railH,1);
        else gui.c.rrect(railX,railY,Math.min(railW,thumb-railX+radius),railH,radius,
                Anim.lerpColor(tierColor(tier),0xff22254e,.24f),tierColor(tier));
        gui.c.rrect(railX+radius,railY+2,Math.max(0,railW-2*radius),1,.5f,0x35ffffff);
        for(int i=0;i<levels;i++){
            float dot=BuildingEffortFlow.thumb(railX,railW,radius,(float)i/(levels-1));
            if(Math.abs(dot-thumb)>10)gui.c.circle(dot,railY+radius,1.1f,0x72ffffff);
        }
        gui.c.shadow(thumb-radius,railY,railH,railH,radius,5,0x35070a1c);
        gui.c.circle(thumb,railY+radius,radius,0xfffafaff);
        gui.c.circle(thumb-2,railY+radius-3,2,0x7fffffff);
        if(!locked)gui.hit(railX-4,railY-5,railW+8,railH+10).left(()->{
            gui.focused=null;gui.drag=(mx,my)->select(Tier.values()[BuildingEffortFlow.selection(mx,railX,railW,radius,levels)],gui);
            gui.drag.drag(gui.mx,gui.my);
        });
        float hintY=cardY+cardH+13;
        List<String> hint=gui.c.wrap(GuiBase.REG,Lang.t("ui.building.workshop.tier."+tier.name().toLowerCase(Locale.ROOT)),8.2f,width);
        for(int i=0;i<Math.min(3,hint.size());i++)gui.c.textC(GuiBase.REG,hint.get(i),x,hintY+i*12,8.2f,Theme.TEXT_3);
        float countY=hintY+Math.min(3,hint.size())*12+13;
        gui.c.textC(GuiBase.REG,Lang.t("ui.building.workshop.agents"),x,countY,9,Theme.TEXT_2);
        gui.c.textRight(GuiBase.REG,Lang.t("ui.building.workshop.agentRange"),x+width,countY,7.5f,Theme.TEXT_4);
        float fieldY=countY+12;
        gui.button("building.workshop.agents.minus","−",null,x,fieldY,32,27,false,()->{if(!locked)setAgents(counts.get(tier)-1);});
        if(!locked)gui.textField(agentCount,"",null,x+38,fieldY,width-114,27);
        else {gui.c.rrect(x+38,fieldY,width-114,27,7,0x10ffffff);gui.c.textMid(GuiBase.MED,Integer.toString(counts.get(tier)),x+38+(width-114)/2,fieldY+14,10,Theme.TEXT_3);}
        gui.button("building.workshop.agents.plus","+",null,x+width-70,fieldY,32,27,false,()->{if(!locked)setAgents(counts.get(tier)+1);});
        gui.iconButton("building.workshop.agents.reset",Icon.REFRESH,x+width-16,fieldY+14,12,Theme.TEXT_3,()->{if(!locked)setAgents(tier.defaultAgents());});
        float noteY=fieldY+42;
        List<String> note=gui.c.wrap(GuiBase.REG,Lang.t("ui.building.workshop.notice"),7.6f,width);
        for(int i=0;i<Math.min(3,note.size());i++)gui.c.textC(GuiBase.REG,note.get(i),x,noteY+i*11,7.6f,Theme.TEXT_4);
        return noteY+Math.min(3,note.size())*11+9-y;
    }
    void progressButton(GuiBase gui,float x,float y,float width){
        String suffix=snapshot.total()>0?"  "+snapshot.completed()+" / "+snapshot.total():"";
        gui.button("building.workshop.progress",Lang.t("ui.building.workshop.tasks")+suffix,Icon.ACTIVITY,x,y,width,28,progressOpen,()->{progressOpen=!progressOpen;gui.focused=null;});
    }
    void overlay(GuiBase gui,float panelWidth,float panelHeight){
        if(!progressOpen)return;
        float width=Math.min(640,panelWidth-24),height=Math.min(590,panelHeight-24),x=(panelWidth-width)/2,y=(panelHeight-height)/2;
        gui.hit(0,0,panelWidth,panelHeight).click(button->dismiss(gui)).scroll(amount->{});
        gui.c.shadow(x,y+3,width,height,18,9,0x18000000);
        gui.c.lens(x,y,width,height,18,Theme.lift(.115f),Theme.lensGlass(gui.c.scale));
        gui.hit(x,y,width,height).click(button->{}).scroll(amount->{});
        gui.c.textC(GuiBase.BOLD,Lang.t("ui.building.workshop.tasks"),x+20,y+23,13,Theme.TEXT);
        gui.c.textRight(GuiBase.MED,label(runTier),x+width-49,y+23,10,tierColor(runTier));
        gui.iconButton("building.workshop.close",Icon.CLOSE,x+width-22,y+23,13,Theme.TEXT_2,()->dismiss(gui));
        drawProgress(gui,x+14,y+44,width-28,height-58,snapshot);
    }
    /** Also usable as an embedded panel when the host has space beside the preview. */
    void drawProgress(GuiBase gui,float x,float y,float width,float height,Snapshot state){
        if(state==null||height<100)return;
        float tabWidth=Math.min(150,(width-8)/2);
        gui.button("building.workshop.tab.tasks",Lang.t("ui.building.workshop.taskList"),null,x,y,tabWidth,25,!messagesOpen,()->{messagesOpen=false;gui.focused=null;});
        gui.button("building.workshop.tab.messages",Lang.t("ui.building.workshop.allMessages",state.messages().size()),null,x+tabWidth+6,y,tabWidth,25,messagesOpen&&conversationTask.isEmpty(),()->openMessages(gui,""));
        y+=33;height-=33;
        if(messagesOpen){drawMessages(gui,x,y,width,height,state);return;}
        String summary=publicText(state.summary());
        List<String> summaryLines=gui.c.wrap(GuiBase.REG,summary.isBlank()?Lang.t("ui.building.workshop.ready"):summary,9,width-6);
        for(int i=0;i<summaryLines.size();i++)gui.c.textC(GuiBase.REG,summaryLines.get(i),x+3,y+9+i*13,9,Theme.TEXT_2);
        float metaY=y+summaryLines.size()*13+13;
        String phase=phase(state.phase());
        String completed=Lang.t("ui.building.workshop.completed",state.completed(),state.total());
        float phaseWidth=Math.max(24,width-gui.c.textWidth(GuiBase.REG,completed,8.5f)-18);
        List<String> phases=gui.c.wrap(GuiBase.MED,phase,9,phaseWidth);
        for(int i=0;i<phases.size();i++)gui.c.textC(GuiBase.MED,phases.get(i),x+3,metaY+i*12,9,Theme.TEXT);
        gui.c.textRight(GuiBase.REG,completed,x+width-3,metaY,8.5f,Theme.TEXT_3);
        metaY+=Math.max(0,phases.size()-1)*12;
        float progress=state.total()==0?0:(float)state.completed()/state.total();completion.to(progress,gui.dt,9);
        gui.c.rrect(x+3,metaY+11,width-6,5,2.5f,0x18ffffff);
        if(completion.value>.001f){
            if(runTier==Tier.ULTRA)pixels(gui,x+3,metaY+11,(width-6)*Math.min(1,completion.value),5,.85f);
            else gui.c.rrect(x+3,metaY+11,(width-6)*Math.min(1,completion.value),5,2.5f,tierColor(runTier));
        }
        var usage=state.usage();
        boolean anyUsage=usage.inputTokens()>=0||usage.outputTokens()>=0||usage.totalTokens()>=0;
        String tokens=anyUsage?Lang.t("ui.building.workshop.tokens",number(usage.inputTokens()),number(usage.outputTokens()),number(usage.totalTokens())):Lang.t("ui.building.workshop.tokensUnknown");
        float usageY=metaY+31;
        for(String line:gui.c.wrap(GuiBase.REG,tokens,8.2f,width-6)){
            gui.c.textC(GuiBase.REG,line,x+3,usageY,8.2f,Theme.TEXT_3);usageY+=13;
        }
        String requests=Lang.t("ui.building.workshop.requests",usage.requests(),usage.unknownRequests());
        for(String line:gui.c.wrap(GuiBase.REG,requests,7.7f,width-6)){
            gui.c.textC(GuiBase.REG,line,x+3,usageY,7.7f,Theme.TEXT_4);usageY+=13;
        }
        float top=usageY+1,view=y+height-top;
        if(view<20)return;
        measure(gui,state,width);
        progressScroll.max=Math.max(0,taskHeight-view);progressScroll.update(gui.dt);
        gui.hit(x,top,width,view).scroll(progressScroll::scroll);
        gui.c.clip(x,top,width,view);gui.pushHitClip(x,top,width,view);
        try{
            if(rows.isEmpty())gui.c.textC(GuiBase.REG,Lang.t("ui.building.workshop.waiting"),x+10,top+21,9,Theme.TEXT_4);
            for(Row row:rows){
                float rowY=top+row.offset()-progressScroll.get();
                if(rowY+row.height()<top)continue;if(rowY>top+view)break;
                Task task=row.task();int color=stateColor(task.state());
                gui.c.rrect(x+1,rowY,width-9,row.height(),10,task.state()==State.RUNNING?0x1a8c69ff:0x12ffffff);
                if(task.state()==State.RUNNING){
                    float pulse=.5f+.5f*(float)Math.sin(System.nanoTime()/260_000_000.0);
                    gui.c.circle(x+15,rowY+17,6,Anim.lerpColor(0x087f72ed,0x45878cff,pulse));
                }
                gui.c.circle(x+15,rowY+17,2.7f,color);
                for(int i=0;i<row.name().size();i++)gui.c.textC(GuiBase.MED,row.name().get(i),x+28,rowY+16+i*13,9,Theme.TEXT);
                float statusX=x+width-(row.summary().expandable()?46:19);
                gui.c.textRight(GuiBase.REG,Lang.t("ui.building.workshop.state."+task.state().name().toLowerCase(Locale.ROOT)),statusX,rowY+16,8,color);
                if(row.summary().expandable()){
                    gui.iconButton("building.workshop.taskFold."+task.id(),foldIcon(row.summary()),x+width-23,rowY+16,12,Theme.TEXT_2,
                            ()->{toggle(expandedTasks,task.id());measured=null;});
                }
                float textY=rowY+34+Math.max(0,row.name().size()-1)*13;
                for(String line:row.role()){gui.c.textC(GuiBase.MED,line,x+14,textY,9,Theme.TEXT_2);textY+=13;}
                if(row.summary().visibleLines()>0)drawFoldedText(gui,row.summary(),x+14,textY+3,top,top+view,8.5f,12,Theme.TEXT_3);
                if(hasMessages(state,task.id()))gui.button("building.workshop.thread."+task.id(),Lang.t("ui.building.workshop.viewMessages"),null,
                        x+width-118,rowY+row.height()-29,99,22,false,()->openMessages(gui,conversationOwner(task.id())));
            }
        }finally{gui.popHitClip();gui.c.unclip();}
        gui.panelScrollBar("building.workshop.scroll",progressScroll,x+width-3,top,view);
    }
    private void measure(GuiBase gui,Snapshot state,float width){
        long language=Lang.revision();
        if(measured==state&&measuredWidth==width&&measuredScale==gui.c.scale&&languageRevision==language)return;
        measured=state;measuredWidth=width;measuredScale=gui.c.scale;languageRevision=language;
        List<Row> next=new ArrayList<>();float offset=0;
        for(Task task:state.tasks()){
            List<String> name=gui.c.wrap(GuiBase.MED,taskTitle(task),9,Math.max(24,width-155));
            List<String> role=gui.c.wrap(GuiBase.MED,taskRole(task.role()),9,width-38);
            var summary=new FoldedText(gui.c.wrap(GuiBase.REG,publicText(task.summary()),8.5f,width-38),expandedTasks.contains(task.id()));
            float height=40+Math.max(0,name.size()-1)*13+role.size()*13
                    +(summary.visibleLines()==0?0:3+summary.visibleLines()*12)+(hasMessages(state,task.id())?28:0);
            next.add(new Row(task,name,role,summary,offset,height));offset+=height+7;
        }
        rows=List.copyOf(next);taskHeight=offset;
    }
    private static boolean hasMessages(Snapshot state,String task){
        return state.messages().stream().anyMatch(message->inThread(conversationOwner(task),message));
    }
    private static String conversationOwner(String id){
        if(id.equals("architect")||id.startsWith("chief-"))return "chief";
        int agent=id.indexOf("-agent-");
        return id.startsWith("followup-")&&agent>=0?id.substring(agent+1):id;
    }
    private static boolean inThread(String task,Message message){
        return task.isEmpty()||task.equals(conversationOwner(message.from()))||task.equals(conversationOwner(message.to()));
    }
    private void openMessages(GuiBase gui,String task){
        messagesOpen=true;conversationTask=task;measuredMessages=null;
        messageScroll.target=0;messageScroll.value.snap(0);gui.focused=null;
    }
    private static String participant(Snapshot state,String id){
        if(id==null||id.isBlank())return Lang.t("ui.building.workshop.participant.system");
        for(Task task:state.tasks())if(id.equals(task.id()))return taskTitle(task);
        if(id.equals("all"))return Lang.t("ui.building.workshop.participant.all");
        if(id.equals("system"))return Lang.t("ui.building.workshop.participant.system");
        if(id.equals("chief"))return Lang.t("ui.building.workshop.task.chief");
        return id;
    }
    private void drawMessages(GuiBase gui,float x,float y,float width,float height,Snapshot state){
        if(height<45)return;
        String heading=conversationTask.isEmpty()?Lang.t("ui.building.workshop.conversation.all")
                :Lang.t("ui.building.workshop.conversation.task",participant(state,conversationTask));
        float headingY=y+11;
        for(String line:gui.c.wrap(GuiBase.MED,heading,10,width-8)){
            gui.c.textC(GuiBase.MED,line,x+3,headingY,10,Theme.TEXT);headingY+=14;
        }
        headingY+=4;
        for(String line:gui.c.wrap(GuiBase.REG,Lang.t("ui.building.workshop.conversation.hint"),8,width-8)){
            gui.c.textC(GuiBase.REG,line,x+3,headingY,8,Theme.TEXT_3);headingY+=12;
        }
        float top=headingY+4,view=y+height-top;
        if(view<15)return;
        measureMessages(gui,state,width);
        messageScroll.max=Math.max(0,messageHeight-view);messageScroll.update(gui.dt);
        gui.hit(x,top,width,view).scroll(messageScroll::scroll);
        gui.c.clip(x,top,width,view);gui.pushHitClip(x,top,width,view);
        try{
            if(messageRows.isEmpty())gui.c.textC(GuiBase.REG,Lang.t("ui.building.workshop.conversation.empty"),x+10,top+22,9,Theme.TEXT_3);
            for(MessageRow row:messageRows){
                float rowY=top+row.offset()-messageScroll.get();
                if(rowY+row.height()<top)continue;if(rowY>top+view)break;
                Message message=row.message();
                int color=message.stage().equals("decision")?0xffbda0ff:message.stage().equals("feedback")?0xff9fcbff:0xffa2dac7;
                gui.c.rrect(x+1,rowY,width-9,row.height(),12,0x15ffffff);
                gui.c.circle(x+14,rowY+17,2.4f,color);
                for(int i=0;i<row.route().size();i++)gui.c.textC(GuiBase.MED,row.route().get(i),x+25,rowY+17+i*13,9,Theme.TEXT);
                float stageX=x+width-(row.body().expandable()?46:20);
                gui.c.textRight(GuiBase.REG,Lang.t("ui.building.workshop.message."+message.stage()),stageX,rowY+17,8,color);
                if(row.body().expandable()){
                    gui.iconButton("building.workshop.messageFold."+message.id(),foldIcon(row.body()),x+width-23,rowY+17,12,Theme.TEXT_2,
                            ()->{toggle(expandedMessages,message.id());measuredMessages=null;});
                }
                float lineY=rowY+38+Math.max(0,row.route().size()-1)*13;
                drawFoldedText(gui,row.body(),x+14,lineY,top,top+view,9,13,Theme.TEXT_2);
            }
        }finally{gui.popHitClip();gui.c.unclip();}
        gui.panelScrollBar("building.workshop.messages.scroll",messageScroll,x+width-3,top,view);
    }
    private void measureMessages(GuiBase gui,Snapshot state,float width){
        long language=Lang.revision();
        if(measuredMessages==state&&measuredConversation.equals(conversationTask)&&messageWidth==width&&messageScale==gui.c.scale&&messageLanguage==language)return;
        measuredMessages=state;measuredConversation=conversationTask;messageWidth=width;messageScale=gui.c.scale;messageLanguage=language;
        List<MessageRow> next=new ArrayList<>();float offset=0;
        for(Message message:state.messages()){
            if(!inThread(conversationTask,message))continue;
            String route=participant(state,message.from())+"  →  "+participant(state,message.to());
            List<String> routeLines=gui.c.wrap(GuiBase.MED,route,9,Math.max(24,width-155));
            var body=new FoldedText(gui.c.wrap(GuiBase.REG,publicText(message.content()),9,width-38),expandedMessages.contains(message.id()));
            float height=45+Math.max(0,routeLines.size()-1)*13+Math.max(1,body.visibleLines())*13;
            next.add(new MessageRow(message,routeLines,body,offset,height));offset+=height+8;
        }
        messageRows=List.copyOf(next);messageHeight=offset;
    }
    private static Icon foldIcon(FoldedText text){return text.expanded()?Icon.CHEVRON_UP:Icon.CHEVRON_DOWN;}
    private static void toggle(Set<String> expanded,String id){if(!expanded.add(id))expanded.remove(id);}
    private static void drawFoldedText(GuiBase gui,FoldedText text,float x,float y,float top,float bottom,float size,float step,int color){
        int start=Math.max(0,(int)Math.floor((top-y-size)/step));
        int end=Math.min(text.visibleLines(),(int)Math.ceil((bottom-y+size)/step)+1);
        for(int i=start;i<end;i++)gui.c.textC(GuiBase.REG,text.lines().get(i),x,y+i*step,size,color);
    }
    private void select(Tier value,GuiBase gui){
        if(tier==value)return;tier=value;agentCount.setText(Integer.toString(counts.get(tier)));gui.focused=null;persist();
    }
    private void setAgents(int value){counts.put(tier,Math.max(1,Math.min(64,value)));agentCount.setText(Integer.toString(counts.get(tier)));persist();}
    private static String label(Tier tier){return switch(tier){case LOW->"Low";case MEDIUM->"Medium";case HIGH->"High";case XHIGH->"XHigh";case MAX->"Max";case ULTRA->"Ultra";};}
    private static String number(long value){return value<0?"—":String.format(Locale.ROOT,"%,d",value);}
    private static String taskTitle(Task task){
        if(task.id().equals("architect"))return Lang.t("ui.building.workshop.task.architect");
        if(task.id().equals("chief"))return Lang.t("ui.building.workshop.task.chief");
        if(task.id().startsWith("chief-"))return Lang.t("ui.building.workshop.task.chiefRound",task.id().substring(6));
        if(task.id().startsWith("followup-"))return Lang.t("ui.building.workshop.task.followup",conversationOwner(task.id()).substring(6));
        if(task.id().equals("validation"))return Lang.t("ui.building.workshop.task.validation");
        if(task.id().matches("agent-[0-9]+"))return Lang.t("ui.building.workshop.task.agent",task.id().substring(6));
        if(task.id().matches("review-[0-9]+"))return Lang.t("ui.building.workshop.task.review",task.id().substring(7));
        return task.title()==null?task.id():task.title();
    }
    private static String taskRole(String role){
        if(role==null)return "";
        String value=switch(role){
            case "Planning"->"planning";case "Detail design"->"detail";case "Validation"->"validation";
            case "Chief design coordination"->"coordination";
            case "Independent inspection"->"inspection";
            case "Structure, entrances, stairs and circulation"->"structure";
            case "Materials, furnishings, lighting, landscape and architectural detail"->"detailReview";
            case "Independent final requirements and consistency audit"->"finalReview";
            default->"";
        };
        if(!value.isEmpty())return Lang.t("ui.building.workshop.role."+value);
        int colon=role.indexOf(':');
        if(role.startsWith("Region ")&&colon>0){
            String suffix=role.substring(colon+1).strip();
            String key=suffix.startsWith("roofline")?"upper":suffix.startsWith("foundations")?"lower":"middle";
            return Lang.t("ui.building.workshop.region")+" "+role.substring(7,colon)+" · "+Lang.t("ui.building.workshop.role."+key);
        }
        return role;
    }
    private static String publicText(String text){
        if(text==null)return "";
        if(text.startsWith("ui.building.ai.")||text.startsWith("ui.ai.images."))return Lang.t(text);
        String key=switch(text){
            case "Creating the shared architectural plan."->"planning";
            case "Independent detail agents are developing assigned regions."->"details";
            case "Merging non-overlapping detail regions and validating block data."->"merging";
            case "Checking the complete result before preview."->"validation";
            case "The complete building is ready for preview."->"complete";
            case "The chief architect is comparing proposals and resolving design feedback."->"coordination";
            case "Specialists are responding to design feedback."->"followup";
            case "A validated building is ready; some optional agent requests failed and are listed in the task report."->"completeWarnings";
            case "Generation was cancelled; incomplete work was not applied."->"cancelled";
            case "Generation did not pass all required tasks; the previous preview is preserved."->"failed";
            case "The model request did not complete."->"requestFailed";
            case "Structured output did not pass validation."->"invalid";
            default->"";
        };
        if(!key.isEmpty())return Lang.t("ui.building.workshop.report."+key);
        var review=java.util.regex.Pattern.compile("Verification pass ([0-9]+) of ([0-9]+)\\.").matcher(text);
        if(review.matches())return Lang.t("ui.building.workshop.report.review",review.group(1),review.group(2));
        var retry=java.util.regex.Pattern.compile("Retrying or repairing structured output, attempt ([0-9]+)\\.").matcher(text);
        if(retry.matches())return Lang.t("ui.building.workshop.report.retry",retry.group(1));
        return text.replace(" fill operations · "," "+Lang.t("ui.building.workshop.fillOperations")+" · ")
                .replace(" repair operations · "," "+Lang.t("ui.building.workshop.repairOperations")+" · ")
                .replace(" validated fill operations."," "+Lang.t("ui.building.workshop.validatedOperations"))
                .replace("Base geometry and circulation validated.",Lang.t("ui.building.workshop.report.baseValidated"))
                .replace("Assigned region inspected.",Lang.t("ui.building.workshop.report.inspected"))
                .replace("Assigned geometry processed.",Lang.t("ui.building.workshop.report.processed"))
                .replace("Complete design reviewed.",Lang.t("ui.building.workshop.report.reviewed"));
    }
    private static int tierColor(Tier value){return switch(value){case LOW->0xff95c5d9;case MEDIUM->0xff78daca;case HIGH->0xff78a9fa;case XHIGH->0xff9b9efa;case MAX->0xffbd94ff;case ULTRA->0xffe0b2ff;};}
    private static int stateColor(State value){return switch(value){case QUEUED->0xff9298a9;case RUNNING->0xffbd9cff;case COMPLETED->0xff76ddba;case FAILED->0xfffb8d9b;case CANCELLED->0xffbdac90;};}
    private static String phase(String phase){String key="ui.building.workshop.phase."+(phase==null?"idle":phase.toLowerCase(Locale.ROOT));String label=Lang.t(key);return label.equals(key)?phase:label;}
    private static void pixels(GuiBase gui,float x,float y,float width,float height,float opacity){
        BuildingEffortFlow.draw(gui.c::rrect,x,y,width,height,System.nanoTime()/1_000_000_000.0,opacity);
    }
    private void read(){
        synchronized(Config.persistenceLock()){
            if(!Config.persistenceAllowed())return;
            try{
                var path=Config.directory().resolve("ai-building-workshop.json");
                if(!Files.isRegularFile(path)||Files.size(path)>8192)return;
                JsonObject json=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
                if(json.has("tier"))tier=Tier.valueOf(json.get("tier").getAsString());
                if(json.has("agents"))for(var entry:json.getAsJsonObject("agents").entrySet()){
                    try{counts.put(Tier.valueOf(entry.getKey()),Math.max(1,Math.min(64,entry.getValue().getAsInt())));}catch(RuntimeException ignored){ }
                }
            }catch(Exception ignored){tier=Tier.MAX;}
        }
    }
    private void persist(){
        JsonObject data=new JsonObject(),agents=new JsonObject();data.addProperty("tier",tier.name());
        counts.forEach((key,value)->agents.addProperty(key.name(),value));data.add("agents",agents);
        synchronized(SAVE){
            if(save!=null)save.cancel(false);
            save=SAVE.schedule(()->{
                synchronized(Config.persistenceLock()){
                    if(!Config.persistenceAllowed())return;
                    try{
                        var path=Config.directory().resolve("ai-building-workshop.json");Files.createDirectories(path.getParent());
                        var temp=path.resolveSibling(path.getFileName()+".tmp");
                        Files.writeString(temp,data.toString(),StandardCharsets.UTF_8);
                        try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                        catch(java.nio.file.AtomicMoveNotSupportedException ignored){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
                    }catch(Exception ignored){ }
                }
            },250,TimeUnit.MILLISECONDS);
        }
    }
}
