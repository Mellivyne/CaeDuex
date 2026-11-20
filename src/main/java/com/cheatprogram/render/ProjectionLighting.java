package com.cheatprogram.render;

import com.cheatprogram.capture.AppCaptureEngine;
import com.cheatprogram.capture.ProjectionLightColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;

/** A client-only light pass on exposed world geometry. */
public final class ProjectionLighting {
    private static final int MAX_FACES = 560;
    private static final int MAX_SOURCES = 4;
    private static final long SCAN_BUDGET_NANOS = 1_400_000;
    private static final long SCAN_INTERVAL_NANOS = 550_000_000;
    private static final WeakHashMap<AppCaptureEngine, Light> LIGHTS = new WeakHashMap<>();
    private static Render3D frame;
    private static int frameSources;
    private static long remainingScanNanos;

    record Vertex(Vec3 point, int color) { }

    private record Face(Vec3 origin, Vec3 tangent, Vec3 bitangent, Vec3 normal, int material) { }

    private record Source(Vec3 center, Vec3 right, Vec3 up, Vec3 normal, float width, float height,
                          float strength, float range) { }

    private static final class Light {
        ClientLevel level;
        AppCastPlane sampledPlane;
        AppCastPlane scanningPlane;
        float sampledRange, scanningRange;
        long nextScan, lastSample;
        int scanIndex;
        List<Face> faces = List.of();
        final ArrayList<Face> scanning = new ArrayList<>();
        final float[] colors = new float[ProjectionLightColors.GRID * ProjectionLightColors.GRID * 3];
        final ArrayList<Vertex> vertices = new ArrayList<>();
    }

    private static final class Offsets {
        static final List<BlockPos> BY_DISTANCE = create();

        private static List<BlockPos> create() {
            var offsets = new ArrayList<BlockPos>();
            for (int y = -17; y <= 17; y++) {
                for (int z = -17; z <= 17; z++) {
                    for (int x = -17; x <= 17; x++) {
                        if (x*x + y*y + z*z <= 17*17) offsets.add(new BlockPos(x, y, z));
                    }
                }
            }
            offsets.sort(Comparator.comparingInt(p -> p.getX()*p.getX() + p.getY()*p.getY() + p.getZ()*p.getZ()));
            return offsets;
        }
    }

    private ProjectionLighting() { }

    public static void draw(Render3D renderer, ClientLevel level, AppCastPlane plane, AppCaptureEngine capture,
                            float strength, float range) {
        if (!Float.isFinite(range) || !Float.isFinite(strength) || strength <= 0) return;
        range = Math.clamp(range, 1, 16);
        if (renderer.camera.distanceToSqr(plane.center()) > (range + 40) * (range + 40)) return;
        if (frame != renderer) {
            frame = renderer;
            frameSources = 0;
            remainingScanNanos = SCAN_BUDGET_NANOS;
        }
        if (frameSources >= MAX_SOURCES) return;
        long now = System.nanoTime();
        int[] samples = capture.projectionLightColors(now);
        if (samples == null) return;
        frameSources++;
        Light light = LIGHTS.computeIfAbsent(capture, ignored -> new Light());
        if (light.level != level) {
            light.level = level;
            light.faces = List.of();
            light.sampledPlane = null;
            light.scanningPlane = null;
            light.lastSample = 0;
            light.nextScan = 0;
        }
        updateColors(light, samples, now);
        updateFaces(light, plane, range, now);
        var source = new Source(plane.center(), plane.right(), plane.up(), plane.normal(),
                plane.width(), plane.height(), Math.min(strength, 4), range);
        light.vertices.clear();
        for (Face face : light.faces.isEmpty() ? light.scanning : light.faces) {
            Vec3 a = face.origin(), b = a.add(face.tangent()), d = a.add(face.bitangent()), c = b.add(face.bitangent());
            int ca = color(light.colors, face, a, source);
            int cb = color(light.colors, face, b, source);
            int cc = color(light.colors, face, c, source);
            int cd = color(light.colors, face, d, source);
            if (((ca | cb | cc | cd) >>> 24) == 0) continue;
            light.vertices.add(new Vertex(a, ca));
            light.vertices.add(new Vertex(b, cb));
            light.vertices.add(new Vertex(c, cc));
            light.vertices.add(new Vertex(d, cd));
        }
        renderer.drawProjectionLight(light.vertices);
    }

    private static void updateColors(Light light, int[] rgb, long now) {
        double blend = light.lastSample == 0 ? 1 : 1 - Math.exp(-(now - light.lastSample) / 65_000_000.0);
        light.lastSample = now;
        for (int i = 0; i < rgb.length; i++) {
            for (int channel = 0; channel < 3; channel++) {
                int index = i * 3 + channel;
                float sample = (rgb[i] >>> (16 - 8 * channel) & 255) / 255f;
                light.colors[index] += (sample - light.colors[index]) * (float) blend;
            }
        }
    }

