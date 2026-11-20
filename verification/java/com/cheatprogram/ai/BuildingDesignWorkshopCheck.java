package com.cheatprogram.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import com.cheatprogram.ai.BuildingDesignWorkshop.*;

/** Standalone deterministic checks; no game, credentials, model endpoint or paid request. */
public final class BuildingDesignWorkshopCheck {
    private static final Pattern REGION=Pattern.compile("authorized bounds are from \\[(\\d+), (\\d+), (\\d+)\\] to \\[(\\d+), (\\d+), (\\d+)\\]");
    private static final class Model implements Request {
        final AtomicInteger architectures=new AtomicInteger(),details=new AtomicInteger(),reviews=new AtomicInteger();
        final AtomicInteger chief=new AtomicInteger(),followups=new AtomicInteger(),peerReads=new AtomicInteger();
        final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),inspections=new AtomicInteger();
        final AtomicBoolean current=new AtomicBoolean(true);
        boolean repairArchitect,failDetail,cancelDetail,missingUsage,tiny,collaborate;
        public String ask(String system,String prompt,List<AiImage> images,java.util.function.BooleanSupplier permitted,java.util.function.Consumer<Usage> usage)throws Exception {
            require(!system.toLowerCase().contains("chain of thought"),"reasoning transcript requested");
            if(!missingUsage)usage.accept(new Usage(10,20,30,true));
            if(prompt.contains("COMPLETE base building plan")){
                if(architectures.incrementAndGet()==1&&repairArchitect)return "not JSON";
                return base(tiny?1:12,tiny?1:8,tiny?1:10).toString();
            }
            if(prompt.contains("independent verification pass")){
                reviews.incrementAndGet();
                return patch(new int[]{0,0,0},false).toString();
            }
            if(prompt.contains("CHIEF COORDINATION")){
                JsonObject decision=patch(new int[]{0,0,0},true);
                if(chief.incrementAndGet()==1&&collaborate){
                    require(prompt.contains("Peer proposal for the roof edge"),"chief did not see specialist messages");
                    addMessage(decision,"agent-1","Chief asks for a compatible roof edge");
                }
                return decision.toString();
            }
            int running=active.incrementAndGet();peak.accumulateAndGet(running,Math::max);
            try{
                boolean reply=prompt.contains("replying to public design feedback");
                if(reply){
                    followups.incrementAndGet();
                    require(prompt.contains("Chief asks for a compatible roof edge")||prompt.contains("Peer proposal for the roof edge"),"reply lacked the directed message");
                }else{
                    details.incrementAndGet();
                    if(prompt.contains("Peer proposal for the roof edge"))peerReads.incrementAndGet();
                }
                Thread.sleep(3);
                if(cancelDetail){current.set(false);return "{}";}
                boolean inspection=prompt.contains("You are inspection-only.");
                if(inspection)inspections.incrementAndGet();
                var match=REGION.matcher(prompt);require(match.find(),"assignment bounds missing");
                int[] low={Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3))};
                if(failDetail)low[0]=-1;
                JsonObject result=patch(low,inspection);
                if(collaborate&&prompt.contains("independent agent 1 of"))addMessage(result,"agent-2","Peer proposal for the roof edge");
                if(reply)addMessage(result,"chief","Roof edge response is ready");
                return result.toString();
            }finally{active.decrementAndGet();}
        }
    }
    private static void addMessage(JsonObject result,String to,String content){
        var message=new JsonObject();message.addProperty("to",to);message.addProperty("content",content);
        JsonArray messages=new JsonArray();messages.add(message);result.add("messages",messages);
    }
    private static JsonObject base(int x,int y,int z){
        JsonObject plan=JsonParser.parseString("{\"name\":\"Fixture building\",\"palette\":[{\"Name\":\"minecraft:air\"},{\"Name\":\"minecraft:stone_bricks\"}],\"fills\":[{\"from\":[0,0,0],\"to\":[0,0,0],\"block\":1}],\"summary\":\"Established the base building.\"}").getAsJsonObject();
        JsonArray size=new JsonArray();size.add(x);size.add(y);size.add(z);plan.add("size",size);return plan;
    }
    private static JsonObject patch(int[] location,boolean empty){
        JsonObject result=JsonParser.parseString("{\"summary\":\"Added a real glass detail in the assigned region.\",\"palette\":[{\"Name\":\"minecraft:air\"},{\"Name\":\"minecraft:glass\"}],\"fills\":[]}").getAsJsonObject();
        if(!empty){JsonObject fill=new JsonObject();JsonArray position=new JsonArray();for(int value:location)position.add(value);fill.add("from",position);fill.add("to",position.deepCopy());fill.addProperty("block",1);result.getAsJsonArray("fills").add(fill);result.getAsJsonArray("fills").add(fill.deepCopy());}
        return result;
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static void ultra()throws Exception {
        Model model=new Model();model.collaborate=true;AtomicInteger validations=new AtomicInteger();
        List<Snapshot> events=Collections.synchronizedList(new ArrayList<>());
        AtomicInteger observers=new AtomicInteger(),observerPeak=new AtomicInteger();
        JsonObject built=new BuildingDesignWorkshop(model,(plan,current)->validations.incrementAndGet()).run("A detailed home",null,List.of(),"fixture",Options.defaults(Tier.ULTRA),model.current::get,event->{
            observerPeak.accumulateAndGet(observers.incrementAndGet(),Math::max);
            try{Thread.sleep(1);events.add(event);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally{observers.decrementAndGet();}
        });
        require(model.architectures.get()==1&&model.details.get()==30&&model.reviews.get()==3,"Ultra must make real independent requests");
        require(model.peak.get()<=3&&model.peak.get()>1,"bounded concurrent calls");
        require(model.chief.get()==4&&model.followups.get()==2&&model.peerReads.get()>0,"bidirectional model communication is missing");
        require(observerPeak.get()==1,"concurrent progress callbacks can reorder UI snapshots");
        require(validations.get()>=35,"validation callback not used across stages");
        require(built.getAsJsonArray("palette").size()==3,"patch palette was not deduplicated");
        for(var value:built.getAsJsonArray("fills")){int block=value.getAsJsonObject().get("block").getAsInt();require(block>=0&&block<3,"bad remapped palette index");}
        BuildingDesignWorkshop.validatePlan(built,()->true);
        Snapshot done=events.getLast();require(done.phase().equals("complete")&&done.completed()==done.total(),"unfinished tasks reported complete");
        require(done.tasks().stream().filter(task->task.id().startsWith("agent-")).count()==30,"agent task reports missing");
        require(done.usage().requests()==40&&done.usage().totalTokens()==1200&&done.usage().reported()&&done.usage().unknownRequests()==0,"actual usage aggregation");
        require(done.messages().stream().anyMatch(m->m.from().equals("agent-2")&&m.content().contains("Roof edge response")),"public response not retained");
        require(Options.defaults().tier()==Tier.MAX&&Options.defaults(Tier.ULTRA).agents()==30,"defaults");
    }
    private static void repairsAndUnknownUsage()throws Exception {
        Model model=new Model();model.repairArchitect=true;model.missingUsage=true;
        List<Snapshot> events=new ArrayList<>();
        new BuildingDesignWorkshop(model).run("A home",null,List.of(),"fixture",new Options(Tier.MEDIUM,2),model.current::get,events::add);
        Snapshot done=events.getLast();require(model.architectures.get()==2,"malformed output not repaired");
        require(!done.usage().reported()&&done.usage().totalTokens()==-1&&done.usage().unknownRequests()==7,"missing usage must stay unknown");
        require(events.stream().anyMatch(event->event.tasks().stream().anyMatch(task->task.summary().contains("attempt 2"))),"repair progress missing");
    }
    private static void boundsAndFailure()throws Exception {
        Model model=new Model();model.failDetail=true;List<Snapshot> events=new ArrayList<>();
        JsonObject previous=base(4,4,4);String original=previous.toString();
        JsonObject candidate=new BuildingDesignWorkshop(model).run("A home",previous,List.of(),"fixture",new Options(Tier.LOW,1),model.current::get,events::add);
        require(events.getLast().phase().equals("complete_warnings"),"invalid coordinate silently accepted");
        require(candidate.getAsJsonArray("fills").size()==1,"invalid patch altered the base");
        require(previous.toString().equals(original),"failed work mutated previous preview");
        require(events.getLast().tasks().stream().anyMatch(task->task.state()==State.FAILED),"failed agent hidden");
    }
    private static void retryAndContinue()throws Exception {
        Model model=new Model();AtomicInteger attempts=new AtomicInteger();List<Snapshot> events=new ArrayList<>();
        Request server=(system,prompt,images,current,usage)->{
            if(prompt.contains("independent agent 1 of")&&attempts.incrementAndGet()==1){
                usage.accept(new Usage(-1,-1,-1,false));
                throw new RequestFailure("ui.building.ai.request.rateLimit",429,true,1);
            }
            return model.ask(system,prompt,images,current,usage);
        };
        new BuildingDesignWorkshop(server).run("A home",null,List.of(),"fixture",new Options(Tier.LOW,1),model.current::get,events::add);
        Snapshot done=events.getLast();
        require(attempts.get()==2&&done.phase().equals("complete")&&done.usage().unknownRequests()==1,"transient failure did not recover");
    }
    private static void cancellation()throws Exception {
        Model model=new Model();model.cancelDetail=true;List<Snapshot> events=new ArrayList<>();boolean cancelled=false;
        try{new BuildingDesignWorkshop(model).run("A home",null,List.of(),"fixture",Options.defaults(Tier.ULTRA),model.current::get,events::add);}catch(CancellationException expected){cancelled=true;}
        require(cancelled&&events.getLast().phase().equals("cancelled"),"cancelled work applied");
        require(events.getLast().tasks().stream().noneMatch(task->task.state()==State.QUEUED||task.state()==State.RUNNING),"unfinished work remained running");
    }
    private static void tinyDesign()throws Exception {
        Model model=new Model();model.tiny=true;
        new BuildingDesignWorkshop(model).run("One block",null,List.of(),"fixture",new Options(Tier.LOW,5),model.current::get,event->{});
        require(model.details.get()==5&&model.inspections.get()==4,"tiny-plan roles must be honest independent inspections");
    }
    private static void terminalRace()throws Exception {
        AtomicBoolean current=new AtomicBoolean(true);AtomicInteger detail=new AtomicInteger();
        CountDownLatch secondStarted=new CountDownLatch(1),terminal=new CountDownLatch(1),lateFinished=new CountDownLatch(1);
        List<Snapshot> events=Collections.synchronizedList(new ArrayList<>());
        Request model=(system,prompt,images,permitted,usage)->{
            usage.accept(new Usage(1,1,2,true));
            if(prompt.contains("COMPLETE base building plan"))return base(12,8,10).toString();
            if(detail.incrementAndGet()==1){
                require(secondStarted.await(2,TimeUnit.SECONDS),"second request never overlapped");
                current.set(false);return "{}";
            }
            secondStarted.countDown();
            try{
                try{terminal.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){ }
                throw new java.io.IOException("late transport failure after cancellation");
            }finally{lateFinished.countDown();}
        };
        boolean cancelled=false;
        try{new BuildingDesignWorkshop(model).run("A home",null,List.of(),"fixture",new Options(Tier.ULTRA,2),current::get,event->{
            events.add(event);if(event.phase().equals("cancelled"))terminal.countDown();
        });}catch(CancellationException expected){cancelled=true;}
        require(cancelled&&lateFinished.await(2,TimeUnit.SECONDS),"cancel/late-failure fixture did not finish");
        Thread.sleep(30);
        boolean terminalSeen=false;
        synchronized(events){for(Snapshot event:events){
            if(event.phase().equals("cancelled"))terminalSeen=true;
            if(terminalSeen){require(event.phase().equals("cancelled"),"terminal phase regressed");
                require(event.tasks().stream().noneMatch(task->task.state()==State.RUNNING||task.state()==State.QUEUED||task.state()==State.FAILED),"late failure replaced cancelled task state");}
        }}
        require(terminalSeen,"terminal cancellation snapshot missing");
    }
    private static void orderedOverwrites(){
        JsonObject original=base(4,4,4);JsonObject glass=patch(new int[]{0,0,0},false);
        JsonObject cut=patch(new int[]{0,0,0},false);cut.getAsJsonArray("fills").get(0).getAsJsonObject().addProperty("block",0);cut.getAsJsonArray("fills").get(1).getAsJsonObject().addProperty("block",0);
        JsonObject result=BuildingDesignWorkshop.merge(BuildingDesignWorkshop.merge(BuildingDesignWorkshop.merge(original,glass),cut),glass);
        JsonArray fills=result.getAsJsonArray("fills");require(fills.size()==4,"consecutive dedup must preserve nonconsecutive overwrites");
        require(fills.get(2).getAsJsonObject().get("block").getAsInt()==0&&fills.get(3).getAsJsonObject().get("block").getAsInt()==2,"ordered air cuts lost");
    }
    public static void main(String[] args)throws Exception {
        ultra();repairsAndUnknownUsage();boundsAndFailure();retryAndContinue();cancellation();tinyDesign();orderedOverwrites();terminalRace();
        System.out.println("BUILDING_WORKSHOP_PASS actual 30-agent workflow, bounded concurrency, registry-validation callbacks, repeated reviews, usage, repair, cancellation, bounds, palette remap, overwrite order, tiny-design inspection");
    }
}
