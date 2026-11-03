package com.cheatprogram.capture;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/** Text/geometry channel. No video codec, audio capture, account or quality setting. */
public final class SharedSceneSession implements AutoCloseable, SharedProjectionSession {
    private volatile Host host;
    private volatile Guest guest;
    private final AtomicReference<byte[]> received=new AtomicReference<>();
    public synchronized void publish(String address){revoke();Host next=new Host(address);host=next;SharedCastVideo.worker("scene-publish",next::run);}
    public synchronized void watch(String invite){leave();Guest next=new Guest(invite);guest=next;SharedCastVideo.worker("scene-watch",next::run);}
    public void offer(byte[] scene){Host current=host;if(current!=null&&!current.closed&&valid(scene)){current.pending.set(scene);synchronized(current.signal){current.signal.notifyAll();}}}
    public byte[] take(){return received.getAndSet(null);}
    @Override public boolean publishing(){Host current=host;return current!=null&&!current.closed;}
    @Override public boolean watching(){Guest current=guest;return current!=null&&!current.closed;}
    @Override public String invite(){Host current=host;return current==null||current.closed?"":current.invite;}
    @Override public String publishStatus(){Host current=host;return current==null?"ui.appcast.share.idle":current.status;}
    @Override public String watchStatus(){Guest current=guest;return current==null?"ui.appcast.share.idle":current.status;}
    @Override public long sentFrames(){Host current=host;return current==null?0:current.sent;}
    @Override public long receivedFrames(){Guest current=guest;return current==null?0:current.count;}
    public synchronized void revoke(){Host current=host;host=null;if(current!=null)current.close();}
    public synchronized void leave(){Guest current=guest;guest=null;if(current!=null)current.close();received.set(null);}
    @Override public void close(){revoke();leave();}
    private static boolean valid(byte[] bytes){return bytes!=null&&bytes.length>4&&bytes.length<=65536&&bytes[0]=='C'&&bytes[1]=='P'&&bytes[2]=='L'&&bytes[3]==1;}
    private static String error(Throwable failure){String message=failure.getMessage();return message!=null&&message.startsWith("ui.appcast.share.")?message:"ui.appcast.share.connectionFailed";}
    private static void disconnect(SharedCastConnection connection){if(connection!=null)try{connection.close();}catch(IOException ignored){}}
    private static final class Host {
        final String address;final AtomicReference<byte[]> pending=new AtomicReference<>();final Object signal=new Object();
        volatile SharedCastConnection connection;volatile SharedCastDirectServer direct;volatile boolean closed;volatile String invite="",status="ui.appcast.share.connecting";volatile long sent;
        Host(String address){this.address=address;}
        void run(){try{
            SharedCastInvite endpoint,advertised;
            if(address.isBlank()||address.startsWith("direct://")){
                var server=SharedCastDirectServer.create(address);synchronized(this){if(closed){server.close();return;}direct=server;}
                endpoint=server.local();advertised=server.advertised();
            }else{endpoint=SharedCastInvite.endpoint(address);advertised=endpoint;}
            var opened=SharedCastConnection.publish(endpoint.host(),endpoint.port(),endpoint.pin());
            synchronized(this){if(closed){opened.close();return;}connection=opened;invite=advertised.code(opened.invite());}
            status="ui.appcast.share.broadcasting";
            while(!closed){byte[] scene=pending.getAndSet(null);opened.send(scene==null?new byte[0]:scene);if(scene!=null)sent++;synchronized(signal){if(pending.get()==null&&!closed)signal.wait(500);}}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Throwable failure){if(!closed)status=error(failure);}finally{close();}}
        synchronized void close(){if(closed)return;closed=true;invite="";disconnect(connection);if(direct!=null)direct.close();pending.set(null);synchronized(signal){signal.notifyAll();}}
    }
    private final class Guest {
        final String code;volatile SharedCastConnection connection;volatile boolean closed;volatile String status="ui.appcast.share.connecting";volatile long count;
        Guest(String code){this.code=code;}
        void run(){try{
            var invite=SharedCastInvite.parse(code);var opened=SharedCastConnection.watch(invite.host(),invite.port(),invite.token(),invite.pin());
            synchronized(this){if(closed){opened.close();return;}connection=opened;}
            status="ui.appcast.share.waitingFrame";
            while(!closed){byte[] scene=opened.receive();if(!valid(scene))throw new IOException("ui.appcast.share.wrongType");if(!closed){received.set(scene);count++;status="ui.appcast.share.watching";}}
        }catch(Throwable failure){if(!closed)status=error(failure);}finally{close();}}
        synchronized void close(){if(closed)return;closed=true;disconnect(connection);received.set(null);}
    }
}