    private static void updateFaces(Light light, AppCastPlane plane, float range, long now) {
        if (remainingScanNanos <= 0) return;
        if (light.scanningPlane == null) {
            boolean moved = light.sampledPlane != null
                    && light.sampledPlane.center().distanceToSqr(plane.center()) > .75 * .75;
            if (now < light.nextScan && !moved && light.sampledRange == range) return;
            light.scanning.clear();
            light.scanIndex = 0;
            light.scanningPlane = plane;
            light.scanningRange = range;
        }
        long started = System.nanoTime();
        BlockPos center = BlockPos.containing(light.scanningPlane.center());
        List<BlockPos> offsets = Offsets.BY_DISTANCE;
        double radius = light.scanningRange + 1;
        int visited = 0;
        while (light.scanIndex < offsets.size() && light.scanning.size() < MAX_FACES) {
            if (visited >= 480 || visited != 0 && (visited & 7) == 0
                    && System.nanoTime() - started >= remainingScanNanos) break;
            visited++;
            BlockPos offset = offsets.get(light.scanIndex++);
            if (offset.getX()*offset.getX() + offset.getY()*offset.getY() + offset.getZ()*offset.getZ() > radius * radius) {
                light.scanIndex = offsets.size();
                break;
            }
            collectFaces(light, center.offset(offset));
        }
        if (light.scanIndex == offsets.size() || light.scanning.size() >= MAX_FACES) {
            light.faces = List.copyOf(light.scanning);
            light.sampledPlane = light.scanningPlane;
            light.sampledRange = light.scanningRange;
            light.scanningPlane = null;
            light.nextScan = now + SCAN_INTERVAL_NANOS;
        }
        remainingScanNanos -= System.nanoTime() - started;
    }

    private static void collectFaces(Light light, BlockPos pos) {
        ClientLevel level = light.level;
        if (!level.hasChunkAt(pos)) return;
        var state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() && !state.canOcclude()) return;
        var shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) return;
        Vec3 source = light.scanningPlane.center();
        int material = state.getMapColor(level, pos).col;
        for (AABB box : shape.toAabbs()) {
            AABB bounds = box.move(pos.getX(), pos.getY(), pos.getZ());
            for (Direction direction : Direction.values()) {
                if (light.scanning.size() >= MAX_FACES) return;
                BlockPos neighbor = pos.relative(direction);
                if (level.getBlockState(neighbor).isCollisionShapeFullBlock(level, neighbor)) continue;
                Face face = face(bounds, direction, material);
                Vec3 center = face.origin().add(face.tangent().scale(.5)).add(face.bitangent().scale(.5));
                Vec3 incoming = source.subtract(center);
                if (incoming.dot(face.normal()) < .02
                        || incoming.lengthSqr() > light.scanningRange * light.scanningRange + 1) continue;
                var hit = level.clip(new ClipContext(source, center, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, Minecraft.getInstance().player));
                if (hit.getType() == HitResult.Type.MISS) light.scanning.add(face);
            }
        }
    }

    private static Face face(AABB b, Direction side, int material) {
        double e = .0015, x = b.getXsize(), y = b.getYsize(), z = b.getZsize();
        return switch (side) {
            case DOWN -> new Face(new Vec3(b.minX, b.minY - e, b.minZ), new Vec3(x, 0, 0), new Vec3(0, 0, z), new Vec3(0, -1, 0), material);
            case UP -> new Face(new Vec3(b.minX, b.maxY + e, b.minZ), new Vec3(x, 0, 0), new Vec3(0, 0, z), new Vec3(0, 1, 0), material);
            case NORTH -> new Face(new Vec3(b.minX, b.minY, b.minZ - e), new Vec3(x, 0, 0), new Vec3(0, y, 0), new Vec3(0, 0, -1), material);
            case SOUTH -> new Face(new Vec3(b.minX, b.minY, b.maxZ + e), new Vec3(x, 0, 0), new Vec3(0, y, 0), new Vec3(0, 0, 1), material);
            case WEST -> new Face(new Vec3(b.minX - e, b.minY, b.minZ), new Vec3(0, 0, z), new Vec3(0, y, 0), new Vec3(-1, 0, 0), material);
            case EAST -> new Face(new Vec3(b.maxX + e, b.minY, b.minZ), new Vec3(0, 0, z), new Vec3(0, y, 0), new Vec3(1, 0, 0), material);
        };
    }

    private static int color(float[] samples, Face face, Vec3 p, Source source) {
        Vec3 delta = p.subtract(source.center());
        double distance = delta.length();
        if (distance < .01 || distance >= source.range()) return 0;
        Vec3 ray = delta.scale(1 / distance);
        double incidence = Math.max(0, -ray.dot(face.normal()));
        double emission = .48 + .52 * Math.max(0, ray.dot(source.normal()));
        double edge = 1 - distance / source.range();
        double falloff = edge * edge * (3 - 2 * edge) / (1 + .09 * distance * distance);
        int alpha = (int) Math.round(255 * Math.min(.95, source.strength() * 1.18 * incidence * emission * falloff));
        if (alpha == 0) return 0;
        int grid = ProjectionLightColors.GRID;
        double u = Math.clamp(.5 + delta.dot(source.right()) / Math.max(1.25, source.width() + distance * .55), 0, 1) * (grid - 1);
        double v = Math.clamp(.5 - delta.dot(source.up()) / Math.max(1.25, source.height() + distance * .55), 0, 1) * (grid - 1);
        int x = Math.min(grid - 2, (int) u), y = Math.min(grid - 2, (int) v);
        double fx = u - x, fy = v - y;
        int rgb = 0;
        for (int i = 0; i < 3; i++) {
            int top = (y * grid + x) * 3 + i;
            double a = samples[top] * (1 - fx) + samples[top + 3] * fx;
            double b = samples[top + grid * 3] * (1 - fx) + samples[top + grid * 3 + 3] * fx;
            double reflectance = .38 + .62 * ((face.material() >>> (16 - 8 * i) & 255) / 255.0);
            rgb = (rgb << 8) | (int) Math.round(255 * reflectance * (a * (1 - fy) + b * fy));
        }
        return rgb == 0 ? 0 : alpha << 24 | rgb;
    }
}
