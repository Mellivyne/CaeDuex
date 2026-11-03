package com.cheatprogram.capture;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Versioned transport shared by all loaders. Calls belong on a network worker. */
final class SharedCastConnection implements AutoCloseable {
    private static final byte[] MAGIC = {'C', 'P', 'C', 'A', 'S', 'T', 1};
    private static final int MAX_FRAME = 16 * 1024 * 1024;
    private static final Pattern INVITE = Pattern.compile("[A-Za-z0-9_-]{22}");

    private final SSLSocket socket;
    private final java.net.Socket tcp;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final boolean publisher;
    private final String invite;
    private final javax.crypto.spec.SecretKeySpec key;
    private final java.security.SecureRandom random=new java.security.SecureRandom();


    static SharedCastConnection publish(String host, int port) throws IOException {
        return publish(host,port,"");
    }
    static SharedCastConnection publish(String host,int port,String pin)throws IOException{return new SharedCastConnection(host,port,null,pin);}

    static SharedCastConnection watch(String host, int port, String invite) throws IOException {
        if (invite == null || !invite.matches("[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}")) throw new IOException("Invalid invitation format");
        return watch(host,port,invite,"");
    }
    static SharedCastConnection watch(String host,int port,String invite,String pin)throws IOException{
        if(invite==null||!invite.matches("[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}"))throw new IOException("Invalid invitation format");
        return new SharedCastConnection(host,port,invite,pin);
    }
    private SharedCastConnection(String host, int port, String requestedInvite,String pin) throws IOException {
        SSLSocketFactory factory = pin.isEmpty()?(SSLSocketFactory) SSLSocketFactory.getDefault():pinned(pin);
        var transport = new java.net.Socket();tcp=transport;
        SSLSocket tls = null;
        try {
            transport.connect(new InetSocketAddress(host, port), 5000);
            tls = (SSLSocket) factory.createSocket(transport, host, port, true);
            socket = tls;
            SSLParameters parameters = socket.getSSLParameters();
            if(pin.isEmpty())parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            publisher = requestedInvite == null;
            output.write(MAGIC);
            output.writeByte(publisher ? 'P' : 'V');
            if (!publisher) output.write(requestedInvite.substring(0,22).getBytes(StandardCharsets.US_ASCII));
            output.flush();
            if (input.readUnsignedByte() != 1) throw new IOException("Sharing session unavailable");
            String token = publisher ? new String(readExactly(22), StandardCharsets.US_ASCII) : requestedInvite.substring(0,22);
            if (!INVITE.matcher(token).matches()) throw new IOException("Invalid relay response");
            byte[] secret;
            if(publisher){secret=new byte[32];random.nextBytes(secret);}else secret=java.util.Base64.getUrlDecoder().decode(requestedInvite.substring(23));
            key=new javax.crypto.spec.SecretKeySpec(secret,"AES");
            invite=token+"."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            socket.setSoTimeout(0);
        } catch (IOException | RuntimeException failure) {
            try { if (tls != null) tls.close(); else transport.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private static SSLSocketFactory pinned(String pin)throws IOException{
        try{
            byte[] expected=java.util.Base64.getUrlDecoder().decode(pin);
            if(expected.length!=32)throw new IOException("Invalid certificate identity");
            var trust=new javax.net.ssl.X509TrustManager(){
                public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException("Client certificates not used");}
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{
                    if(chain==null||chain.length==0)throw new java.security.cert.CertificateException("Missing host identity");
                    chain[0].checkValidity();
                    try{byte[] actual=java.security.MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded());if(!java.security.MessageDigest.isEqual(expected,actual))throw new java.security.cert.CertificateException("Invitation host identity mismatch");}
                    catch(java.security.NoSuchAlgorithmException failure){throw new java.security.cert.CertificateException(failure);}
                }
            };
            var context=javax.net.ssl.SSLContext.getInstance("TLS");context.init(null,new javax.net.ssl.TrustManager[]{trust},null);return context.getSocketFactory();
        }catch(java.security.GeneralSecurityException|IllegalArgumentException invalid){throw new IOException("Invalid direct host identity",invalid);}
    }
    String invite() { return invite; }

    /** One complete independently decodable media packet, or an empty heartbeat. */
    void send(byte[] packet) throws IOException {
        if (!publisher || packet.length > MAX_FRAME) throw new IOException("Invalid publish packet");
        if(packet.length>0)packet=encrypt(packet);
        if(packet.length>MAX_FRAME)throw new IOException("Media frame too large");
        output.writeInt(packet.length);
        output.write(packet);
        output.flush();
    }

    byte[] receive() throws IOException {
        if (publisher) throw new IOException("Publisher cannot receive media");
        int size = input.readInt();
        if (size <= 0 || size > MAX_FRAME) throw new IOException("Invalid media packet size");
        return decrypt(readExactly(size));
    }
    private byte[] encrypt(byte[] bytes)throws IOException{
        try{
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,key,new javax.crypto.spec.GCMParameterSpec(128,nonce));cipher.updateAAD(MAGIC);
            byte[] encrypted=cipher.doFinal(bytes),packet=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,packet,0,nonce.length);System.arraycopy(encrypted,0,packet,nonce.length,encrypted.length);return packet;
        }catch(java.security.GeneralSecurityException failure){throw new IOException("Media encryption failed",failure);}
    }
    private byte[] decrypt(byte[] bytes)throws IOException{
        if(bytes.length<28)throw new IOException("Invalid encrypted media");
        try{
            var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.DECRYPT_MODE,key,new javax.crypto.spec.GCMParameterSpec(128,bytes,0,12));cipher.updateAAD(MAGIC);return cipher.doFinal(bytes,12,bytes.length-12);
        }catch(java.security.GeneralSecurityException failure){throw new IOException("ui.appcast.share.invalidInvite",failure);}
    }

    private byte[] readExactly(int size) throws IOException {
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException("Sharing session ended");
        return bytes;
    }

    @Override public void close() throws IOException {
        // Close the underlying transport first, interrupting a blocked TLS writer without waiting for close_notify.
        try { tcp.close(); } finally { socket.close(); }
    }
}
