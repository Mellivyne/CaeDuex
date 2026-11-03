package com.cheatprogram.capture;

import java.io.*;

/** Wire format is independent of Minecraft classes, registries, mappings and loader versions. */
public record SharedCastPacket(Placement placement, int width, int height, byte[] video) {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public record Placement(String dimension, double x, double y, double z, float width, float height,
                            float yaw, float pitch, float roll, float curvature, boolean verticalCurve, int segments) {
        public Placement {
            if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || dimension.length() > 256
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 32_000_000 || Math.abs(z) > 32_000_000 || Math.abs(y) > 32_000_000
                    || !Float.isFinite(width) || !Float.isFinite(height) || width < .05 || width > 64 || height < .05 || height > 128
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(roll)
                    || !Float.isFinite(curvature) || Math.abs(curvature) > 160 || segments < 8 || segments > 128)
                throw new IllegalArgumentException("Invalid screen geometry");
        }
    }
    public SharedCastPacket {
        if (placement == null || width < 2 || height < 2 || width > 8192 || height > 8192 || (long) width * height > 33554432
                || video == null || video.length < 8 || video.length > MAX_BYTES - 1024) throw new IllegalArgumentException("Invalid video packet");
    }
    public byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream(video.length + 256);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x43505631); // CPV1: an independently decodable H.264 access unit, then geometry.
            out.writeInt(width); out.writeInt(height);
            var p = placement;
            out.writeUTF(p.dimension); out.writeDouble(p.x); out.writeDouble(p.y); out.writeDouble(p.z);
            out.writeFloat(p.width); out.writeFloat(p.height); out.writeFloat(p.yaw); out.writeFloat(p.pitch);
            out.writeFloat(p.roll); out.writeFloat(p.curvature); out.writeBoolean(p.verticalCurve); out.writeInt(p.segments);
            out.writeInt(video.length); out.write(video);
        }
        return bytes.toByteArray();
    }
    public static SharedCastPacket decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Invalid media length");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x43505631) throw new IOException("ui.appcast.share.wrongType");
            int w = in.readInt(), h = in.readInt();
            Placement p = new Placement(in.readUTF(), in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readBoolean(), in.readInt());
            int size = in.readInt();
            if (size < 8 || size != in.available()) throw new IOException("Invalid video length");
            return new SharedCastPacket(p, w, h, in.readNBytes(size));
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid shared screen", invalid); }
    }
}
