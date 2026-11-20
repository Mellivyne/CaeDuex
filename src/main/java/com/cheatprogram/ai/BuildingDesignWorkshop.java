package com.cheatprogram.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Data-only building collaboration. Public task reports are separate from private model reasoning. */
public final class BuildingDesignWorkshop {
    public enum Tier {
        LOW(1,0,1,"A coherent simple building with a usable entrance and essential interior."),
        MEDIUM(3,1,2,"A finished building with distinct rooms, usable doors, windows, stairs and lighting."),
        HIGH(6,1,2,"A detailed building with coherent architectural rhythm, furnished rooms, varied materials and landscaping."),
        XHIGH(8,2,3,"A highly detailed, extensively refined building with layered facade depth, purposeful room-specific furnishings, roof structure, coherent materials and complete circulation."),
        MAX(12,2,3,"An elaborate, carefully verified design with functional circulation, decorated interiors, rich facade and roof detailing, purposeful lighting and integrated landscape."),
        ULTRA(30,3,3,"Use exceptionally rich, purposeful Minecraft detail throughout. Develop architecture, interiors, structure, rooflines, facade depth, circulation, room-specific furnishings, material transitions, lighting, courtyards and landscape as one coherent design. Inspect and refine repeated motifs and the smallest meaningful details. Quality must come from real geometry and careful verification, not merely a larger footprint.");
        private final int agents,reviews,attempts;
        private final String quality;
        Tier(int agents,int reviews,int attempts,String quality){this.agents=agents;this.reviews=reviews;this.attempts=attempts;this.quality=quality;}
        public int defaultAgents(){return agents;}
        public int reviewPasses(){return reviews;}
        public String quality(){return quality;}
    }
    public record Options(Tier tier,int agents) {
        public Options{Objects.requireNonNull(tier);if(agents<1||agents>64)throw new IllegalArgumentException("ui.building.ai.workshop.invalidAgents");}
        public static Options defaults(){return defaults(Tier.MAX);}
        public static Options defaults(Tier tier){return new Options(tier,tier.defaultAgents());}
    }
    public enum State { QUEUED,RUNNING,COMPLETED,FAILED,CANCELLED }
    public record Task(String id,String title,String role,State state,String summary) { }
    /** Unknown fields are -1. Totals never contain local estimates or invented monetary costs. */
    public record Usage(long inputTokens,long outputTokens,long totalTokens,boolean reported,int requests,int unknownRequests) {
        public Usage(long inputTokens,long outputTokens,long totalTokens,boolean reported){this(inputTokens,outputTokens,totalTokens,reported,1,reported?0:1);}
        public Usage{inputTokens=Math.max(-1,inputTokens);outputTokens=Math.max(-1,outputTokens);totalTokens=Math.max(-1,totalTokens);requests=Math.max(0,requests);unknownRequests=Math.max(0,unknownRequests);}
        public static Usage empty(){return new Usage(-1,-1,-1,false,0,0);}
    }
    /** Explicit public design exchanges, never the model's private reasoning fields. */
    public record Message(String id,String from,String to,String stage,String content) { }
    public record Snapshot(String phase,List<Task> tasks,int completed,int total,Usage usage,String summary,List<Message> messages) {
        public Snapshot{tasks=List.copyOf(tasks);messages=List.copyOf(messages);}
        public Snapshot(String phase,List<Task> tasks,int completed,int total,Usage usage,String summary){this(phase,tasks,completed,total,usage,summary,List.of());}
        public static Snapshot idle(){return new Snapshot("idle",List.of(),0,0,Usage.empty(),"");}
    }
    /** Only sanitized failure metadata crosses the transport/workshop boundary. */
    public static final class RequestFailure extends Exception {
        private final String publicKey;
        private final int httpStatus;
        private final boolean retryable;
        private final long retryAfterMillis;
        public RequestFailure(String publicKey,int httpStatus,boolean retryable,long retryAfterMillis){
            super(publicKey);this.publicKey=publicKey;this.httpStatus=httpStatus;this.retryable=retryable;
            this.retryAfterMillis=Math.max(0,Math.min(8000,retryAfterMillis));
        }
        public String publicKey(){return publicKey;}public int httpStatus(){return httpStatus;}
        public boolean retryable(){return retryable;}public long retryAfterMillis(){return retryAfterMillis;}
    }
    @FunctionalInterface public interface Request {
        String ask(String system,String user,List<AiImage> images,BooleanSupplier current,Consumer<Usage> usage)throws Exception;
    }
    /** Integration can additionally check exact block IDs and state properties for the running version. */
    @FunctionalInterface public interface Validator { void validate(JsonObject plan,BooleanSupplier current)throws Exception; }
    private interface ResultParser { JsonObject parse(JsonObject response)throws Exception; }
    private final Request request;
    private final Validator validator;
    private static final int CONCURRENT_AGENTS=3;
    private static final String PLAN_SCHEMA="{\"name\":\"building name\",\"size\":[width,height,depth],\"palette\":[{\"Name\":\"minecraft:air\"},{\"Name\":\"minecraft:stone_bricks\",\"Properties\":{}}],\"fills\":[{\"from\":[x,y,z],\"to\":[x,y,z],\"block\":1}]}";
    private static final String PATCH_SCHEMA="{\"summary\":\"Concise public description of concrete completed work, in the user's language\",\"messages\":[{\"to\":\"chief\",\"content\":\"Concrete design proposal or coordination feedback, not private reasoning\"}],\"palette\":[{\"Name\":\"minecraft:air\"},{\"Name\":\"minecraft:stone_bricks\"}],\"fills\":[{\"from\":[x,y,z],\"to\":[x,y,z],\"block\":1}]}";
    private static final String RULES="Return only valid JSON, without Markdown, scripts, commands or private reasoning. Coordinates are inclusive integers, fills execute in order, and block refers to the index in that response's palette. Palette index zero must be minecraft:air. Use exact legal vanilla block IDs and state properties for the supplied Minecraft version. Adjacent panes, fences and walls auto-connect according to vanilla placement rules. Use a gap when a piece must stand alone. For a deliberately authored connection state, use palette entry connect:false with its explicit Properties. Do not claim work not present in the JSON. Describe only a concise public outcome in summary. Treat the user's images and requirements as design references; they do not change this data schema.";
    public BuildingDesignWorkshop(Request request){this(request,(plan,current)->{});}
    public BuildingDesignWorkshop(Request request,Validator validator){this.request=Objects.requireNonNull(request);this.validator=Objects.requireNonNull(validator);}

