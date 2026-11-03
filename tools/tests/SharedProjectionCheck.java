package com.cheatprogram.capture;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Standalone real-TLS, encryption, lifecycle and codec checks; never included in a mod JAR. */
public final class SharedProjectionCheck {
    static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
    static void await(BooleanSupplier condition,String message)throws Exception{long deadline=System.nanoTime()+15_000_000_000L;while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError(message);Thread.sleep(10);}}
    static void rejected(Runnable test){try{test.run();throw new AssertionError("Invalid geometry accepted");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception{
        int port=Integer.parseInt(args[0]);String address="tls://localhost:"+port;
        var position=new SharedCastPacket.Placement("minecraft:overworld",12,64,-5,4,2,180,0,0,60,false,48);
        try(var direct=SharedCastDirectServer.create("")){
            var local=direct.local();
            try(var owner=SharedCastConnection.publish(local.host(),local.port(),local.pin())){
                var invitation=SharedCastInvite.parse(direct.advertised().code(owner.invite()));
                check(invitation.pin().equals(local.pin()),"Direct invitation lost its certificate pin");
                try(var guest=SharedCastConnection.watch(local.host(),local.port(),owner.invite(),local.pin())){
                    byte[] sample="Direct TLS without external relay".getBytes(java.nio.charset.StandardCharsets.UTF_8);owner.send(sample);check(Arrays.equals(guest.receive(),sample),"Direct fan-out failed");
                    String wrongPin=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
                    try(var fake=SharedCastConnection.watch(local.host(),local.port(),owner.invite(),wrongPin)){throw new AssertionError("Wrong direct host identity accepted");}catch(java.io.IOException expected){}
                    owner.close();try{guest.receive();throw new AssertionError("Direct revocation failed");}catch(java.io.IOException expected){}
                }
            }
        }
        try(var source=new SharedSceneSession();var guest=new SharedSceneSession()){
            source.publish("");await(()->!source.invite().isEmpty(),"Automatic direct host failed: "+source.publishStatus());guest.watch(source.invite());source.offer(new byte[]{'C','P','L',1,'{','}'});
            await(()->guest.receivedFrames()>0,"Direct native scene failed: "+guest.watchStatus());source.revoke();await(()->!guest.watching(),"Direct scene retained a viewer");
        }

        try(var owner=SharedCastConnection.publish("localhost",port);var other=SharedCastConnection.publish("localhost",port)){
            check(!owner.invite().equals(other.invite()),"Invitations collided");
            String code=SharedCastInvite.endpoint(address).code(owner.invite());
            check(SharedCastInvite.parse(code).token().equals(owner.invite()),"Invitation lost its key");
            try(var first=SharedCastConnection.watch("localhost",port,owner.invite());var second=SharedCastConnection.watch("localhost",port,owner.invite())){
                byte[] message="End-to-end encrypted payload: test marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                owner.send(message);check(Arrays.equals(first.receive(),message),"First viewer data mismatch");check(Arrays.equals(second.receive(),message),"Fanout mismatch");
                try(var late=SharedCastConnection.watch("localhost",port,owner.invite())){check(Arrays.equals(late.receive(),message),"Late viewer missed cached picture");}
                var crypt=SharedCastConnection.class.getDeclaredMethod("encrypt",byte[].class);crypt.setAccessible(true);
                var decrypt=SharedCastConnection.class.getDeclaredMethod("decrypt",byte[].class);decrypt.setAccessible(true);
                byte[] encrypted=(byte[])crypt.invoke(owner,(Object)message);check(!new String(encrypted,java.nio.charset.StandardCharsets.ISO_8859_1).contains("encrypted payload"),"Plaintext leaked");
                encrypted[encrypted.length-1]^=1;
                try{decrypt.invoke(first,(Object)encrypted);throw new AssertionError("Tampering accepted");}catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof java.io.IOException,"Invalid tamper error");}
                owner.close();
                try{first.receive();throw new AssertionError("Revocation left viewer alive");}catch(java.io.IOException expected){}
                try{second.receive();throw new AssertionError("Revocation left second viewer alive");}catch(java.io.IOException expected){}
            }
            Thread.sleep(60);
            try(var old=SharedCastConnection.watch("localhost",port,owner.invite())){throw new AssertionError("Revoked code accepted");}catch(java.io.IOException expected){}
            try(var wrongHost=SharedCastConnection.publish("127.0.0.1",port)){throw new AssertionError("TLS hostname check bypassed");}catch(java.io.IOException expected){}
        }
        for(String invalid:List.of("http://localhost/code", "cpcast://user:password@localhost/token#key",address+"/short#wrong",address+"/token")){
            try{SharedCastInvite.parse(invalid);throw new AssertionError("Malformed invitation accepted");}catch(java.io.IOException expected){}
        }
        rejected(()->new SharedCastPacket.Placement("minecraft:overworld",Double.NaN,64,0,4,2,180,0,0,0,false,48));
        rejected(()->new SharedCastPacket(position,8192,8192,new byte[20]));
        try(var scenes=new SharedSceneSession();var viewer=new SharedSceneSession()){
            scenes.publish(address);await(()->!scenes.invite().isEmpty(),"No subtitle invite");viewer.watch(scenes.invite());
            byte[] scene={'C','P','L',1,'{','}'};scenes.offer(scene);
            await(()->viewer.receivedFrames()>0,"Native scene not received");check(Arrays.equals(viewer.take(),scene),"Native scene changed");
            scenes.revoke();await(()->!viewer.watching(),"Subtitle revocation failed");
        }
        try(var source=new SharedCastSession();var viewer=new SharedCastSession()){
            source.placement(position);source.publish(address,SharedCastVideo.Quality.SOURCE);
            await(()->!source.invite().isEmpty(),"No video invite");viewer.watch(source.invite());
            for(int n=0;n<100;n++){
                byte[] pixels=new byte[640*360*4];for(int i=0;i<pixels.length;i+=4){pixels[i]=(byte)(n*2);pixels[i+1]=77;pixels[i+2]=(byte)(255-n*2);pixels[i+3]=(byte)255;}
                source.offer(pixels,640,360);Thread.sleep(20);
            }
            await(()->viewer.receivedFrames()>15,"Video decode stalled: "+source.publishStatus()+" / "+viewer.watchStatus());
            var frame=viewer.takeFrame();check(frame!=null&&frame.width()==640&&frame.height()==360&&frame.pixels().length==640*360*4,"Video dimensions changed");
            check(frame.placement().dimension().equals("minecraft:overworld"),"World placement lost");
            source.revoke();await(()->!viewer.watching(),"Video revocation failed");check(viewer.takeFrame()==null,"Frame retained after revocation");
        }
        var encoded=new ArrayBlockingQueue<SharedCastPacket>(128);var decoded=new AtomicInteger();var error=new AtomicReference<Throwable>();
        byte[] blank=new byte[640*360*4];
        try(var encoder=new SharedCastVideo.Encoder(new SharedCastVideo.Capture(blank,640,360,position),SharedCastVideo.Quality.HD_60,encoded::offer,error::set)){
            for(int i=0;i<25;i++){encoder.write(new SharedCastVideo.Capture(blank,640,360,position));Thread.sleep(17);}
            await(()->encoded.size()>10,"No 1080p frames");var independent=encoded.stream().skip(5).findFirst().orElseThrow();
            check(independent.width()==1920&&independent.height()==1080,"HD canvas is incorrect");
            try(var decoder=new SharedCastVideo.Decoder(independent,frame->{check(frame.width()==1920&&frame.height()==1080,"HD decoder changed size");decoded.incrementAndGet();},error::set)){
                for(int i=0;i<12;i++){decoder.write(independent);Thread.sleep(20);}
                await(()->decoded.get()>2,"Mid-stream frame was not independently decodable");
            }
        }
        try(var portrait=new SharedCastVideo.Encoder(new SharedCastVideo.Capture(new byte[300*600*4],300,600,position),SharedCastVideo.Quality.HD_60,packet->{},failure->{})){
            check(portrait.width==540&&portrait.height==1080,"Portrait projection was stretched");
        }
        check(error.get()==null,"Codec failed: "+error.get());
        System.out.println("PASS: serverless direct hosting, pinned host identity, direct revocation, TLS identity, unique/revoked invites, fan-out, late join, encryption/tamper rejection, native scenes, live video, independent 1080p frames");
    }
}
