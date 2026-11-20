package com.cheatprogram.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Local HTTP fixtures only. No credentials, provider requests, billing estimates or devices. */
public final class AiProtocolUsageCheck {
    private record Response(int status,String json) { }
    private static JsonObject object(String json){return JsonParser.parseString(json).getAsJsonObject();}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static String chat(String content,String finish,int tokens){
        JsonObject message=new JsonObject();message.addProperty("role","assistant");message.addProperty("content",content);
        JsonObject choice=new JsonObject();choice.addProperty("finish_reason",finish);choice.add("message",message);
        JsonArray choices=new JsonArray();choices.add(choice);
        JsonObject root=new JsonObject();root.add("choices",choices);
        JsonObject usage=new JsonObject();usage.addProperty("prompt_tokens",tokens);usage.addProperty("completion_tokens",tokens);usage.addProperty("total_tokens",2*tokens);root.add("usage",usage);
        return root.toString();
    }
    private static JsonArray history(boolean longHistory){
        JsonArray history=new JsonArray();history.add(AiProtocol.message("system","Fixture system"));
        history.add(AiProtocol.message("user",longHistory?"Historical data ".repeat(600):"Fixture request"));
        if(longHistory){history.add(AiProtocol.message("assistant","Prior result ".repeat(100)));history.add(AiProtocol.message("user","Earlier requirement"));history.add(AiProtocol.message("assistant","Earlier result"));history.add(AiProtocol.message("user","Latest request"));}
        return history;
    }
    private static void exchange(List<Response> responses,boolean longHistory,List<AiProtocol.Usage> reported,boolean shouldFail)throws Exception{
        AtomicInteger calls=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",request->{
            request.getRequestBody().readAllBytes();
            int index=calls.getAndIncrement();
            Response response=index<responses.size()?responses.get(index):new Response(500,"{}");
            byte[] bytes=response.json().getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(response.status(),bytes.length);
            try(var body=request.getResponseBody()){body.write(bytes);}
        });
        server.start();
        try(var client=HttpClient.newHttpClient()){
            URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions");
            try{
                var result=AiProtocol.exchange(client,endpoint,"fixture-model","fixture-key",history(longHistory),new JsonArray(),()->true,reported::add);
                require(!shouldFail,"Expected invalid reply to fail");
                require(result.reply().get("content").getAsString().endsWith("done"),"Continued text not returned");
                require(result.compacted()==longHistory,"Compaction state incorrect");
            }catch(RuntimeException invalid){if(!shouldFail)throw invalid;}
            require(calls.get()==responses.size(),"Unexpected HTTP calls");
        }finally{server.stop(0);}
    }
    public static void main(String[] args)throws Exception{
        var missing=AiProtocol.usage(object("{}"));
        require(missing.inputTokens()==-1&&missing.totalTokens()==-1&&missing.requests()==1,"Missing usage must remain unknown");
        var chat=AiProtocol.usage(object("{\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":40,\"total_tokens\":160,\"prompt_tokens_details\":{\"cached_tokens\":50},\"completion_tokens_details\":{\"reasoning_tokens\":10}}}"));
        require(chat.inputTokens()==120&&chat.outputTokens()==40&&chat.totalTokens()==160&&chat.cachedTokens()==50&&chat.reasoningTokens()==10,"Chat counters");
        var responses=AiProtocol.usage(object("{\"usage\":{\"input_tokens\":130,\"output_tokens\":60,\"total_tokens\":190,\"input_tokens_details\":{\"cached_tokens\":80},\"output_tokens_details\":{\"reasoning_tokens\":20}}}"));
        require(responses.inputTokens()==130&&responses.outputTokens()==60&&responses.cachedTokens()==80&&responses.reasoningTokens()==20,"Responses counters");
        var deepseek=AiProtocol.usage(object("{\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"prompt_cache_hit_tokens\":12}}"));
        require(deepseek.cachedTokens()==12&&deepseek.totalTokens()==-1,"DeepSeek cache or fabricated total");
        var invalid=AiProtocol.usage(object("{\"usage\":{\"input_tokens\":-1,\"prompt_tokens\":\"42\",\"output_tokens\":1.2,\"total_tokens\":9223372036854775808}}"));
        require(invalid.inputTokens()==-1&&invalid.outputTokens()==-1&&invalid.totalTokens()==-1,"Invalid counters accepted");
        List<AiProtocol.Usage> observed=new ArrayList<>();
        exchange(List.of(new Response(200,chat("part","length",4)),new Response(200,chat("done","stop",6))),false,observed,false);
        require(observed.size()==2&&observed.stream().mapToLong(AiProtocol.Usage::totalTokens).sum()==20,"Continuation usage missing");
        observed.clear();
        exchange(List.of(new Response(400,"{\"error\":{\"code\":\"context_length_exceeded\"}}"),new Response(200,chat("memory ","length",5)),new Response(200,chat("complete","stop",7)),new Response(200,chat("done","stop",9))),true,observed,false);
        require(observed.size()==4&&observed.getFirst().totalTokens()==-1&&observed.stream().filter(u->u.totalTokens()>=0).mapToLong(AiProtocol.Usage::totalTokens).sum()==42,"Compaction/continuation usage missing");
        observed.clear();
        exchange(List.of(new Response(200,"{\"usage\":{\"total_tokens\":33},\"choices\":[]}")),false,observed,true);
        require(observed.size()==1&&observed.getFirst().totalTokens()==33,"Consumed usage lost when reply invalid");
        observed.clear();
        exchange(List.of(new Response(200,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"done\"}}]}")),false,observed,false);
        require(observed.size()==1&&observed.getFirst().totalTokens()==-1,"Unknown response not counted");
        observed.clear();
        exchange(List.of(new Response(200,chat("part","length",4)),new Response(429,"{\"error\":{\"message\":\"fixture rate limit\"}}")),false,observed,true);
        require(observed.size()==2&&observed.getLast().totalTokens()==-1,"Failed continuation request omitted");
        observed.clear();
        exchange(List.of(new Response(200,chat("part","length",4)),new Response(200,"not json")),false,observed,true);
        require(observed.size()==2&&observed.getLast().totalTokens()==-1,"Malformed continuation request omitted");
        System.out.println("AI_PROTOCOL_USAGE_PASS Chat/Responses/DeepSeek counters, unknown values, continuation, compaction, invalid reply");
    }
}
