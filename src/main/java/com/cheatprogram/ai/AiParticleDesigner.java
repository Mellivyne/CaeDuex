package com.cheatprogram.ai;

import com.cheatprogram.core.FeaturePresets;
import com.cheatprogram.core.ScopedPresetCodec;
import com.cheatprogram.module.visual.ParticleEffects;
import com.cheatprogram.util.ParticleStudio;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Produces validated particle data and a portable code, never executable model output. */
public final class AiParticleDesigner {
    public static final AiConfig CONNECTION=new AiConfig(FabricLoader.getInstance().getConfigDir().resolve("cheatprogram-open/ai-particles.json"));
    private static final Gson GSON=new Gson();
    private static final ExecutorService IO=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"CaeDuex particle designer");t.setDaemon(true);return t;});
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> EMITTER_KEYS=Set.of("particle","options","shown","rotateWithPlayer","symmetry","x","y","z","pitch","yaw","roll","vx","vy","vz","spread","scale","every","count","start","duration","lifetime","path");
    private static final Set<String> ROUND_KEYS=Set.of("name","duration","hold","emitters");
    public record Design(JsonObject plan,FeaturePresets.Prepared prepared,String code) { }

    public CompletableFuture<Design> generate(String requirement,JsonObject previous,List<AiImage> images,BooleanSupplier current){
        // Credentials and attachments are captured for this explicitly submitted request.
        var uri=CONNECTION.completionUri();String key=CONNECTION.apiKey(),model=CONNECTION.model();List<AiImage> attached=List.copyOf(images);
        JsonObject context=previous==null?null:previous.deepCopy();
        String language=com.cheatprogram.core.Lang.language();
        return CompletableFuture.supplyAsync(()->{
            try{
                if(key.isBlank())throw new IllegalArgumentException("ui.aiParticles.needKey");
                JsonArray history=new JsonArray();
                history.add(AiProtocol.message("system",instructions(language)));
                if(context!=null)history.add(AiProtocol.message("assistant",context.toString()));
                history.add(AiProtocol.message("user",requirement,attached));
                var exchange=AiProtocol.exchange(HTTP,uri,model,key,history,new JsonArray(),current);
                if(!current.getAsBoolean())throw new CancellationException();
                String text=exchange.reply().get("content").getAsString().strip();
                if(text.startsWith("```")){int start=text.indexOf('\n'),end=text.lastIndexOf("```");if(start>=0&&end>start)text=text.substring(start+1,end).strip();}
                return decode(JsonParser.parseString(text).getAsJsonObject());
            }catch(CancellationException e){throw e;}catch(Exception e){
                String message=e.getMessage();
                if(message!=null&&(message.startsWith("ui.aiParticles.")||message.startsWith("ui.ai.images.")))throw new CompletionException(new IllegalArgumentException(message));
                if(!attached.isEmpty()&&e instanceof AiProtocol.ProtocolException)throw new CompletionException(new IllegalArgumentException("ui.aiParticles.imageRejected"));
                throw new CompletionException(new IllegalArgumentException("ui.aiParticles.failed"));
            }
        },IO);
    }

    public static String instructions(){return instructions(com.cheatprogram.core.Lang.language());}

    public static String instructions(String language){
        StringBuilder types=new StringBuilder();
        for(var choice:ParticleEffects.choices())types.append(choice.id()).append(" options=")
                .append(ParticleEffects.defaultOptions(choice.id())).append('\n');
        String version=FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        return AiDesignPrompts.particles(language,version,types.toString());
    }

    /** A complete validation boundary, shared by network results and development checks. */
    public static Design decode(JsonObject raw)throws IOException{
        if(raw==null)throw invalid();
        ScopedPresetCodec.only(raw,Set.of("rounds"));
        JsonArray rounds=raw.getAsJsonArray("rounds");if(rounds==null||rounds.isEmpty()||rounds.size()>ParticleStudio.MAX_ROUNDS)throw invalid();
        JsonArray normalized=new JsonArray();int count=0;
        Set<String> registered=new HashSet<>();for(var choice:ParticleEffects.choices())registered.add(choice.id());
        for(JsonElement item:rounds){
            JsonObject row=item.getAsJsonObject();ScopedPresetCodec.only(row,ROUND_KEYS);
            JsonObject round=GSON.toJsonTree(new ParticleStudio.Round()).getAsJsonObject();
            for(var e:row.entrySet())if(!e.getKey().equals("emitters"))round.add(e.getKey(),e.getValue().deepCopy());
            exactInteger(round.get("duration"));
            JsonArray emitters=row.getAsJsonArray("emitters");if(emitters==null||emitters.isEmpty()||emitters.size()>ParticleStudio.MAX_EMITTERS||(count+=emitters.size())>ParticleStudio.MAX_TOTAL)throw invalid();
            JsonArray clean=new JsonArray();
            for(JsonElement element:emitters){
                JsonObject input=element.getAsJsonObject();ScopedPresetCodec.only(input,EMITTER_KEYS);
                JsonObject emitter=GSON.toJsonTree(new ParticleStudio.Emitter()).getAsJsonObject();
                for(var entry:input.entrySet()){
                    if(entry.getKey().equals("path")){
                        JsonObject path=emitter.getAsJsonObject("path");for(var property:entry.getValue().getAsJsonObject().entrySet())path.add(property.getKey(),property.getValue().deepCopy());
                    }else emitter.add(entry.getKey(),entry.getValue().deepCopy());
                }
                for(String field:List.of("every","count","start","duration","lifetime"))exactInteger(emitter.get(field));
                if(!registered.contains(emitter.get("particle").getAsString()))throw invalid();
                emitter.addProperty("fixed",false);emitter.addProperty("world","");emitter.addProperty("dimension","");
                clean.add(emitter);
            }
            round.add("emitters",clean);normalized.add(round);
        }
        JsonObject project=new JsonObject();project.addProperty("version",2);project.add("rounds",normalized);
        JsonObject data=new JsonObject();data.add("settings",new JsonObject());data.add("project",project);
        var decoded=new ScopedPresetCodec.Decoded(ScopedPresetCodec.Scope.PARTICLES,data);
        var prepared=FeaturePresets.validate(decoded);
        String code=ScopedPresetCodec.encode(ScopedPresetCodec.Scope.PARTICLES,data);
        // Keep the compact original plan as revision context, without IDs or private coordinates.
        return new Design(raw.deepCopy(),prepared,code);
    }
    private static void exactInteger(JsonElement value){try{value.getAsBigDecimal().intValueExact();}catch(Exception error){throw invalid();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("ui.aiParticles.invalid");}
}
