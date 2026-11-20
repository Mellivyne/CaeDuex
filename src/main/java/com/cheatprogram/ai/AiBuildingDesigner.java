package com.cheatprogram.ai;

import com.cheatprogram.util.BuildingConnections;
import com.cheatprogram.util.BuildingFiles.Blueprint;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Data-only collaboration; generated results enter preview before any explicit world import. */
public final class AiBuildingDesigner {
    public static final AiConfig CONNECTION=new AiConfig(FabricLoader.getInstance().getConfigDir().resolve("cheatprogram-open/ai-building.json"));
    private static final ExecutorService IO=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"CaeDuex building designer");t.setDaemon(true);return t;});
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Design(Blueprint building,JsonObject plan) { }
    public static String minecraftVersion(){return FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();}
    public static String promptSchema(){
        return "Design a complete Minecraft "+minecraftVersion()+" building. Return ONLY a JSON object with this schema: "
                +"{\"name\":\"Building name\",\"size\":[width,height,depth],\"palette\":[{\"Name\":\"minecraft:air\"},{\"Name\":\"minecraft:stone_bricks\",\"Properties\":{}}],\"fills\":[{\"from\":[x,y,z],\"to\":[x,y,z],\"block\":1}]}. "
                +"Coordinates are inclusive nonnegative integers inside size; fills execute in order. Palette index zero MUST be minecraft:air. Use exact legal block IDs and state properties for this Minecraft version. "
                +"Include foundations, coherent architecture, usable entrances, doors, stairs, room-specific interiors, furnishing, material transitions, lighting, landscaping and purposeful detail. "
                +"No scripts, commands, executable code or private reasoning. Revisions must return the COMPLETE updated building, preserving unchanged work. There is no arbitrary dimension cap, but available memory still applies. "
                +"Verify coordinates, palette references, structural continuity, access and circulation repeatedly. The game validates and imports this JSON itself; never invent a CPS1 code.";
    }
    public CompletableFuture<Design> generate(String requirement,JsonObject previous,BooleanSupplier current){return generate(requirement,previous,List.of(),current);}
    public CompletableFuture<Design> generate(String requirement,JsonObject previous,List<AiImage> attachments,BooleanSupplier current){
        return generate(requirement,previous,attachments,BuildingDesignWorkshop.Options.defaults(),current,snapshot->{});
    }
    public CompletableFuture<Design> generate(String requirement,JsonObject previous,List<AiImage> attachments,
            BuildingDesignWorkshop.Options options,BooleanSupplier current,Consumer<BuildingDesignWorkshop.Snapshot> progress){
        return generate(requirement,previous,attachments,options,current,progress,plan->{});
    }
    public CompletableFuture<Design> generate(String requirement, JsonObject previous, List<AiImage> attachments,
            BuildingDesignWorkshop.Options options, BooleanSupplier current, Consumer<BuildingDesignWorkshop.Snapshot> progress,
            Consumer<JsonObject> checkpoint) {
        List<AiImage> images=List.copyOf(attachments);
        JsonObject prior=previous==null?null:previous.deepCopy();
        final java.net.URI uri;final String key,model,version;
        String language=com.cheatprogram.core.Lang.language();
        try{uri=CONNECTION.completionUri();key=CONNECTION.apiKey();model=CONNECTION.model();version=minecraftVersion();}
        catch(RuntimeException invalid){return CompletableFuture.failedFuture(new IllegalArgumentException("ui.building.ai.failed"));}
        return CompletableFuture.supplyAsync(()->{
            try{
                if(key.isBlank())throw new IllegalArgumentException("ui.building.ai.needKey");
                var workshop=new BuildingDesignWorkshop((system,user,references,live,usage)->{
                    JsonArray history=new JsonArray();
                    history.add(AiProtocol.message("system",system));
                    history.add(AiProtocol.message("user",user,references));
                    try {
                        var exchange=AiProtocol.exchange(HTTP,uri,model,key,history,new JsonArray(),live,reported->{
                            boolean complete=reported.inputTokens()>=0&&reported.outputTokens()>=0&&reported.totalTokens()>=0;
                            usage.accept(new BuildingDesignWorkshop.Usage(reported.inputTokens(),reported.outputTokens(),reported.totalTokens(),
                                    complete,(int)Math.min(Integer.MAX_VALUE,reported.requests()),complete?0:1));
                        },Duration.ofSeconds(360));
                        return exchange.reply().get("content").getAsString();
                    } catch(AiProtocol.RequestFailure failure) {
                        throw new BuildingDesignWorkshop.RequestFailure(failure.publicKey(),failure.httpStatus(),failure.retryable(),failure.retryAfterMillis());
                    }
                },AiBuildingDesigner::validatePlan);
                JsonObject plan=workshop.run(requirement,prior,images,version,language,options,current,progress,checkpoint);
                if(!current.getAsBoolean())throw new CancellationException();
                return new Design(decode(plan,current),plan);
            }catch(CancellationException cancelled){throw cancelled;}
            catch(BuildingDesignWorkshop.RequestFailure failure){throw new CompletionException(failure);}
            catch(OutOfMemoryError memory){throw new CompletionException(new IllegalArgumentException("ui.building.ai.memory"));}
            catch(IllegalArgumentException e){throw e;}
            catch(Exception e){throw new CompletionException(new IllegalArgumentException("ui.building.ai.failed"));}
        },IO);
    }
    public static Blueprint decode(JsonObject plan){
        return decode(plan,()->true);
    }
    public static Blueprint decode(JsonObject plan,java.util.function.BooleanSupplier current){
        try{return decodeChecked(plan,current,true);}
        catch(CancellationException error){throw error;}
        catch(IllegalArgumentException error){if(error.getMessage()!=null&&error.getMessage().startsWith("ui.building.ai."))throw error;throw invalid();}
        catch(RuntimeException error){throw invalid();}
    }
    public static void validatePlan(JsonObject plan,BooleanSupplier current){decodeChecked(plan,current,false);}
    private static Blueprint decodeChecked(JsonObject plan,java.util.function.BooleanSupplier current,boolean allocate){
        if(!current.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();
        JsonArray size=plan.getAsJsonArray("size");if(size==null||size.size()!=3)throw invalid();
        int w=integer(size.get(0)),h=integer(size.get(1)),d=integer(size.get(2));
        if(w<1||h<1||d<1)throw invalid();
        long volume;try{volume=Math.multiplyExact(Math.multiplyExact((long)w,h),d);}catch(ArithmeticException e){throw invalid();}
        // A resource check, not a design-size preference. Reject before allocation.
        long available=Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        if(volume>Integer.MAX_VALUE-8||volume*16>available)throw new IllegalArgumentException("ui.building.ai.memory");
        JsonArray palette=plan.getAsJsonArray("palette");if(palette==null||palette.isEmpty())throw invalid();
        List<CompoundTag> states=new ArrayList<>();
        List<BlockState> resolvedStates=new ArrayList<>();
        for(JsonElement element:palette){if(!current.getAsBoolean())throw new CancellationException();JsonObject state=element.getAsJsonObject();String id=state.get("Name").getAsString();if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.ResourceLocation.parse(id)))throw invalid();
            CompoundTag tag=new CompoundTag();
            tag.putString("Name",id);
            var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.ResourceLocation.parse(id));
            BlockState resolved=block.defaultBlockState();
            if(state.has("Properties")){
                CompoundTag props=new CompoundTag();
                for(var entry:state.getAsJsonObject("Properties").entrySet()){
                    var property=block.getStateDefinition().getProperty(entry.getKey());
                    String value=entry.getValue().getAsString();
                    if(property==null||property.getValue(value).isEmpty())throw invalid();
                    props.putString(entry.getKey(),value);
                    if(allocate)resolved=withProperty(resolved,property,value);
                }
                tag.put("Properties",props);
            }
            if(state.has("connect")){
                if(!state.get("connect").isJsonPrimitive()||!state.getAsJsonPrimitive("connect").isBoolean())throw invalid();
                if(!state.get("connect").getAsBoolean())BuildingConnections.keepAuthoredConnections(tag);
            }
            states.add(tag);
            if(allocate)resolvedStates.add(resolved);
        }
        if(!palette.get(0).getAsJsonObject().get("Name").getAsString().equals("minecraft:air"))throw invalid();
        // Validate the entire plan before applying it; malformed revisions cannot replace a good preview.
        JsonArray fills=plan.getAsJsonArray("fills");if(fills==null)throw invalid();
        for(JsonElement element:fills){if(!current.getAsBoolean())throw new CancellationException();JsonObject fill=element.getAsJsonObject();JsonArray a=fill.getAsJsonArray("from"),b=fill.getAsJsonArray("to");int block=integer(fill.get("block"));if(a.size()!=3||b.size()!=3||block<0||block>=states.size())throw invalid();
            for(int i=0;i<3;i++){int low=integer(a.get(i)),high=integer(b.get(i)),limit=i==0?w:i==1?h:d;if(low<0||high<low||high>=limit)throw invalid();}}
        if(!allocate)return null;
        int[] blocks=new int[(int)volume];
        for(JsonElement element:fills){if(Thread.currentThread().isInterrupted()||!current.getAsBoolean())throw new CancellationException();JsonObject fill=element.getAsJsonObject();var a=fill.getAsJsonArray("from");var b=fill.getAsJsonArray("to");int block=integer(fill.get("block"));
            for(int y=integer(a.get(1));y<=integer(b.get(1));y++){if(!current.getAsBoolean())throw new CancellationException();for(int z=integer(a.get(2));z<=integer(b.get(2));z++)Arrays.fill(blocks,(y*d+z)*w+integer(a.get(0)),(y*d+z)*w+integer(b.get(0))+1,block);}}
        BuildingConnections.resolve(w,h,d,resolvedStates,states,blocks,current);
        return new Blueprint(com.cheatprogram.util.BuildingFiles.cleanName(plan.has("name")?plan.get("name").getAsString():"AI building"),w,h,d,List.copyOf(states),blocks,Map.of());
    }
    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        return state.setValue(property,property.getValue(value).orElseThrow());
    }
    private static int integer(JsonElement value){try{return value.getAsBigDecimal().intValueExact();}catch(Exception e){throw invalid();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("ui.building.ai.invalid");}
}
