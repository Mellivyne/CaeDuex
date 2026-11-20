package com.cheatprogram.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Typed safe error fixtures. Uses only loopback HTTP and synthetic credentials. */
public final class AiProtocolFailureCheck {
    private static final String PRIVATE="fixture-private-body-key-path";
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static JsonArray history(){JsonArray result=new JsonArray();result.add(AiProtocol.message("user","Fixture"));return result;}
    private static AiProtocol.RequestFailure request(HttpClient client,URI uri,Duration timeout,List<AiProtocol.Usage> usage)throws Exception{
        try{AiProtocol.exchange(client,uri,"fixture-model","fixture-key",history(),new JsonArray(),()->true,usage::add,timeout);}
        catch(AiProtocol.RequestFailure failure){
            require(!failure.toString().contains(PRIVATE)&&failure.getCause()==null,"Private response retained in diagnosis");
            return failure;
        }
        throw new AssertionError("Failure was accepted as success");
    }
    public static void main(String[] args)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        for(int status:new int[]{401,403,408,429,500,503}){
            server.createContext("/"+status,request->{
                request.getRequestBody().readAllBytes();
                if(status==429)request.getResponseHeaders().add("Retry-After","999999");
                byte[] payload=("{\"error\":{\"message\":\""+PRIVATE+"\"}}").getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(status,payload.length);try(var output=request.getResponseBody()){output.write(payload);}
            });
        }
        server.createContext("/malformed",request->{
            request.getRequestBody().readAllBytes();byte[] bytes=("not json "+PRIVATE).getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200,bytes.length);try(var output=request.getResponseBody()){output.write(bytes);}
        });
        server.createContext("/timeout",request->{
            request.getRequestBody().readAllBytes();
            try{Thread.sleep(200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            try{request.sendResponseHeaders(200,0);request.getResponseBody().close();}catch(java.io.IOException closed){ }
        });
        server.start();
        try(var client=HttpClient.newHttpClient()){
            String root="http://127.0.0.1:"+server.getAddress().getPort();
            for(int status:new int[]{401,403,408,429,500,503}){
                var usage=new ArrayList<AiProtocol.Usage>();
                var failure=request(client,URI.create(root+"/"+status),Duration.ofSeconds(2),usage);
                require(failure.httpStatus()==status,"HTTP status lost");
                require(failure.retryable()==(status>=500||status==429||status==408),"Retry classification incorrect");
                require(usage.size()==1&&usage.getFirst().totalTokens()==-1,"Failed attempt usage must stay unknown");
                if(status==429)require(failure.publicKey().endsWith("rateLimit")&&failure.retryAfterMillis()==120_000,"Retry-After not safely bounded");
            }
            var malformed=request(client,URI.create(root+"/malformed"),Duration.ofSeconds(2),new ArrayList<>());
            require(malformed.publicKey().endsWith("malformed")&&!malformed.retryable(),"Malformed JSON not classified");
            var usage=new ArrayList<AiProtocol.Usage>();
            var timeout=request(client,URI.create(root+"/timeout"),Duration.ofMillis(30),usage);
            require(timeout.publicKey().endsWith("timeout")&&timeout.retryable()&&usage.size()==1,"Timeout diagnosis or usage missing");
            JsonObject incomplete=JsonParser.parseString("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},\"output\":[]}").getAsJsonObject();
            try{AiProtocol.reply(incomplete,true);throw new AssertionError("Incomplete Responses accepted");}
            catch(AiProtocol.RequestFailure expected){require(expected.publicKey().endsWith("incomplete")&&!expected.retryable(),"Incomplete Responses not classified");}
        }finally{server.stop(0);}
        System.out.println("AI_PROTOCOL_FAILURE_PASS safe auth/permission/rate-limit/server/timeout/malformed diagnoses, bounded Retry-After, one usage record per request");
    }
}
