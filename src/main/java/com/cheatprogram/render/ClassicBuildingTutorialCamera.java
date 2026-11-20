package com.cheatprogram.render;

import com.cheatprogram.util.BuildingFiles.Blueprint;
import net.minecraft.world.phys.Vec3;

/** Reproduces the original tutorial framing around the center and top of each section. */
public final class ClassicBuildingTutorialCamera {
    public record Frame(Vec3 eye, Vec3 target, float yaw, float pitch, double span) {}

    private final Blueprint source;
    private final int[] order;
    private final int[] ranks;

    public ClassicBuildingTutorialCamera(Blueprint source, int[] order, int[] ranks) {
        this.source = source;
        this.order = order;
        this.ranks = ranks;
    }

    public Frame frame(int from, int to) {
        Vec3 low = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 high = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        for (int i = from; i < to; i++) {
            Vec3 point = center(order[i]);
            low = new Vec3(Math.min(low.x, point.x), Math.min(low.y, point.y), Math.min(low.z, point.z));
            high = new Vec3(Math.max(high.x, point.x), Math.max(high.y, point.y), Math.max(high.z, point.z));
        }
        if (to <= from) return null;
        Vec3 middle = low.add(high).scale(.5);
        Vec3 target = new Vec3(middle.x, high.y + .5, middle.z);
        double span = Math.max(2, Math.max(high.x - low.x + 1,
                Math.max(high.y - low.y + 1, high.z - low.z + 1)));
        double distance = Math.max(5, span * 1.05);
        Vec3 chosen = null;
        double best = Double.NEGATIVE_INFINITY;
        // Interior views and high exterior views compete on visible construction blocks.
        for (double range : new double[]{1, .55, .25}) {
            for (int side = 0; side < 12; side++) {
                double angle = side * Math.PI / 6;
                for (double rise : new double[]{.6, 1.1}) {
                    Vec3 eye = target.add(Math.sin(angle) * distance * range,
                            distance * range * rise, Math.cos(angle) * distance * range);
                    if (!clearEye(eye, to)) continue;
                    int visible = 0;
                    int samples = Math.min(15, to - from);
                    for (int sample = 0; sample < samples; sample++) {
                        int index = from + (int) ((long) sample * (to - from - 1) / Math.max(1, samples - 1));
                        if (clear(eye, center(order[index]), to)) visible++;
                    }
                    double score = visible * 20d / samples + (clear(eye, target, to) ? 8 : 0)
                            - Math.abs(rise - .6) - (range < 1 ? .35 : 0);
                    if (score > best) {
                        best = score;
                        chosen = eye;
                    }
                }
            }
        }
        if (chosen == null) chosen = target.add(distance, source.height() + 4, distance);
        Vec3 direction = target.subtract(chosen);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y,
                Math.hypot(direction.x, direction.z)));
        return new Frame(chosen, target, yaw, pitch, span);
    }

    public boolean clear(Vec3 from, Vec3 to, int visible) {
        Vec3 delta = to.subtract(from);
        int steps = Math.max(1, (int) Math.ceil(delta.length() * 4));
        for (int step = 0; step < steps; step++) {
            Vec3 point = from.add(delta.scale((double) step / steps));
            if (point.distanceToSqr(to) < 1.2) break;
            if (solid(point.x, point.y, point.z, visible)) return false;
        }
        return true;
    }

    private boolean clearEye(Vec3 eye, int visible) {
        for (double x : new double[]{-.35, .35})
            for (double y : new double[]{-.35, .35})
                for (double z : new double[]{-.35, .35})
                    if (solid(eye.x + x, eye.y + y, eye.z + z, visible)) return false;
        return true;
    }

    private boolean solid(double px, double py, double pz, int visible) {
        int x = (int) Math.floor(px), y = (int) Math.floor(py), z = (int) Math.floor(pz);
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
