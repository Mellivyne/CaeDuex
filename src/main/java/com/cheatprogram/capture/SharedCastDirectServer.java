package com.cheatprogram.capture;

import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** In-process, invitation-only TLS host. No account, external service, disk certificate or JDK-internal API. */
public final class SharedCastDirectServer implements AutoCloseable {
    private static final byte[] MAGIC={'C','P','C','A','S','T',1};
    private final ServerSocket listener;
    private final SSLSocketFactory factory;
    private final Map<SSLSocket,Socket> transports=new ConcurrentHashMap<>();
    private final String token,pin,advertisedHost;
    private final Set<SSLSocket> sockets=ConcurrentHashMap.newKeySet();
    private final Map<SSLSocket,ArrayBlockingQueue<byte[]>> viewers=new ConcurrentHashMap<>();
    private final Semaphore capacity=new Semaphore(18);
    private volatile boolean closed,publishing;
    private volatile byte[] latest;
    private SharedCastDirectServer(String address)throws IOException{
        try{
            var keys=KeyPairGenerator.getInstance("EC");keys.initialize(256);var pair=keys.generateKeyPair();
            var certificate=certificate(pair);
            pin=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded()));
            byte[] capability=new byte[16];new SecureRandom().nextBytes(capability);token=Base64.getUrlEncoder().withoutPadding().encodeToString(capability);
            char[] password=UUID.randomUUID().toString().toCharArray();var store=KeyStore.getInstance("PKCS12");store.load(null,password);
            store.setKeyEntry("share",pair.getPrivate(),password,new java.security.cert.Certificate[]{certificate});
            var manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());manager.init(store,password);Arrays.fill(password,'\0');
            var context=SSLContext.getInstance("TLS");context.init(manager.getKeyManagers(),null,null);
            int port=0;String host=localAddress();
            if(!address.isBlank()){var endpoint=SharedCastInvite.endpoint(address.replaceFirst("^direct:","tls:"));port=endpoint.port();host=endpoint.host();}
            advertisedHost=host;
            factory=context.getSocketFactory();listener=new ServerSocket(port,18);
            SharedCastVideo.worker("direct-accept",this::accept);
        }catch(GeneralSecurityException failure){throw new IOException("Cannot initialize direct TLS",failure);}
    }
    public static SharedCastDirectServer create(String address)throws IOException{return new SharedCastDirectServer(address);}
    public SharedCastInvite local(){return new SharedCastInvite("127.0.0.1",listener.getLocalPort(),null,pin);}
    public SharedCastInvite advertised(){return new SharedCastInvite(advertisedHost,listener.getLocalPort(),null,pin);}
    private void accept(){
        while(!closed)try{
            Socket raw=listener.accept();
            if(!capacity.tryAcquire()){raw.close();continue;}
            try{
                var socket=(SSLSocket)factory.createSocket(raw,raw.getInetAddress().getHostAddress(),raw.getPort(),true);socket.setUseClientMode(false);
                socket.setEnabledProtocols(Arrays.stream(socket.getSupportedProtocols()).filter(p->p.equals("TLSv1.3")||p.equals("TLSv1.2")).toArray(String[]::new));
                transports.put(socket,raw);sockets.add(socket);SharedCastVideo.worker("direct-peer",()->handle(socket));
            }catch(IOException failure){raw.close();capacity.release();}
        }catch(IOException failure){if(!closed)close();}
    }
    private void handle(SSLSocket socket){
        try{
            socket.setSoTimeout(5000);socket.setTcpNoDelay(true);socket.startHandshake();
            var input=new DataInputStream(new BufferedInputStream(socket.getInputStream()));var output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            if(!Arrays.equals(input.readNBytes(MAGIC.length),MAGIC))return;
            int role=input.readUnsignedByte();
            if(role=='P'){
                synchronized(this){if(publishing||!socket.getInetAddress().isLoopbackAddress()){output.writeByte(0);output.flush();return;}publishing=true;}
                output.writeByte(1);output.write(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.flush();socket.setSoTimeout(20000);
                try{
                    while(!closed){int size=input.readInt();if(size==0)continue;if(size<28||size>SharedCastPacket.MAX_BYTES)return;
                        byte[] bytes=input.readNBytes(size);if(bytes.length!=size)return;latest=bytes;
                        for(var queue:viewers.values()){queue.poll();queue.offer(bytes);}
                    }
                }finally{close();}
            }else if(role=='V'){
                byte[] requested=input.readNBytes(22);
                var queue=new ArrayBlockingQueue<byte[]>(1);
                synchronized(this){if(closed||!publishing||!MessageDigest.isEqual(requested,token.getBytes(java.nio.charset.StandardCharsets.US_ASCII))||viewers.size()>=16){output.writeByte(0);output.flush();return;}viewers.put(socket,queue);}
                byte[] cached=latest;if(cached!=null)queue.offer(cached);
                output.writeByte(1);output.flush();socket.setSoTimeout(0);
                SharedCastVideo.worker("direct-peer-life",()->{try{input.read();}catch(IOException ignored){}finally{closeSocket(socket);}});
                try{while(!closed&&!socket.isClosed()){
                    byte[] frame=queue.poll(1,TimeUnit.SECONDS);if(frame==null)continue;
                    output.writeInt(frame.length);output.write(frame);output.flush();
                }}finally{viewers.remove(socket);}
            }
        }catch(IOException ignored){}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        finally{sockets.remove(socket);closeSocket(socket);transports.remove(socket);capacity.release();}
    }
    @Override public synchronized void close(){if(closed)return;closed=true;latest=null;try{listener.close();}catch(IOException ignored){}for(var socket:sockets)closeSocket(socket);viewers.clear();}
    private void closeSocket(SSLSocket socket){try{Socket raw=transports.get(socket);if(raw!=null)raw.close();}catch(IOException ignored){}try{socket.close();}catch(IOException ignored){}}
    private static String localAddress(){
        // Routing lookup only: connecting a UDP socket does not send any packet.
        try(var route=new DatagramSocket()){route.connect(InetAddress.getByName("192.0.2.1"),9);var address=route.getLocalAddress();if(!address.isAnyLocalAddress()&&!address.isLoopbackAddress())return address.getHostAddress();}catch(IOException ignored){}
        try{for(var links=NetworkInterface.getNetworkInterfaces();links.hasMoreElements();){var link=links.nextElement();if(!link.isUp()||link.isLoopback()||link.isVirtual())continue;for(var addresses=link.getInetAddresses();addresses.hasMoreElements();){var address=addresses.nextElement();if(address instanceof Inet4Address&&address.isSiteLocalAddress()&&!address.isLinkLocalAddress())return address.getHostAddress();}}}catch(SocketException ignored){}
        return "127.0.0.1";
    }
    /** RFC 5280 leaf certificate signed by its ephemeral EC key. Clients authenticate the SPKI pin in the invitation. */
    private static X509Certificate certificate(KeyPair pair)throws GeneralSecurityException,IOException{
        byte[] algorithm=der(0x30,new byte[]{6,8,42,(byte)134,72,(byte)206,61,4,3,2});
        byte[] name=new X500Principal("CN=CaeDuex direct share").getEncoded();
        var clock=DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);Instant now=Instant.now();
        byte[] validity=der(0x30,join(der(0x17,clock.format(now.minusSeconds(300)).getBytes(java.nio.charset.StandardCharsets.US_ASCII)),der(0x17,clock.format(now.plusSeconds(2592000)).getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        byte[] serial=new java.math.BigInteger(120,new SecureRandom()).add(java.math.BigInteger.ONE).toByteArray();
        byte[] body=der(0x30,join(der(0xa0,der(2,new byte[]{2})),der(2,serial),algorithm,name,validity,name,pair.getPublic().getEncoded()));
        var signer=Signature.getInstance("SHA256withECDSA");signer.initSign(pair.getPrivate());signer.update(body);
        byte[] encoded=der(0x30,join(body,algorithm,der(3,join(new byte[]{0},signer.sign()))));
        var cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));cert.verify(pair.getPublic());return cert;
    }
    private static byte[] der(int tag,byte[] value)throws IOException{var out=new ByteArrayOutputStream();out.write(tag);if(value.length<128)out.write(value.length);else{int count=0,n=value.length;while(n!=0){count++;n>>>=8;}out.write(128|count);for(int i=count-1;i>=0;i--)out.write(value.length>>>(i*8)&255);}out.write(value);return out.toByteArray();}
    private static byte[] join(byte[]...parts)throws IOException{var out=new ByteArrayOutputStream();for(byte[] part:parts)out.write(part);return out.toByteArray();}
}
