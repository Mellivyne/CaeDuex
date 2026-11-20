package com.cheatprogram.capture;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** A single explicit broadcast and subscription. Invites and received frames are never persisted. */
public final class SharedCastSession implements AutoCloseable, SharedProjectionSession {
    private volatile Publisher publisher;
    private volatile Viewer viewer;
    private volatile SharedCastPacket.Placement placement;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "CaeDuex-share-timeouts"); thread.setDaemon(true); return thread;
    });
    public void placement(SharedCastPacket.Placement value) { placement = value; }
    public synchronized void publish(String address, SharedCastVideo.Quality quality) {
        revoke(); Publisher next = new Publisher(address, quality); publisher = next;
        SharedCastVideo.worker("connect-publisher", next::run);
    }
    public synchronized void watch(String code) {
        leave(); Viewer next = new Viewer(code); viewer = next;
        SharedCastVideo.worker("connect-viewer", next::run);
    }
    public boolean publishing() { Publisher p = publisher; return p != null && !p.closed; }
    public boolean watching() { Viewer v = viewer; return v != null && !v.closed; }
    public SharedCastVideo.Quality quality() { Publisher p = publisher; return p == null ? SharedCastVideo.Quality.SOURCE : p.quality; }
    public String invite() { Publisher p = publisher; return p == null || p.closed ? "" : p.invite; }
    public String publishStatus() { Publisher p = publisher; return p == null ? "ui.appcast.share.idle" : p.status; }
    public String watchStatus() { Viewer v = viewer; return v == null ? "ui.appcast.share.idle" : v.status; }
    public String encoderName() { Publisher p = publisher; return p == null || p.encoder == null ? "" : p.encoder.name; }
    public long sentFrames() { Publisher p = publisher; return p == null ? 0 : p.sent; }
    public long receivedFrames() { Viewer v = viewer; return v == null ? 0 : v.received; }
    public SharedCastVideo.Decoded takeFrame() { Viewer v = viewer; return v == null || v.closed ? null : v.decoded.getAndSet(null); }
    /** Called by the native capture reader. Only swaps a reference; no encoding, copying or network I/O. */
    public void offer(byte[] pixels, int width, int height) {
        Publisher p = publisher; var position = placement;
        if (p == null || p.closed || position == null) return;
        long now = System.nanoTime();
        if (!p.quality.acceptsFrame(p.accepted,now)) return;
        p.accepted = p.quality.acceptedTimestamp(p.accepted,now); p.raw.set(new SharedCastVideo.Capture(pixels, width, height, position));
        synchronized (p.signal) { p.signal.notifyAll(); }
    }
    public synchronized void revoke() { Publisher p = publisher; publisher = null; if (p != null) p.close(); }
    public synchronized void leave() { Viewer v = viewer; viewer = null; if (v != null) v.close(); }
    @Override public synchronized void close() { revoke(); leave(); placement = null; }
    private static String error(Throwable failure) {
        String message = failure.getMessage();
        return message != null && message.startsWith("ui.appcast.share.") ? message : "ui.appcast.share.connectionFailed";
    }
    private static void disconnect(SharedCastConnection connection) { if (connection != null) try { connection.close(); } catch (IOException ignored) { } }
    private final class Publisher {
        final String address; final SharedCastVideo.Quality quality;
        final AtomicReference<SharedCastVideo.Capture> raw = new AtomicReference<>();
        final AtomicReference<SharedCastPacket> encoded = new AtomicReference<>();
        final Object signal = new Object();
        volatile boolean closed, codecBroken, software;
        volatile String status = "ui.appcast.share.connecting", invite = "";
        volatile SharedCastConnection connection;
        volatile SharedCastDirectServer direct;
        volatile SharedCastVideo.Encoder encoder;
        volatile long accepted, sent, writing, encoderEpoch;
        Publisher(String address, SharedCastVideo.Quality quality) { this.address = address; this.quality = quality; }
        void run() {
            ScheduledFuture<?> timeout = null;
            try {
                SharedCastVideo.executable();
                SharedCastInvite endpoint,advertised;
                if(address.isBlank()||address.startsWith("direct://")){
                    var server=SharedCastDirectServer.create(address);
                    synchronized(this){if(closed){server.close();return;}direct=server;}
                    endpoint=server.local();advertised=server.advertised();
                }else{endpoint=SharedCastInvite.endpoint(address);advertised=endpoint;}
                SharedCastConnection opened = SharedCastConnection.publish(endpoint.host(), endpoint.port(),endpoint.pin());
                synchronized (this) { if (closed) { opened.close(); return; } connection = opened; invite = advertised.code(opened.invite()); }
                status = "ui.appcast.share.waitingCapture";
                SharedCastVideo.worker("encode", this::encode);
                timeout = TIMER.scheduleAtFixedRate(() -> {
                    if (!closed && writing != 0 && System.nanoTime() - writing > 4_000_000_000L) {
                        status = "ui.appcast.share.networkSlow"; close();
                    }
                }, 1, 1, TimeUnit.SECONDS);
                while (!closed) {
                    var frame = encoded.getAndSet(null);
                    writing = System.nanoTime();
                    opened.send(frame == null ? new byte[0] : frame.encode());
                    writing = 0;
                    if (frame != null) { sent++; status = "ui.appcast.share.broadcasting"; }
                    synchronized (signal) { if (encoded.get() == null && !closed) signal.wait(500); }
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Throwable failure) { if (!closed) status = error(failure); }
            finally { if (timeout != null) timeout.cancel(false); close(); }
        }
        void encode() {
            SharedCastVideo.Capture last = null; long lastInput = 0;
            try {
                while (!closed) {
                    var frame = raw.getAndSet(null);
                    if (frame == null) {
                        synchronized (signal) { if (raw.get() == null && !closed) signal.wait(100); }
                        if (last == null || System.nanoTime() - lastInput < 250_000_000L) continue;
                        var currentPlacement = placement;
                        frame = new SharedCastVideo.Capture(last.pixels(), last.width(), last.height(), currentPlacement == null ? last.placement() : currentPlacement);
                    }
                    if (closed) break;
                    if(codecBroken){
                        if(encoder!=null)encoder.close();encoder=null;codecBroken=false;
                        if(software)throw new IOException("ui.appcast.share.codecFailed");software=true;
                    }
                    if(encoder!=null&&(encoder.inputWidth!=frame.width()||encoder.inputHeight!=frame.height())){encoder.close();encoder=null;}
                    if (encoder == null) {
                        status = "ui.appcast.share.startingEncoder";
                        long ticket=++encoderEpoch;
                        SharedCastVideo.Encoder created = new SharedCastVideo.Encoder(frame, quality,
                                packet -> { if (!closed&&ticket==encoderEpoch) { encoded.set(packet); synchronized (signal) { signal.notifyAll(); } } },
                                failure -> { if (!closed&&ticket==encoderEpoch) { codecBroken = true; } }, software);
                        synchronized (this) { if (closed) { created.close(); break; } encoder = created; }
                    }
                    try { encoder.write(frame); }
                    catch (IOException failedHardware) {
                        encoder.close(); encoder = null;
                        if (software) throw failedHardware;
                        software = true; codecBroken = false; raw.compareAndSet(null, frame);
                    }
                    last = frame; lastInput = System.nanoTime();
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Throwable failure) { if (!closed) { status = error(failure); close(); } }
        }
        synchronized void close() {
            if (closed) return; closed = true; invite = "";
            disconnect(connection);if(direct!=null)direct.close(); if (encoder != null) encoder.close(); raw.set(null); encoded.set(null);
            synchronized (signal) { signal.notifyAll(); }
        }
    }
    private static final class Viewer {
        final String code;
        final AtomicReference<SharedCastVideo.Decoded> decoded = new AtomicReference<>();
        final ExecutorService work = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "CaeDuex-share-decode"); thread.setDaemon(true); return thread; });
        final LatestFrameDecoder<SharedCastPacket> pending = new LatestFrameDecoder<>(work, this::decode);
        volatile SharedCastConnection connection;
        volatile SharedCastVideo.Decoder decoder;
        volatile boolean closed;
        volatile String status = "ui.appcast.share.connecting";
        volatile long received;
        Viewer(String code) { this.code = code; }
        void run() {
            try {
                var invite = SharedCastInvite.parse(code); SharedCastVideo.executable();
                SharedCastConnection opened = SharedCastConnection.watch(invite.host(), invite.port(), invite.token(),invite.pin());
                synchronized (this) { if (closed) { opened.close(); return; } connection = opened; }
                status = "ui.appcast.share.waitingFrame";
                while (!closed) pending.offer(SharedCastPacket.decode(opened.receive()));
            } catch (Throwable failure) { if (!closed) status = error(failure); }
            finally { close(); }
        }
        void decode(SharedCastPacket packet) {
            try {
                if (closed) return;
                if (decoder != null && (decoder.width != packet.width() || decoder.height != packet.height())) { decoder.close(); decoder = null; }
                if (decoder == null) {
                    SharedCastVideo.Decoder created = new SharedCastVideo.Decoder(packet, frame -> {
                        if (!closed) { decoded.set(frame); received++; status = "ui.appcast.share.watching"; }
                    }, failure -> { if (!closed) { status = "ui.appcast.share.codecFailed"; close(); } });
                    synchronized (this) { if (closed) { created.close(); return; } decoder = created; }
                }
                decoder.write(packet);
            } catch (Throwable failure) { if (!closed) { status = error(failure); close(); } }
        }
        synchronized void close() {
            if (closed) return; closed = true;
            disconnect(connection); pending.close(); work.shutdownNow();
            if (decoder != null) decoder.close(); decoded.set(null);
        }
    }
}
