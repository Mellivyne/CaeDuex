package com.cheatprogram.render;

import com.cheatprogram.util.BuildingFiles.Blueprint;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;

/** Frames each construction region and checks sightlines at the time its blocks are placed. */
public final class BuildingTutorialCamera {
    private static final double[] PITCHES = {22, 38, 55, 72, 9, -8};
    private static final double[] DISTANCE_SCALES = {1, 1.18, 1.44};
    private static final int MAX_SAMPLES = 20;
    private static final double SCREEN_FILL = .82;

    public record Frame(Vec3 eye, Vec3 target, float yaw, float pitch, double halfWidth, double halfHeight) {
        public double insetScale(double aspect) {
            return SCREEN_FILL / Math.max(.5, Math.max(halfHeight, halfWidth / Math.max(.1, aspect)));
        }
    }

    private record Shot(Vec3 back, Vec3 right, Vec3 up, float yaw, float pitch) {}
    private record Fit(double distance, double halfWidth, double halfHeight) {}

    private final Blueprint source;
    private final int[] order;
    private final int[] ranks;
    private final double verticalLimit;
    private final double horizontalLimit;

    public BuildingTutorialCamera(Blueprint source, int[] order, int[] ranks, double fov, double aspect) {
        this.source = source;
        this.order = order;
        this.ranks = ranks;
        verticalLimit = Math.tan(Math.toRadians(Math.clamp(fov, 30.0, 110.0) / 2)) * SCREEN_FILL;
        horizontalLimit = verticalLimit * Math.max(.1, aspect);
    }

