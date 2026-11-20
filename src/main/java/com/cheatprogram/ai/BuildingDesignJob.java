package com.cheatprogram.ai;

import com.cheatprogram.core.Config;
import com.cheatprogram.util.BuildingFiles;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A client-session job whose lifetime is independent of the building screen. */
public final class BuildingDesignJob {
    public static final BuildingDesignJob INSTANCE=new BuildingDesignJob(()->Config.persistenceAllowed());
    private final BooleanSupplier allowed;
    private State state=new State(0,null,"",false,false,BuildingDesignWorkshop.Snapshot.idle(),"",null);
    private AiBuildingDesigner.Design pendingPreview;
    private JsonObject checkpointPlan;
    private Continuation continuation;
    private AiDesignExchange.Ticket exchange;

    public record State(long id,BuildingDesignWorkshop.Options options,String requirement,boolean running,boolean workshop,
                        BuildingDesignWorkshop.Snapshot progress,String status,AiBuildingDesigner.Design design) { }

    public record Continuation(String requirement, BuildingDesignWorkshop.Options options, JsonObject plan,
                               BuildingDesignWorkshop.Snapshot progress, String status, int imageCount) {
        public JsonObject progressJson() {
            Gson gson=new Gson();
            JsonObject result=gson.toJsonTree(progress).getAsJsonObject();
            result.add("options",gson.toJsonTree(options));
            result.addProperty("status",status);
            return result;
        }
    }

    @FunctionalInterface interface Runner {
        CompletableFuture<AiBuildingDesigner.Design> start(BooleanSupplier current,Consumer<BuildingDesignWorkshop.Snapshot> progress);
    }
    @FunctionalInterface interface CheckpointRunner {
        CompletableFuture<AiBuildingDesigner.Design> start(BooleanSupplier current,Consumer<BuildingDesignWorkshop.Snapshot> progress,
                                                            Consumer<JsonObject> checkpoint);
    }
    private int imageCount;

    BuildingDesignJob(BooleanSupplier allowed){this.allowed=allowed;}

    public synchronized State state(){return state;}
    public synchronized Continuation continuation(){return state.running()?null:continuation;}
    public synchronized AiDesignExchange.Ticket exchange(){return exchange;}
    public synchronized void exchange(AiDesignExchange.Ticket value){exchange=value;}

    public synchronized boolean generate(String requirement,List<AiImage> attachments,BuildingDesignWorkshop.Options options){
        if(requirement==null||requirement.isBlank())return false;
        var designer=new AiBuildingDesigner();
        JsonObject prior=state.design()==null?null:state.design().plan();
        if(continuation!=null&&continuation.requirement().equals(requirement)&&continuation.plan()!=null)
            prior=continuation.plan();
        JsonObject previous=prior;
        return start(requirement,options,true,attachments.size(),previous,(current,progress,checkpoint)->
                designer.generate(requirement,previous,attachments,options,current,progress,checkpoint));
    }

    public boolean importPlan(Supplier<JsonObject> source){
        return importPlan(state().requirement(),source);
    }

    public boolean importPlan(String requirement,Supplier<JsonObject> source){
        return start(requirement,null,false,(current,progress)->CompletableFuture.supplyAsync(()->{
            JsonObject plan=source.get();
            BuildingDesignWorkshop.validatePlan(plan,current);
            return new AiBuildingDesigner.Design(AiBuildingDesigner.decode(plan,current),plan);
        },BuildingFiles.IO));
    }

    boolean start(String requirement,BuildingDesignWorkshop.Options options,boolean workshop,Runner runner){
        return start(requirement,options,workshop,0,null,(current,progress,checkpoint)->runner.start(current,progress));
    }

    synchronized boolean start(String requirement,BuildingDesignWorkshop.Options options,boolean workshop,
                               int imageCount,JsonObject previous,CheckpointRunner runner){
        if(state.running()||!allowed.getAsBoolean())return false;
        long id=state.id()+1;
        if(workshop){
            checkpointPlan=previous;
            this.imageCount=imageCount;
        }
        state=new State(id,options,requirement,true,workshop,BuildingDesignWorkshop.Snapshot.idle(),
                workshop?"ui.building.ai.working":"ui.ai.exchange.validating",state.design());
        try{
            runner.start(()->current(id),progress->updateProgress(id,progress),plan->updateCheckpoint(id,plan))
                    .whenComplete((design,error)->finish(id,design,error));
        }catch(RuntimeException error){finish(id,null,error);}
        return true;
    }