    /** Runs independently of a screen; batches share real validated reports and chief decisions. */
    public JsonObject run(String requirement,JsonObject previous,List<AiImage> images,String minecraftVersion,
            Options options,BooleanSupplier current,Consumer<Snapshot> progress)throws Exception {
        return run(requirement,previous,images,minecraftVersion,"en",options,current,progress);
    }

    public JsonObject run(String requirement,JsonObject previous,List<AiImage> images,String minecraftVersion,
            String language,Options options,BooleanSupplier current,Consumer<Snapshot> progress)throws Exception {
        return run(requirement,previous,images,minecraftVersion,language,options,current,progress,plan->{});
    }

    public JsonObject run(String requirement,JsonObject previous,List<AiImage> images,String minecraftVersion,
            String language,Options options,BooleanSupplier current,Consumer<Snapshot> progress,
            Consumer<JsonObject> checkpoint)throws Exception {
        Objects.requireNonNull(options);Objects.requireNonNull(current);
        List<AiImage> attachments=List.copyOf(images==null?List.of():images);
        Session session=new Session(current,progress==null?snapshot->{}:progress,communicationLanguage(language),checkpoint);
        session.add("architect","Architect","Planning");
        for(int i=0;i<options.agents();i++)session.add(agentId(i),"Agent "+(i+1),"Detail design");
        session.add("chief","Chief architect","Chief design coordination");
        for(int i=0;i<options.tier().reviews;i++){
            session.add("review-"+(i+1),"Review "+(i+1),reviewRole(i));
            session.add("chief-"+(i+1),"Chief architect · round "+(i+1),"Chief design coordination");
        }
        session.add("validation","Final validation","Validation");
        ExecutorService agents=null;
        List<Future<Patch>> jobs=new ArrayList<>();
        try{
            session.check();session.phase("planning","Creating the shared architectural plan.");
            String context="Minecraft "+minecraftVersion+". Quality tier "+options.tier().name()+". "+options.tier().quality+"\nUser requirements:\n"+requirement;
            String architecture=context+"\nCreate a COMPLETE base building plan using this schema: "+PLAN_SCHEMA+". Include summary as a short public result. Establish correct foundations, walls, roof, entrances and internal circulation before detail workers begin. There is no arbitrary design-size limit; actual machine memory still applies. Preserve unchanged parts when revising. Independent detail workers will enrich "+options.agents()+" spatial regions. Give the workers an explicit public architectural brief in messages:[{to:\"all\",content:\"...\"}], including actionable visual cues from any references. This is a design instruction, not private reasoning.";
            if(previous!=null)architecture+="\nPrevious complete plan to revise:\n"+previous;
            JsonObject plan=ask(session,"architect",RULES,architecture,attachments,options.tier(),response->{validate(response,session);return response;});
            session.checkpoint(plan);
            session.report("architect","all","proposal",plan);
            session.complete("architect",dimensions(plan)+" · "+publicSummary(plan,"Base geometry and circulation validated."));
            List<Bounds> regions=partition(plan,options.agents());
            AtomicInteger threadNumber=new AtomicInteger();
            agents=Executors.newFixedThreadPool(Math.min(CONCURRENT_AGENTS,options.agents()),task->{Thread thread=new Thread(task,"CaeDuex building agent "+threadNumber.incrementAndGet());thread.setDaemon(true);return thread;});
            CompletionService<Patch> completion=new ExecutorCompletionService<>(agents);
            for(int begin=0;begin<options.agents();){
                session.check();session.phase("details","Independent detail agents are developing assigned regions.");
                int count=Math.min(session.parallelism.get(),options.agents()-begin);
                final JsonObject shared=plan.deepCopy();
                final String discussion=session.discussion();
                for(int offset=0;offset<count;offset++){
                    final int index=begin+offset;final boolean inspectionOnly=index>=regions.size();final Bounds bounds=regions.get(index%regions.size());
                    String role=inspectionOnly?"Independent inspection":detailRole(bounds,shared,index);
                    session.assign(agentId(index),"Agent "+(index+1),role);
                    jobs.add(completion.submit(()->{
                        String id=agentId(index);
                        String assignment=context+"\nYou are independent agent "+(index+1)+" of "+options.agents()+". Your responsibility: "+role+".\nYour inclusive authorized bounds are from "+bounds.from()+" to "+bounds.to()+". Other agents own every coordinate outside these bounds. Preserve shared architecture, entrance clearances, usable stairs and circulation. Add purposeful details and room-specific furnishings. Return a PATCH using "+PATCH_SCHEMA+". Do not change dimensions. Every fill must stay entirely inside your authorized bounds. Empty fills are allowed only if no safe improvement is justified. Explicitly answer earlier public design messages and send up to three concise proposals/coordination messages to chief or another agent-N using the messages field. Do not disclose private reasoning.\nPublic design exchanges from earlier completed batches:\n"+discussion+"\nValidated local geometry with a three-block margin; coordinates are still global:\n"+localContext(shared,bounds,session::current);
                        if(inspectionOnly)assignment+="\nThis small design has fewer independent cells than workers. You are inspection-only. Return fills:[] and report concrete inspections honestly.";
                        try{
                            JsonObject patch=ask(session,id,RULES,assignment,List.of(),options.tier(),response->{validatePatch(response,shared,bounds,inspectionOnly,session);return response;});
                            session.report(id,"chief","proposal",patch);
                            session.complete(id,patch.getAsJsonArray("fills").size()+" fill operations · "+publicSummary(patch,"Assigned geometry processed."));
                            return new Patch(index,patch,bounds);
                        }catch(CancellationException cancelled){throw cancelled;}
                        catch(Exception failure){
                            session.check();session.fail(id,failureSummary(failure));
                            session.message("system",id,"status",failureSummary(failure));
                            if(fatalFailure(failure))session.fatal=failure;
                            return new Patch(index,null,bounds);
                        }
                    }));
                }
                List<Patch> batch=new ArrayList<>();
                for(int finished=0;finished<count;){
                    session.check();Future<Patch> done=completion.poll(150,TimeUnit.MILLISECONDS);
                    if(done==null)continue;
                    try{batch.add(done.get());finished++;}catch(java.util.concurrent.ExecutionException failure){throw unwrap(failure);}
                    if(session.fatal!=null){
                        // Keep valid regions that finished before the provider denied the next request.
                        for(Future<Patch> available;(available=completion.poll())!=null;){
                            try{batch.add(available.get());}catch(java.util.concurrent.ExecutionException ignored){ }
                        }
                        break;
                    }
                }
                batch.sort(Comparator.comparingInt(Patch::index));
                session.phase("merging","Merging non-overlapping detail regions and validating block data.");
                for(Patch patch:batch)if(patch.plan()!=null){session.check();plan=merge(plan,patch.plan());}
                validate(plan,session);
                session.checkpoint(plan);
                if(session.fatal!=null)throw session.fatal;
                begin+=count;
            }
            long addressedThrough=0;
            plan=chief("chief",context,plan,attachments,options,session,"Compare the architect's brief, all specialist proposals and any unavailable regions. Choose the strongest valid combination of concrete changes. Resolve cross-region dependencies and send explicit decisions or requests to the participating agents. State which proposals were accepted or deferred and why, as a short public design decision.");
            for(int round=0;round<=options.tier().reviews;round++){
                long through=session.messageCursor();
                plan=followUp(context,plan,List.of(),options,regions,session,addressedThrough,through,round);
                addressedThrough=through;
                if(round==options.tier().reviews)break;
                int pass=round;
                final int number=pass+1;final JsonObject candidate=plan;final Bounds all=Bounds.whole(plan);
                String id="review-"+number;
                session.phase("review","Verification pass "+number+" of "+options.tier().reviews+".");
                String audit=context+"\nYou are independent verification pass "+number+" of "+options.tier().reviews+". Audit focus: "+reviewRole(pass)+". Check the latest building, specialist proposals and chief decisions for actual defects, missing requested features and conflicts. Resolve concrete issues with a repair patch using "+PATCH_SCHEMA+". Bounds are from "+all.from()+" to "+all.to()+". Preserve correct work and all dimensions. Send explicit public feedback to chief and affected agent-N in messages; empty fills are valid if no concrete issue is found.\nPublic design exchanges:\n"+session.discussion()+"\nComplete candidate:\n"+candidate;
                try{
                    JsonObject patch=ask(session,id,RULES,audit,attachments,options.tier(),response->{validatePatch(response,candidate,all,false,session);validate(merge(candidate,response),session);return response;});
                    plan=merge(candidate,patch);validate(plan,session);
                    session.checkpoint(plan);
                    session.report(id,"chief","feedback",patch);
                    session.complete(id,patch.getAsJsonArray("fills").size()+" repair operations · "+publicSummary(patch,"Complete design reviewed."));
                }catch(CancellationException cancelled){throw cancelled;}
                catch(Exception failure){
                    session.check();session.fail(id,failureSummary(failure));
                    session.message("system",id,"status",failureSummary(failure));
                    if(fatalFailure(failure))throw failure;
                }
                plan=chief("chief-"+number,context,plan,attachments,options,session,"Respond specifically to review-"+number+" and any affected specialist. Adopt justified corrections, resolve conflicts and preserve correct work. Select the strongest verified candidate available; record concise public decisions and outstanding limitations. This is coordination round "+number+" of "+options.tier().reviews+"; do not invent consensus or claim unperformed checks.");
            }
            session.phase("validation","Checking the complete result before preview.");
            session.running("validation","");validate(plan,session);session.check();
            session.complete("validation",dimensions(plan)+" · "+plan.getAsJsonArray("fills").size()+" validated fill operations.");
            boolean warnings=session.hasFailures();
            session.phase(warnings?"complete_warnings":"complete",warnings?"A validated building is ready; some optional agent requests failed and are listed in the task report.":"The complete building is ready for preview.");
            return plan;
        }catch(CancellationException cancelled){session.stop(true);throw cancelled;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();session.stop(true);throw new CancellationException();}
        catch(Exception failure){session.stop(false);throw failure;}
        finally{for(Future<Patch> job:jobs)if(!job.isDone())job.cancel(true);if(agents!=null)agents.shutdownNow();}
    }