    /** Only raise a transition when its direct path crosses construction that already exists. */
    public double transitionRise(Vec3 from, Vec3 to, int placed) {
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 3));
        double rise = 0;
        for (int step = 1; step < steps; step++) {
            double t = (double) step / steps;
            Vec3 eye = from.lerp(to, t);
            if (clearEye(eye, placed)) continue;
            int y = Math.min(source.height() - 1, (int) Math.floor(eye.y + .35));
            while (y < source.height() && !clearEye(new Vec3(eye.x, y + 2.75, eye.z), placed)) y++;
            rise = Math.max(rise, (y + 2.75 - eye.y) / (4 * t * (1 - t)));
        }
        return rise;
    }

    public Frame frame(int from, int to, Frame previous, BooleanSupplier active) {
        int width = source.width(), depth = source.depth();
        int minX = width, minY = source.height(), minZ = depth;
        int maxX = 0, maxY = 0, maxZ = 0;
        for (int i = from; i < to; i++) {
            int index = order[i];
            int x = index % width, y = index / (width * depth), z = index / width % depth;
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        Vec3 target = new Vec3((minX + maxX + 1d) * .5, minY + (maxY - minY + 1d) * .54,
                (minZ + maxZ + 1d) * .5);
        Vec3[] corners = new Vec3[8];
        for (int i = 0; i < corners.length; i++) {
            corners[i] = new Vec3((i & 1) == 0 ? minX : maxX + 1d, (i & 2) == 0 ? minY : maxY + 1d,
                    (i & 4) == 0 ? minZ : maxZ + 1d).subtract(target);
        }
        int samples = Math.min(MAX_SAMPLES, to - from);
        Vec3[] points = new Vec3[samples];
        int[] placed = new int[samples];
        for (int sample = 0; sample < samples; sample++) {
            int index = from + (int) ((long) sample * (to - from - 1) / Math.max(1, samples - 1));
            points[sample] = center(order[index]);
            placed[sample] = index;
        }

        Frame chosen = null;
        double best = Double.NEGATIVE_INFINITY;
        for (int side = 0; side < 12; side++) {
            if (!active.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            for (double pitch : PITCHES) {
                Shot shot = shot(side * 30f, (float) pitch);
                Fit fit = fit(shot, corners);
                for (double scale : DISTANCE_SCALES) {
                    double distance = fit.distance * scale;
                    Vec3 eye = target.add(shot.back.scale(distance));
                    if (!clearEye(eye, to)) continue;
                    double score = 0;
                    for (int sample = 0; sample < samples; sample++) {
                        if (clear(eye, points[sample], placed[sample])) score += 4d / samples;
                        if (clear(eye, points[sample], to)) score += 2d / samples;
                    }
                    score -= .50 * (scale - 1) + .006 * Math.abs(pitch - 32);
                    if (previous != null) {
                        score -= .22 * Math.min(1, eye.distanceTo(previous.eye) / Math.max(4, distance))
                                + .14 * Math.abs(net.minecraft.util.Mth.wrapDegrees(shot.yaw - previous.yaw)) / 180d;
                    }
                    if (score > best) {
                        best = score;
                        chosen = new Frame(eye, target, shot.yaw, shot.pitch, fit.halfWidth, fit.halfHeight);
                    }
                }
            }
        }
        if (chosen != null) return chosen;
        Shot overhead = shot(0, 90);
        Fit fit = fit(overhead, corners);
        double distance = Math.max(fit.distance, source.height() - target.y + 2);
        return new Frame(target.add(overhead.back.scale(distance)), target, overhead.yaw, overhead.pitch,
                fit.halfWidth, fit.halfHeight);
    }

    private static Shot shot(float yaw, float pitch) {
        double azimuth = Math.toRadians(yaw), elevation = Math.toRadians(pitch);
        Vec3 back = new Vec3(Math.sin(azimuth) * Math.cos(elevation), Math.sin(elevation),
                -Math.cos(azimuth) * Math.cos(elevation));
        Vec3 right = new Vec3(-Math.cos(azimuth), 0, -Math.sin(azimuth));
        Vec3 up = back.cross(right);
        return new Shot(back, right, up, yaw, pitch);
    }

    private Fit fit(Shot shot, Vec3[] corners) {
        double distance = 2.6, halfWidth = .5, halfHeight = .5;
        for (Vec3 corner : corners) {
            double x = Math.abs(corner.dot(shot.right)), y = Math.abs(corner.dot(shot.up));
            double projected = Math.max(x / horizontalLimit, y / verticalLimit);
            distance = Math.max(distance, corner.dot(shot.back) + Math.max(.75, projected));
            halfWidth = Math.max(halfWidth, x);
            halfHeight = Math.max(halfHeight, y);
        }
        return new Fit(distance, halfWidth, halfHeight);
    }

    private boolean clear(Vec3 eye, Vec3 point, int visible) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 4));
        int targetX = (int) point.x, targetY = (int) point.y, targetZ = (int) point.z;
        for (int step = 1; step < steps; step++) {
            double t = (double) step / steps;
            int x = (int) Math.floor(eye.x + t * dx);
            int y = (int) Math.floor(eye.y + t * dy);
            int z = (int) Math.floor(eye.z + t * dz);
            if (x == targetX && y == targetY && z == targetZ) return true;
            if (solid(x, y, z, visible)) return false;
        }
        return true;
    }

    private boolean clearEye(Vec3 eye, int visible) {
        if (eye.y < 1.8) return false;
        for (int i = 0; i < 8; i++) {
            int x = (int) Math.floor(eye.x + ((i & 1) == 0 ? -.35 : .35));
            int y = (int) Math.floor(eye.y + ((i & 2) == 0 ? -1.7 : .35));
            int z = (int) Math.floor(eye.z + ((i & 4) == 0 ? -.35 : .35));
            if (solid(x, y, z, visible)) return false;
        }
        return !solid((int) Math.floor(eye.x), (int) Math.floor(eye.y - .67), (int) Math.floor(eye.z), visible);
    }

    private boolean solid(int x, int y, int z, int visible) {
        if (x < 0 || x >= source.width() || y < 0 || y >= source.height() || z < 0 || z >= source.depth()) return false;
        int rank = ranks[(y * source.depth() + z) * source.width() + x];
        return rank > 0 && rank <= visible;
    }

    private Vec3 center(int index) {
        return new Vec3(index % source.width() + .5,
                index / (source.width() * source.depth()) + .5,
                index / source.width() % source.depth() + .5);
    }
}