    private synchronized boolean current(long id){return state.id()==id&&state.running()&&allowed.getAsBoolean();}

    private synchronized void updateCheckpoint(long id,JsonObject plan){
        if(current(id)&&state.workshop())checkpointPlan=plan;
    }

    private synchronized void updateProgress(long id,BuildingDesignWorkshop.Snapshot snapshot){
        if(state.id()!=id)return;
        if(state.running()){
            state=new State(id,state.options(),state.requirement(),true,true,snapshot,state.status(),state.design());
        }else if(state.progress().phase().equals(snapshot.phase())&&snapshot.usage().requests()>=state.progress().usage().requests()){
            // An in-flight request may return billable usage as a cancellation finishes.
            var previous=state.progress();
            var counted=new BuildingDesignWorkshop.Snapshot(previous.phase(),previous.tasks(),previous.completed(),previous.total(),
                    snapshot.usage(),previous.summary(),previous.messages());
            state=new State(id,state.options(),state.requirement(),false,state.workshop(),counted,state.status(),state.design());
        }
    }

    public synchronized void cancel(){
        if(state.running())finish(state.id(),null,new CancellationException());
    }

    private synchronized void finish(long id,AiBuildingDesigner.Design design,Throwable error){
        if(state.id()!=id||!state.running())return;
        if(!allowed.getAsBoolean()){
            state=new State(id,null,"",false,false,BuildingDesignWorkshop.Snapshot.idle(),"",null);
            pendingPreview=null;
            checkpointPlan=null;
            continuation=null;
            return;
        }
        var snapshot=state.progress();
        String status;
        if(error!=null||design==null){
            Throwable cause=error;
            while(cause instanceof java.util.concurrent.CompletionException&&cause.getCause()!=null)cause=cause.getCause();
            boolean cancelled=cause instanceof CancellationException;
            status=cancelled?"ui.building.ai.cancelled":failureKey(cause,state.workshop());
            if(state.workshop()&&!snapshot.phase().equals(cancelled?"cancelled":"failed")){
                var outcome=cancelled?BuildingDesignWorkshop.State.CANCELLED:BuildingDesignWorkshop.State.FAILED;
                var tasks=snapshot.tasks().stream().map(task->task.state()==BuildingDesignWorkshop.State.QUEUED
                        ||task.state()==BuildingDesignWorkshop.State.RUNNING
                        ||(!cancelled&&task.id().equals("validation"))
                        ?new BuildingDesignWorkshop.Task(task.id(),task.title(),task.role(),outcome,task.summary()):task).toList();
                int completed=(int)tasks.stream().filter(task->task.state()==BuildingDesignWorkshop.State.COMPLETED).count();
                snapshot=new BuildingDesignWorkshop.Snapshot(cancelled?"cancelled":"failed",tasks,completed,snapshot.total(),
                        snapshot.usage(),status,snapshot.messages());
            }
            design=state.design();
        }else{
            pendingPreview=design;
            status=!state.workshop()?"ui.ai.exchange.imported":snapshot.phase().equals("complete_warnings")
                    ?"ui.building.ai.readyWithWarnings":"ui.building.ai.ready";
        }
        if(state.workshop()){
            continuation=new Continuation(state.requirement(),state.options(),error==null&&design!=null?design.plan():checkpointPlan,
                    snapshot,status,imageCount);
            checkpointPlan=null;
        }else if(error==null&&design!=null){
            continuation=null;
        }
        state=new State(id,state.options(),state.requirement(),false,state.workshop(),snapshot,status,design);
    }

    public synchronized AiBuildingDesigner.Design takePreview(){
        var result=pendingPreview;
        pendingPreview=null;
        return result;
    }

    private static String failureKey(Throwable error,boolean workshop){
        if(error instanceof BuildingDesignWorkshop.RequestFailure failure)return failure.publicKey();
        String key=error==null?null:error.getMessage();
        if(key!=null&&key.matches("ui\\.(building\\.ai|ai\\.images|ai\\.exchange)\\.[a-zA-Z0-9.]+"))return key;
        return workshop?"ui.building.ai.failed":"ui.ai.exchange.invalid";
    }
}