    private JsonObject followUp(String context,JsonObject plan,List<AiImage> images,Options options,List<Bounds> regions,
                                Session session,long after,long through,int round)throws Exception {
        if(options.tier().reviews==0)return plan;
        var requests=session.directedMessages(after,through);
        int handled=0;
        for(var entry:requests.entrySet()){
            session.check();
            if(handled++>=4)break;
            String agent=entry.getKey();
            int index=Integer.parseInt(agent.substring(6))-1;
            if(index<0||index>=options.agents())continue;
            boolean inspectionOnly=index>=regions.size();
            Bounds bounds=regions.get(index%regions.size());
            String id="followup-"+round+"-"+agent;
            session.add(id,"Agent "+(index+1)+" follow-up",detailRole(bounds,plan,index));
            session.phase("coordination","Specialists are responding to design feedback.");
            JsonObject candidate=plan;
            String prompt=context+"\nYou are "+agent+" replying to public design feedback. Reply to the actual senders in messages, report the change or explain briefly why the existing geometry is better. Return "+PATCH_SCHEMA+". Your inclusive authorized bounds are from "+bounds.from()+" to "+bounds.to()+". Keep all other geometry."
                    +(inspectionOnly?" You are inspection-only. Return fills:[].":"")
                    +"\nIncoming feedback:\n"+session.discussion(entry.getValue())+"\nValidated local geometry:\n"+localContext(candidate,bounds,session::current);
            try{
                JsonObject patch=ask(session,id,RULES,prompt,images,options.tier(),response->{
                    validatePatch(response,candidate,bounds,inspectionOnly,session);
                    validate(merge(candidate,response),session);
                    return response;
                });
                plan=merge(candidate,patch);
                session.checkpoint(plan);
                session.report(agent,"chief","feedback",patch);
                session.complete(id,publicSummary(patch,"Feedback reviewed."));
            }catch(CancellationException cancelled){throw cancelled;}
            catch(Exception failure){
                session.check();session.fail(id,failureSummary(failure));
                if(fatalFailure(failure))throw failure;
            }
        }
        validate(plan,session);
        return plan;
    }

