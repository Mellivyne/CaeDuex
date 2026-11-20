package com.cheatprogram.ai;

import com.cheatprogram.core.Config;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Explicit local design exchange. Contains submitted design context, never account/API credentials. */
public final class AiDesignExchange {
    public static final int MAX_BYTES=16*1024*1024;
    private static final Set<String> KINDS=Set.of("building","particles");
    private AiDesignExchange(){ }

    public static Ticket open(String kind,String requirement,String context,int imageCount)throws IOException{
        return open(kind,requirement,context,"",imageCount);
    }
    public static Ticket open(String kind,String requirement,String instructions,String previousPlan,int imageCount)throws IOException{
        return open(kind,requirement,instructions,previousPlan,imageCount,"en");
    }
    public static Ticket open(String kind,String requirement,String instructions,String previousPlan,int imageCount,String language)throws IOException{
        return open(kind,requirement,instructions,previousPlan,imageCount,language,null);
    }
    public static Ticket open(String kind,String requirement,String instructions,String previousPlan,int imageCount,String language,
                              JsonObject progress)throws IOException{
        if(!KINDS.contains(kind)||requirement==null||requirement.isBlank()||imageCount<0||imageCount>AiImage.MAX_ATTACHMENTS)
            throw new IOException("ui.ai.exchange.invalid");
        if(!Config.persistenceAllowed())throw new IOException("ui.ai.exchange.failed");
        Path root=Config.directory().resolve("ai-design-exchange").toAbsolutePath().normalize();
        Files.createDirectories(root);
        if(Files.isSymbolicLink(root))throw new IOException("ui.ai.exchange.failed");
        root=root.toRealPath();
        Path server=root.resolve("design_mcp.py");
        try(var source=AiDesignExchange.class.getResourceAsStream("/assets/cheatprogram/ai/design_mcp.py")){
            if(source==null)throw new IOException("ui.ai.exchange.failed");
            atomic(server,source.readAllBytes());
        }
        String id=UUID.randomUUID().toString();
        JsonObject request=new JsonObject();
        request.addProperty("version",1);request.addProperty("id",id);request.addProperty("kind",kind);
        request.addProperty("language",AiDesignPrompts.supportedLanguage(language));
        request.addProperty("createdAt",Instant.now().toString());request.addProperty("requirement",requirement);
        request.addProperty("instructions",instructions==null?"":instructions);
        request.addProperty("previousPlan",previousPlan==null?"":previousPlan);
        if(progress!=null)request.add("progress",progress);
        request.addProperty("imageCount",imageCount);
        byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("ui.ai.exchange.tooLarge");
        atomic(root.resolve("request-"+id+".json"),bytes);
        return new Ticket(root,id,kind,request);
    }
    public static JsonObject parseResult(String input)throws IOException{
        if(input==null||input.length()>MAX_BYTES)throw new IOException("ui.ai.exchange.tooLarge");
        if(input.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("ui.ai.exchange.tooLarge");
        String text=input.strip();
        if(text.startsWith("```")){
            int start=text.indexOf('\n'),end=text.lastIndexOf("```");
            if(start<0||end<=start||!text.substring(end+3).isBlank())throw new IOException("ui.ai.exchange.invalid");
            text=text.substring(start+1,end).strip();
        }
        int depth=0;boolean string=false,escape=false;
        for(int index=0;index<text.length();index++){
            char ch=text.charAt(index);
            if(string){if(escape)escape=false;else if(ch=='\\')escape=true;else if(ch=='"')string=false;}
            else if(ch=='"')string=true;
            else if(ch=='{'||ch=='['){if(++depth>256)throw new IOException("ui.ai.exchange.invalid");}
            else if(ch=='}'||ch==']'){if(--depth<0)throw new IOException("ui.ai.exchange.invalid");}
        }
        if(string||depth!=0)throw new IOException("ui.ai.exchange.invalid");
        try{
            JsonElement value=JsonParser.parseString(text);
            if(!value.isJsonObject())throw new IOException("ui.ai.exchange.invalid");
            return value.getAsJsonObject();
        }catch(JsonParseException|IllegalStateException invalid){throw new IOException("ui.ai.exchange.invalid");}
    }
    private static void atomic(Path path,byte[] bytes)throws IOException{
        if(Files.isSymbolicLink(path))throw new IOException("ui.ai.exchange.failed");
        Path temporary=Files.createTempFile(path.getParent(),".design-",".tmp");
        try{
            Files.write(temporary,bytes);
            try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    public static final class Ticket implements AutoCloseable {
        private final Path root;
        private final String id,kind;
        private final JsonObject request;
        private boolean closed;
        private Ticket(Path root,String id,String kind,JsonObject request){this.root=root;this.id=id;this.kind=kind;this.request=request;}
        public String id(){return id;}
        public String requirement(){return request.get("requirement").getAsString();}
        public String prompt(){
            return AiDesignPrompts.exchange(request.get("language").getAsString(),kind,id,
                    request.get("imageCount").getAsInt(),request.get("instructions").getAsString(),
                    request.get("requirement").getAsString(),request.get("previousPlan").getAsString(),
                    request.has("progress")?request.getAsJsonObject("progress"):null,configuration());
        }
        private String configuration(){
            JsonArray args=new JsonArray();args.add("-u");args.add(root.resolve("design_mcp.py").toString());args.add("--root");args.add(root.toString());
            JsonObject server=new JsonObject();server.addProperty("command","python");server.add("args",args);
            JsonObject servers=new JsonObject();servers.add("cheat-program-design",server);
            JsonObject config=new JsonObject();config.add("mcpServers",servers);
            return new GsonBuilder().setPrettyPrinting().create().toJson(config);
        }
        public synchronized Optional<JsonObject> poll()throws IOException{
            if(closed||!Config.persistenceAllowed())return Optional.empty();
            Path path=root.resolve("result-"+id+".json");
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
            if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>MAX_BYTES)throw new IOException("ui.ai.exchange.invalid");
            byte[] bytes;
            try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(MAX_BYTES+1);}
            if(bytes.length>MAX_BYTES)throw new IOException("ui.ai.exchange.tooLarge");
            JsonObject result=parseResult(new String(bytes,StandardCharsets.UTF_8));
            try{
                if(!id.equals(result.get("requestId").getAsString())||!kind.equals(result.get("kind").getAsString())||!result.get("design").isJsonObject())
                    throw new IOException("ui.ai.exchange.invalid");
                return Optional.of(result.getAsJsonObject("design"));
            }catch(NullPointerException|IllegalStateException|UnsupportedOperationException invalid){throw new IOException("ui.ai.exchange.invalid");}
        }
        @Override public synchronized void close(){
            if(closed)return;closed=true;
            for(String prefix:new String[]{"request-","result-"})try{Files.deleteIfExists(root.resolve(prefix+id+".json"));}catch(IOException ignored){ }
        }
    }
}
