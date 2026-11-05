package com.cheatprogram.capture;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Persistent low-delay codecs. Every network picture is an IDR with its own parameter sets. */
public final class SharedCastVideo {
    public enum Quality {
        HD_60(60), HD_120(120), SOURCE(0);
        private final int frameRate;
        Quality(int frameRate){this.frameRate=frameRate;}
        public int frameRate(){return frameRate;}
        public long frameIntervalNanos(){return frameRate==0?0:1_000_000_000L/frameRate;}
        public boolean acceptsFrame(long previous,long now){return frameRate==0||previous==0||now-previous>=frameIntervalNanos();}
        public long acceptedTimestamp(long previous,long now){
            long interval=frameIntervalNanos();
            return frameRate==0||previous==0||now-previous>=interval*2?now:previous+interval;
        }
    }
    public record Capture(byte[] pixels, int width, int height, SharedCastPacket.Placement placement) { }
    public record Decoded(byte[] pixels, int width, int height, SharedCastPacket.Placement placement) { }
    private static volatile String chosenEncoder;
    private SharedCastVideo() { }
    static Thread worker(String name, Runnable work) {
        Thread thread = new Thread(work, "CheatProgram-share-" + name); thread.setDaemon(true); thread.start(); return thread;
    }
    static Path executable() throws IOException {
        String path = System.getenv("PATH");
        if (path != null) for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            try {
                Path candidate = Path.of(directory.replace("\"", ""), System.getProperty("os.name", "").startsWith("Windows") ? "ffmpeg.exe" : "ffmpeg");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
            } catch (RuntimeException ignored) { }
        }
        throw new IOException("ui.appcast.share.ffmpegRequired");
    }
    static String encoder(Path ffmpeg) throws IOException {
        String override = System.getProperty("cheatprogram.cast.encoder", "");
        if (List.of("h264_nvenc", "h264_qsv", "h264_amf", "libx264").contains(override)) return override;
        if (chosenEncoder != null) return chosenEncoder;
        for (String candidate : List.of("h264_nvenc", "h264_qsv", "h264_amf", "libx264")) {
            Process probe = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "color=size=512x512:rate=1", "-frames:v", "1", "-c:v", candidate,
                    "-f", "null", "-").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0) return chosenEncoder = candidate;
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Codec cancelled", interrupted); }
            finally { probe.destroyForcibly(); }
        }
        throw new IOException("ui.appcast.share.codecFailed");
    }
    static List<String> base(Path executable) { return new ArrayList<>(List.of(executable.toString(), "-hide_banner", "-loglevel", "error", "-nostdin", "-filter_threads", "1")); }
    public static final class Encoder implements AutoCloseable {
        private final Process process;
        private final OutputStream input;
        private final ArrayBlockingQueue<SharedCastPacket.Placement> metadata = new ArrayBlockingQueue<>(8);
        private final Consumer<Throwable> failure;
        private volatile boolean closed;
        public final int inputWidth, inputHeight, width, height;
        public final String name;
        public Encoder(Capture initial, Quality quality, Consumer<SharedCastPacket> output, Consumer<Throwable> failure) throws IOException {
            this(initial, quality, output, failure, false);
        }
        public Encoder(Capture initial, Quality quality, Consumer<SharedCastPacket> output, Consumer<Throwable> failure, boolean software) throws IOException {
            checkRaw(initial.pixels, initial.width, initial.height);
            this.failure = failure; inputWidth = initial.width; inputHeight = initial.height;
            double fit=Math.min(1920.0/inputWidth,1080.0/inputHeight);
            width = quality != Quality.SOURCE ? Math.max(2,(int)(inputWidth*fit)/2*2) : (inputWidth + 1) / 2 * 2;
            height = quality != Quality.SOURCE ? Math.max(2,(int)(inputHeight*fit)/2*2) : (inputHeight + 1) / 2 * 2;
            Path ffmpeg = executable(); name = software || width < 256 || height < 256 ? "libx264" : encoder(ffmpeg);
            List<String> command = base(ffmpeg);
            command.addAll(List.of("-threads", "2", "-f", "rawvideo", "-pixel_format", "bgra", "-video_size", inputWidth + "x" + inputHeight,
                    "-framerate", quality == Quality.SOURCE ? "240" : Integer.toString(quality.frameRate()), "-probesize", "32", "-analyzeduration", "0", "-fpsprobesize", "0", "-i", "pipe:0", "-an", "-sn", "-dn"));
            String filter = quality != Quality.SOURCE
                    ? "scale=" + width + ":" + height + ":flags=fast_bilinear"
                    : "pad=" + width + ":" + height + ":0:0";
            boolean fullChroma = quality == Quality.SOURCE && (name.equals("h264_nvenc") || name.equals("libx264"));
            command.addAll(List.of("-vf", filter, "-c:v", name, "-pix_fmt", fullChroma ? "yuv444p" : "yuv420p", "-threads", "2", "-g", "1", "-bf", "0"));
            switch (name) {
                case "h264_nvenc" -> command.addAll(List.of("-preset", "p1", "-tune", "ull", "-zerolatency", "1", "-delay", "0", "-rc", "constqp", "-qp", quality == Quality.SOURCE ? "12" : "23", "-rc-lookahead", "0"));
                case "h264_qsv" -> command.addAll(List.of("-preset", "veryfast", "-async_depth", "1", "-global_quality", quality == Quality.SOURCE ? "14" : "23", "-look_ahead", "0"));
                case "h264_amf" -> command.addAll(List.of("-usage", "ultralowlatency", "-quality", "speed", "-rc", "cqp", "-qp_i", quality == Quality.SOURCE ? "14" : "23"));
                default -> command.addAll(List.of("-preset", "ultrafast", "-tune", "zerolatency", "-crf", quality == Quality.SOURCE ? "12" : "23", "-x264-params", "repeat-headers=1:aud=1:scenecut=0"));
            }
            command.addAll(List.of("-fps_mode", "passthrough", "-bsf:v", "h264_metadata=aud=insert,dump_extra=freq=keyframe", "-flush_packets", "1", "-f", "h264", "pipe:1"));
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            input = process.getOutputStream();
            worker("encoder-output", () -> {
                try {
                    accessUnits(process.getInputStream(), bytes -> {
                        var placement = metadata.poll();
                        if (placement != null && !closed) {
                            var p=placement;
                            var adjusted=new SharedCastPacket.Placement(p.dimension(),p.x(),p.y(),p.z(),p.width(),p.width()*height/width,p.yaw(),p.pitch(),p.roll(),p.curvature(),p.verticalCurve(),p.segments());
                            output.accept(new SharedCastPacket(adjusted, width, height, bytes));
                        }
                    });
                    if (!closed) failure.accept(new IOException("ui.appcast.share.codecFailed"));
                } catch (Throwable problem) { if (!closed) failure.accept(problem); }
            });
        }
        public void write(Capture frame) throws IOException {
            checkRaw(frame.pixels, inputWidth, inputHeight);
            if (closed || !metadata.offer(frame.placement)) return;
            input.write(frame.pixels); input.flush();
        }
        @Override public void close() { closed = true; process.destroyForcibly(); closeQuietly(input); closeQuietly(process.getInputStream()); metadata.clear(); }
    }
    public static final class Decoder implements AutoCloseable {
        private final Process process;
        private final OutputStream input;
        private final ArrayBlockingQueue<SharedCastPacket.Placement> metadata = new ArrayBlockingQueue<>(8);
        private volatile boolean closed;
        public final int width, height;
        public Decoder(SharedCastPacket first, Consumer<Decoded> output, Consumer<Throwable> failure) throws IOException {
            width = first.width(); height = first.height();
            List<String> command = base(executable());
            command.addAll(List.of("-max_alloc", "134217728", "-threads", "1", "-flags", "low_delay", "-max_pixels", "33554432",
                    "-f", "h264", "-probesize", "32", "-analyzeduration", "0", "-i", "pipe:0", "-an", "-sn", "-dn",
                    "-threads", "2", "-vf", "scale=" + width + ":" + height, "-fps_mode", "passthrough", "-pix_fmt", "bgra", "-flush_packets", "1", "-f", "rawvideo", "pipe:1"));
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            input = process.getOutputStream();
            worker("decoder-output", () -> {
                try {
                    InputStream raw = process.getInputStream(); int length = Math.multiplyExact(Math.multiplyExact(width, height), 4);
                    while (!closed) {
                        byte[] bytes = raw.readNBytes(length);
                        if (bytes.length != length) throw new EOFException("Video decoder stopped");
                        var placement = metadata.poll();
                        if (placement != null && !closed) output.accept(new Decoded(bytes, width, height, placement));
                    }
                } catch (Throwable problem) { if (!closed) failure.accept(problem); }
            });
        }
        public void write(SharedCastPacket frame) throws IOException {
            if (closed || !metadata.offer(frame.placement())) return;
            input.write(frame.video()); input.flush();
        }
        @Override public void close() { closed = true; process.destroyForcibly(); closeQuietly(input); closeQuietly(process.getInputStream()); metadata.clear(); }
    }
    /** Split at AUD headers, including NAL start codes split across pipe reads. */
    static void accessUnits(InputStream input, Consumer<byte[]> output) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(); byte[] buffer = new byte[65536];
        int tail = -1; boolean seen = false;
        for (int count; (count = input.read(buffer)) != -1;) for (int i = 0; i < count; i++) {
            int value = buffer[i] & 255; frame.write(value); tail = tail << 8 | value;
            if ((tail & 0xffffff00) == 0x00000100 && (value & 31) == 9) {
                if (seen) {
                    byte[] bytes = frame.toByteArray(); int length = bytes.length - 4;
                    if (length > 4 && bytes[length - 1] == 0) length--; // optional fourth start-code byte
                    if (length > 8) output.accept(Arrays.copyOf(bytes, length));
                    frame.reset(); frame.write(new byte[]{0, 0, 1, (byte) value});
                }
                seen = true;
            }
            if (frame.size() > SharedCastPacket.MAX_BYTES - 1024) throw new IOException("Video frame too large");
        }
        if (seen && frame.size() > 8) output.accept(frame.toByteArray());
    }
    static void checkRaw(byte[] bytes, int width, int height) throws IOException {
        if (width < 2 || height < 2 || width > 8192 || height > 8192 || (long) width * height > 33554432 || bytes.length != (long) width * height * 4)
            throw new IOException("Invalid capture dimensions");
    }
    private static void closeQuietly(Closeable stream) { try { stream.close(); } catch (IOException ignored) { } }
}
