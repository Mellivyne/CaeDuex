package com.cheatprogram.ai;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Exercise the background job across observer detach/reopen and explicit cancellation. */
public final class BuildingDesignJobCheck {
    private static void require(boolean condition,String failure){if(!condition)throw new AssertionError(failure);}

    public static void main(String[] args){
        var job=new BuildingDesignJob(()->true);
        var response=new CompletableFuture<AiBuildingDesigner.Design>();
        var current=new AtomicReference<BooleanSupplier>();
        var report=new AtomicReference<Consumer<BuildingDesignWorkshop.Snapshot>>();
        var options=BuildingDesignWorkshop.Options.defaults(BuildingDesignWorkshop.Tier.ULTRA);
        var message=new BuildingDesignWorkshop.Message("message-1","chief","agent-1","feedback","Keep the arch open");
        require(job.start("A courtyard",options,true,(live,progress)->{
            current.set(live);report.set(progress);return response;
        }),"could not start");
        var progress=new BuildingDesignWorkshop.Snapshot("details",List.of(),2,5,
                new BuildingDesignWorkshop.Usage(5,7,12,true),"Working",List.of(message));
        report.get().accept(progress);

        // There is deliberately no screen or active observer here.
        var reopened=job.state();
        require(reopened.running()&&current.get().getAsBoolean()&&reopened.options()==options,"detaching the UI stopped the job");
        require(reopened.progress().completed()==2&&reopened.progress().messages().equals(List.of(message)),"reopened UI lost progress");
        require(!job.start("Other",options,true,(live,sink)->new CompletableFuture<>()),"duplicate generation accepted");

        report.get().accept(new BuildingDesignWorkshop.Snapshot("complete_warnings",List.of(),4,5,progress.usage(),"Ready",List.of(message)));
        var validated=new AiBuildingDesigner.Design(null,new JsonObject());
        response.complete(validated);
        require(!job.state().running()&&job.state().status().endsWith("readyWithWarnings"),"partial result reported incorrectly");
        require(job.takePreview()==validated&&job.takePreview()==null,"completed result not delivered exactly once");

        var late=new CompletableFuture<AiBuildingDesigner.Design>();
        job.start("Next",options,true,(live,sink)->{current.set(live);report.set(sink);return late;});
        report.get().accept(progress);
        job.cancel();
        require(!current.get().getAsBoolean()&&job.state().progress().phase().equals("cancelled"),"explicit cancellation did not stop work");
        require(job.state().progress().messages().equals(List.of(message)),"cancelled job lost exchange history");
        late.complete(new AiBuildingDesigner.Design(null,new JsonObject()));
        require(job.state().design()==validated&&job.takePreview()==null,"late cancelled result replaced prior preview");

        job.start("Denied",options,true,(live,sink)->CompletableFuture.failedFuture(
                new BuildingDesignWorkshop.RequestFailure("ui.building.ai.request.auth",401,false,0)));
        require(job.state().status().endsWith("request.auth")&&job.state().design()==validated,"sanitized error or previous result lost");
        System.out.println("BUILDING_JOB_PASS background lifetime, resumed progress, preview delivery, cancellation, sanitized errors");
    }
}