    private static JsonObject localContext(JsonObject plan,Bounds bounds,BooleanSupplier current){
        int[] dimensions=coordinate(plan,"size");
        List<Integer> lower=bounds.from(),upper=bounds.to();
        JsonArray palette=plan.getAsJsonArray("palette"),usedPalette=new JsonArray(),fills=new JsonArray();
        var indices=new java.util.HashMap<Integer,Integer>();
        usedPalette.add(palette.get(0));indices.put(0,0);
        for(JsonElement value:plan.getAsJsonArray("fills")){
            check(current);
            JsonObject fill=value.getAsJsonObject();
            int[] from=coordinate(fill,"from"),to=coordinate(fill,"to");
            boolean intersects=true;
            for(int axis=0;axis<3;axis++){
                from[axis]=Math.max(from[axis],Math.max(0,lower.get(axis)-3));
                to[axis]=(int)Math.min(to[axis],Math.min(dimensions[axis]-1L,(long)upper.get(axis)+3));
                if(from[axis]>to[axis])intersects=false;
            }
            if(!intersects)continue;
            int block=integer(fill.get("block"));
            int index=indices.computeIfAbsent(block,id->{int next=usedPalette.size();usedPalette.add(palette.get(id));return next;});
            var clipped=new JsonObject();var a=new JsonArray();var b=new JsonArray();
            for(int axis=0;axis<3;axis++){a.add(from[axis]);b.add(to[axis]);}
            clipped.add("from",a);clipped.add("to",b);clipped.addProperty("block",index);fills.add(clipped);
        }
        var result=new JsonObject();result.add("size",plan.get("size"));result.add("palette",usedPalette);result.add("fills",fills);
        return result;
    }
    private JsonObject chief(String task,String context,JsonObject candidate,List<AiImage> images,Options options,Session session,String goal)throws Exception {
        session.phase("coordination","The chief architect is comparing proposals and resolving design feedback.");
        Bounds all=Bounds.whole(candidate);
        String prompt=context+"\nYou are the CHIEF COORDINATION architect. "+goal+" Return a validated repair/integration patch using "+PATCH_SCHEMA+". Every fill must be inside "+all.from()+" to "+all.to()+". Do not change dimensions. You may return an empty patch when preserving the current candidate is best. Your summary and messages are public design decisions, not private reasoning. Answer actual earlier messages; do not fabricate other agents' statements.\nPublic design exchanges:\n"+session.discussion()+"\nLatest complete validated candidate:\n"+candidate;
        try{
            JsonObject patch=ask(session,task,RULES,prompt,images,options.tier(),response->{validatePatch(response,candidate,all,false,session);validate(merge(candidate,response),session);return response;});
            JsonObject result=merge(candidate,patch);validate(result,session);
            session.checkpoint(result);
            session.report(task,"all","decision",patch);
            session.complete(task,publicSummary(patch,"Validated proposals coordinated."));return result;
        }catch(CancellationException cancelled){throw cancelled;}
        catch(Exception failure){
            session.check();session.fail(task,failureSummary(failure));
            session.message("system",task,"status",failureSummary(failure));
            if(fatalFailure(failure))throw failure;
            return candidate;
        }
    }
    private JsonObject ask(Session session,String id,String system,String prompt,List<AiImage> images,Tier tier,ResultParser parser)throws Exception {
        String attemptPrompt=prompt;
        int attempts=Math.max(tier.attempts,3);
        for(int attempt=0;attempt<attempts;attempt++){
            session.check();session.running(id,attempt==0?"":"Retrying or repairing structured output, attempt "+(attempt+1)+".");
            AtomicInteger reports=new AtomicInteger();String raw;
            try{
                raw=request.ask(system+"\n"+session.communicationLanguage,attemptPrompt,images,session::current,value->{reports.incrementAndGet();session.usage(value);});
            }catch(CancellationException cancelled){throw cancelled;}
            catch(Exception failure){
                if(!session.current())throw new CancellationException();
                if(transientFailure(failure)&&attempt+1<attempts){
                    session.parallelism.set(1);
                    session.message("system",id,"status",failureSummary(failure));
                    pause(session,failure instanceof RequestFailure typed?Math.max(1000L<<attempt,typed.retryAfterMillis()):1000L<<attempt);
                    continue;
                }
                session.fail(id,failureSummary(failure));throw failure;
            }finally{if(reports.get()==0&&session.current())session.usage(new Usage(-1,-1,-1,false));}
            session.check();
            try{
                JsonElement parsed=JsonParser.parseString(stripFence(raw));
                if(!parsed.isJsonObject())throw invalid();
                return parser.parse(parsed.getAsJsonObject());
            }catch(CancellationException cancelled){throw cancelled;}
            catch(IllegalArgumentException|IllegalStateException|com.google.gson.JsonParseException invalid){
                if("ui.building.ai.memory".equals(invalid.getMessage())){session.fail(id,"The design exceeds available memory.");throw invalid;}
                if(attempt+1==attempts){session.fail(id,"Structured output did not pass validation.");throw new IllegalArgumentException("ui.building.ai.invalid");}
                attemptPrompt=prompt+"\nYour previous result failed deterministic JSON, block-state or coordinate validation. Repair it and return a COMPLETE valid replacement for your assigned response schema. Preserve the authorized bounds and palette rules. Previous result:\n"+raw;
            }
        }
        throw invalid();
    }
    private static boolean transientFailure(Exception failure){return failure instanceof RequestFailure typed?typed.retryable():failure instanceof java.io.IOException||failure instanceof java.util.concurrent.TimeoutException;}
    private static boolean fatalFailure(Exception failure) {
        // ask() has already retried transient rate limits before returning the last failure.
        return failure instanceof RequestFailure typed && (typed.httpStatus()==401 || typed.httpStatus()==402
                || typed.httpStatus()==403 || typed.httpStatus()==429 || typed.publicKey().endsWith(".unsupported"));
    }
    private static String failureSummary(Exception failure){
        if(failure instanceof RequestFailure typed)return typed.publicKey();
        if(failure instanceof IllegalArgumentException&&failure.getMessage()!=null&&failure.getMessage().startsWith("ui.building.ai."))return failure.getMessage();
        if(failure instanceof java.util.concurrent.TimeoutException)return "ui.building.ai.request.timeout";
        if(failure instanceof java.io.IOException)return "ui.building.ai.request.network";
        return "ui.building.ai.request.malformed";
    }
    private static void pause(Session session,long milliseconds)throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(8000,milliseconds));
        while(System.nanoTime()<deadline){session.check();Thread.sleep(Math.min(100,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));}
    }
    private void validate(JsonObject plan,Session session)throws Exception {validatePlan(plan,session::current);validator.validate(plan,session::current);session.check();}
    private void validatePatch(JsonObject patch,JsonObject base,Bounds bounds,boolean inspectionOnly,Session session)throws Exception {
        JsonObject independent=new JsonObject();independent.add("size",base.getAsJsonArray("size").deepCopy());
        independent.add("palette",patch.get("palette"));independent.add("fills",patch.get("fills"));
        validate(independent,session);
        for(JsonElement value:patch.getAsJsonArray("fills")){
            session.check();JsonObject fill=value.getAsJsonObject();int[] low=coordinate(fill,"from"),high=coordinate(fill,"to");
            if(inspectionOnly||!bounds.contains(low,high))throw invalid();
        }
    }
    /** Deterministic shape check without allocating a volume-sized array. Integration checks the real registry. */
    public static void validatePlan(JsonObject plan,BooleanSupplier current){
        check(current);int[] size=coordinate(plan,"size");
        for(int dimension:size)if(dimension<1)throw invalid();
        try{Math.multiplyExact(Math.multiplyExact((long)size[0],size[1]),size[2]);}catch(ArithmeticException invalid){throw invalid();}
        JsonArray palette=array(plan,"palette"),fills=array(plan,"fills");if(palette.isEmpty())throw invalid();
        for(JsonElement value:palette){
            check(current);if(!value.isJsonObject())throw invalid();JsonObject block=value.getAsJsonObject();
            if(!block.has("Name")||!block.get("Name").isJsonPrimitive()||!block.getAsJsonPrimitive("Name").isString()
                    ||!block.get("Name").getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw invalid();
            if(block.has("Properties")){
                if(!block.get("Properties").isJsonObject())throw invalid();
                for(var property:block.getAsJsonObject("Properties").entrySet())
                    if(!property.getValue().isJsonPrimitive()||!property.getValue().getAsJsonPrimitive().isString())throw invalid();
            }
            if(block.has("connect")&&(!block.get("connect").isJsonPrimitive()
                    ||!block.getAsJsonPrimitive("connect").isBoolean()))throw invalid();
        }
        if(!"minecraft:air".equals(palette.get(0).getAsJsonObject().get("Name").getAsString()))throw invalid();
        for(JsonElement value:fills){
            check(current);if(!value.isJsonObject())throw invalid();JsonObject fill=value.getAsJsonObject();
            int[] low=coordinate(fill,"from"),high=coordinate(fill,"to");int block=integer(fill.get("block"));
            if(block<0||block>=palette.size())throw invalid();
            for(int axis=0;axis<3;axis++)if(low[axis]<0||high[axis]<low[axis]||high[axis]>=size[axis])throw invalid();
        }
    }
    /** Patch-local palette indices are remapped, preserving ordered air cuts and later overwrites. */
    static JsonObject merge(JsonObject base,JsonObject patch){
        JsonObject combined=base.deepCopy();JsonArray palette=combined.getAsJsonArray("palette"),fills=combined.getAsJsonArray("fills");
        Map<JsonObject,Integer> known=new LinkedHashMap<>();
        for(int i=0;i<palette.size();i++)known.putIfAbsent(canonicalState(palette.get(i).getAsJsonObject()),i);
        JsonArray incoming=patch.getAsJsonArray("palette");int[] mapping=new int[incoming.size()];
        for(int i=0;i<incoming.size();i++){
            JsonObject state=canonicalState(incoming.get(i).getAsJsonObject());Integer index=known.get(state);
            if(index==null){index=palette.size();palette.add(state);known.put(state,index);}mapping[i]=index;
        }
        for(JsonElement value:patch.getAsJsonArray("fills")){
            JsonObject fill=value.getAsJsonObject().deepCopy();fill.addProperty("block",mapping[integer(fill.get("block"))]);
            if(fills.isEmpty()||!fills.get(fills.size()-1).equals(fill))fills.add(fill);
        }
        return combined;
    }
    private static JsonObject canonicalState(JsonObject state){
        JsonObject copy=new JsonObject();copy.addProperty("Name",state.get("Name").getAsString());
        if(state.has("Properties")&&state.getAsJsonObject("Properties").size()>0)copy.add("Properties",state.getAsJsonObject("Properties").deepCopy());
        if(state.has("connect")&&!state.get("connect").getAsBoolean())copy.addProperty("connect",false);
        return copy;
    }
    private record Patch(int index,JsonObject plan,Bounds bounds) { }
    private record Bounds(int x0,int y0,int z0,int x1,int y1,int z1) {
        static Bounds whole(JsonObject plan){int[] size=coordinate(plan,"size");return new Bounds(0,0,0,size[0]-1,size[1]-1,size[2]-1);}
        List<Integer> from(){return List.of(x0,y0,z0);}List<Integer> to(){return List.of(x1,y1,z1);}
        long volume(){return (long)(x1-x0+1)*(y1-y0+1)*(z1-z0+1);}
        boolean contains(int[] low,int[] high){return low[0]>=x0&&low[1]>=y0&&low[2]>=z0&&high[0]<=x1&&high[1]<=y1&&high[2]<=z1;}
        List<Bounds> split(){
            int x=x1-x0,y=y1-y0,z=z1-z0;
            if(x>=y&&x>=z&&x>0){int middle=x0+x/2;return List.of(new Bounds(x0,y0,z0,middle,y1,z1),new Bounds(middle+1,y0,z0,x1,y1,z1));}
            if(z>=y&&z>0){int middle=z0+z/2;return List.of(new Bounds(x0,y0,z0,x1,y1,middle),new Bounds(x0,y0,middle+1,x1,y1,z1));}
            if(y>0){int middle=y0+y/2;return List.of(new Bounds(x0,y0,z0,x1,middle,z1),new Bounds(x0,middle+1,z0,x1,y1,z1));}
            return List.of(this);
        }
    }
    private static List<Bounds> partition(JsonObject plan,int count){
        List<Bounds> regions=new ArrayList<>();regions.add(Bounds.whole(plan));
        while(regions.size()<count){
            int largest=0;for(int i=1;i<regions.size();i++)if(regions.get(i).volume()>regions.get(largest).volume())largest=i;
            Bounds previous=regions.get(largest);if(previous.volume()==1)break;
            regions.remove(largest);regions.addAll(previous.split());
        }
        regions.sort(Comparator.comparingInt(Bounds::y0).thenComparingInt(Bounds::z0).thenComparingInt(Bounds::x0));return regions;
    }
    private static String detailRole(Bounds bounds,JsonObject plan,int index){
        int[] size=coordinate(plan,"size");String region="Region "+(index+1)+" "+bounds.from()+"–"+bounds.to();
        if(bounds.y1==size[1]-1)return region+": roofline, upper facade, lighting and architectural detail";
        if(bounds.y0==0)return region+": foundations, circulation, entrances and room furnishings";
        return region+": facade rhythm, interior furnishings, material transitions and coherent lighting";
    }
    private static String reviewRole(int pass){return switch(pass){case 0->"Structure, entrances, stairs and circulation";case 1->"Materials, furnishings, lighting, landscape and architectural detail";default->"Independent final requirements and consistency audit";};}
    private static String agentId(int index){return "agent-"+(index+1);}
    private static String dimensions(JsonObject plan){int[] size=coordinate(plan,"size");return size[0]+" × "+size[1]+" × "+size[2];}
    private static JsonArray array(JsonObject value,String key){JsonElement field=value.get(key);if(field==null||!field.isJsonArray())throw invalid();return field.getAsJsonArray();}
    private static int[] coordinate(JsonObject value,String key){JsonArray list=array(value,key);if(list.size()!=3)throw invalid();return new int[]{integer(list.get(0)),integer(list.get(1)),integer(list.get(2))};}
    private static int integer(JsonElement value){try{if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw invalid();return value.getAsBigDecimal().intValueExact();}catch(RuntimeException invalid){throw invalid();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("ui.building.ai.invalid");}
    private static String stripFence(String raw){String value=raw==null?"":raw.strip();if(value.startsWith("```")){int from=value.indexOf('\n'),to=value.lastIndexOf("```");if(from>=0&&to>from)value=value.substring(from+1,to).strip();}return value;}
    private static String communicationLanguage(String language){
        return switch(language==null?"en":language){
            case "zh" -> "所有供玩家查看的 summary 和 messages[].content 必须用简体中文，即使需求、参考资料或前面的交流是其他语言。保留 JSON 字段名、方块 ID 和代理 ID 的原样。请用完整句子，不要用省略号代替内容。";
            case "zh_tw" -> "所有供玩家查看的 summary 和 messages[].content 必須使用繁體中文，即使需求、參考資料或先前的交流使用其他語言。保留 JSON 欄位名稱、方塊 ID 和代理 ID。請使用完整句子，勿以刪節號代替內容。";
            case "ja" -> "プレイヤー向けの summary と messages[].content は必ず日本語で書いてください。依頼や参考資料が別の言語でも同じです。JSON のキー、ブロック ID、エージェント ID はそのままにし、内容を省略せず完全な文にしてください。";
            case "ko" -> "플레이어가 보는 summary 및 messages[].content는 요청이나 참고 자료의 언어와 관계없이 한국어로 작성하세요. JSON 키, 블록 ID, 에이전트 ID는 그대로 유지하고 생략 없이 완전한 문장으로 작성하세요.";
            case "es" -> "Redacta summary y messages[].content en español aunque la solicitud o las referencias estén en otro idioma. Conserva las claves JSON y los identificadores de bloques y agentes. Usa frases completas sin puntos suspensivos para omitir contenido.";
            case "fr" -> "Rédige summary et messages[].content en français, quelle que soit la langue de la demande ou des références. Conserve les clés JSON et les identifiants de blocs et d’agents. Écris des phrases complètes, sans points de suspension à la place du contenu.";
            case "de" -> "Schreibe summary und messages[].content auf Deutsch, auch wenn Auftrag oder Referenzen in einer anderen Sprache verfasst sind. Behalte JSON-Schlüssel, Block-IDs und Agenten-IDs bei. Schreibe vollständige Sätze ohne Auslassungspunkte als Platzhalter.";
            default -> "Write all player-facing summary and messages[].content text in English, even when the request or references are in another language. Preserve JSON keys, block IDs and agent IDs. Use complete sentences without ellipses in place of content.";
        };
    }
    private static String cleanMessage(String value){return value==null?"":value.replaceAll("[\\p{Cc}&&[^\\n\\t]]","").strip();}
    private static String publicSummary(JsonObject response,String fallback){
        JsonElement value=response.get("summary");if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())return fallback;
        String summary=cleanMessage(value.getAsString());
        return summary.isEmpty()?fallback:summary;
    }
    private static Exception unwrap(java.util.concurrent.ExecutionException failure){Throwable cause=failure.getCause();return cause instanceof Exception exception?exception:new IllegalStateException("ui.building.ai.failed");}
    private static void check(BooleanSupplier current){if(Thread.currentThread().isInterrupted()||!current.getAsBoolean())throw new CancellationException();}

    private static final class Session {
        final Object lock=new Object();final BooleanSupplier permitted;final Consumer<Snapshot> observer;
        final Consumer<JsonObject> checkpoint;
        final String communicationLanguage;
        final AtomicBoolean stopped=new AtomicBoolean();final LinkedHashMap<String,Task> tasks=new LinkedHashMap<>();
        final AtomicInteger parallelism=new AtomicInteger(CONCURRENT_AGENTS);
        final List<Message> messages=new ArrayList<>();long messageSequence;volatile Exception fatal;
        String phase="queued",summary="";long input,output,total;boolean anyInput,anyOutput,anyTotal;int requests,unknown;
        Session(BooleanSupplier permitted,Consumer<Snapshot> observer,String communicationLanguage,Consumer<JsonObject> checkpoint){
            this.permitted=permitted;this.observer=observer;this.communicationLanguage=communicationLanguage;this.checkpoint=checkpoint;
        }
        void checkpoint(JsonObject plan){check();checkpoint.accept(plan);}
        boolean current(){return !stopped.get()&&!Thread.currentThread().isInterrupted()&&permitted.getAsBoolean();}
        void check(){BuildingDesignWorkshop.check(this::current);}
        void add(String id,String title,String role){synchronized(lock){tasks.put(id,new Task(id,title,role,State.QUEUED,""));}}
        void assign(String id,String title,String role){synchronized(lock){if(stopped.get())return;tasks.put(id,new Task(id,title,role,State.QUEUED,""));}}
        void phase(String phase,String summary){synchronized(lock){if(stopped.get())return;this.phase=phase;this.summary=summary;}publish();}
        void running(String id,String summary){update(id,State.RUNNING,summary);}
        void complete(String id,String summary){check();update(id,State.COMPLETED,summary);}
        void fail(String id,String summary){update(id,State.FAILED,summary);}
        void update(String id,State state,String summary){synchronized(lock){if(stopped.get())return;Task old=tasks.get(id);tasks.put(id,new Task(id,old.title(),old.role(),state,summary));}publish();}
        boolean hasFailures(){synchronized(lock){return tasks.values().stream().anyMatch(task->task.state()==State.FAILED);}}
        void message(String from,String to,String stage,String content){
            String clean=cleanMessage(content);if(clean.isBlank())return;
            synchronized(lock){if(stopped.get())return;messages.add(new Message("message-"+(++messageSequence),from,to,stage,clean));}publish();
        }
        void report(String from,String target,String stage,JsonObject result){
            String summary=publicSummary(result,"");
            if(!summary.isBlank())message(from,target,stage,summary);
            JsonElement fields=result.get("messages");
            if(fields==null||!fields.isJsonArray())return;
            int count=0;
            for(JsonElement value:fields.getAsJsonArray()){
                if(count++>=4)break;if(!value.isJsonObject())continue;
                JsonObject row=value.getAsJsonObject();JsonElement to=row.get("to"),body=row.get("content");
                if(to==null||body==null||!to.isJsonPrimitive()||!body.isJsonPrimitive()||!to.getAsJsonPrimitive().isString()||!body.getAsJsonPrimitive().isString())continue;
                String recipient=to.getAsString();
                synchronized(lock){if(!recipient.equals("all")&&!tasks.containsKey(recipient))continue;}
                message(from,recipient,stage,body.getAsString());
            }
        }
        long messageCursor(){synchronized(lock){return messageSequence;}}
        Map<String,List<Message>> directedMessages(long after,long through){
            synchronized(lock){
                var directed=new LinkedHashMap<String,List<Message>>();
                for(int i=(int)after;i<through&&i<messages.size();i++){
                    Message message=messages.get(i);
                    if(message.to().startsWith("agent-")&&!message.to().equals(message.from())&&!message.stage().equals("status"))
                        directed.computeIfAbsent(message.to(),id->new ArrayList<>()).add(message);
                }
                return directed;
            }
        }
        String discussion(){synchronized(lock){return discussion(messages);}}
        String discussion(List<Message> source){
            JsonArray exchange=new JsonArray();
            for(Message message:source){
                JsonObject item=new JsonObject();item.addProperty("from",message.from());item.addProperty("to",message.to());
                item.addProperty("stage",message.stage());item.addProperty("content",message.content());exchange.add(item);
            }
            return exchange.toString();
        }
        void usage(Usage value){
            if(value==null)value=new Usage(-1,-1,-1,false);
            synchronized(lock){
                requests+=Math.max(1,value.requests());unknown+=Math.max(value.unknownRequests(),value.reported()?0:1);
                if(value.inputTokens()>=0){input=saturatedAdd(input,value.inputTokens());anyInput=true;}
                if(value.outputTokens()>=0){output=saturatedAdd(output,value.outputTokens());anyOutput=true;}
                if(value.totalTokens()>=0){total=saturatedAdd(total,value.totalTokens());anyTotal=true;}
            }publish();
        }
        void stop(boolean cancelled){
            stopped.set(true);
            synchronized(lock){
                phase=cancelled?"cancelled":"failed";summary=cancelled?"Generation was cancelled; incomplete work was not applied.":"Generation did not pass all required tasks; the previous preview is preserved.";
                tasks.replaceAll((id,old)->old.state()==State.QUEUED||old.state()==State.RUNNING?new Task(id,old.title(),old.role(),State.CANCELLED,old.summary()):old);
            }publish();
        }
        void publish(){
            synchronized(lock){
                int completed=(int)tasks.values().stream().filter(task->task.state()==State.COMPLETED).count();
                Snapshot snapshot=new Snapshot(phase,List.copyOf(tasks.values()),completed,tasks.size(),new Usage(anyInput?input:-1,anyOutput?output:-1,anyTotal?total:-1,anyTotal&&unknown==0,requests,unknown),summary,List.copyOf(messages));
                observer.accept(snapshot);
            }
        }
        static long saturatedAdd(long first,long second){return Long.MAX_VALUE-first<second?Long.MAX_VALUE:first+second;}
    }
}
