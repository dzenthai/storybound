package dev.dzenthai.storybound.module.scene;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * A shot's 3D camera path through two or more control points ({@code from}, optional {@code via} points, {@code to}).
 * Two points fly along an exact straight segment; three or more fly along a centripetal Catmull-Rom spline
 * ({@code alpha = 0.5}) parameterized by arc length so speed and easing stay even across unevenly spaced points.
 */
public final class ScenePath {
    private static final int SAMPLES_PER_SEGMENT = 20;
    private static final double EPS = 1.0e-4;

    private final List<Vec3> control;
    private final List<Vec3> samples;
    private final double[] cumulative;
    private final double totalLength;

    private ScenePath(List<Vec3> control) {
        this.control = control;
        if (control.size() <= 2) {
            Vec3 a = control.getFirst();
            Vec3 b = control.getLast();
            this.samples = List.of(a, b);
            this.totalLength = a.distanceTo(b);
            this.cumulative = new double[] {0.0, this.totalLength};
            return;
        }
        int segments = control.size() - 1;
        int totalSamples = segments * SAMPLES_PER_SEGMENT + 1;
        List<Vec3> baked = new ArrayList<>(totalSamples);
        double[] dist = new double[totalSamples];
        int idx = 0;
        double acc = 0.0;
        for (int seg = 0; seg < segments; seg++) {
            Vec3 p0 = seg == 0 ? reflect(control.get(0), control.get(1)) : control.get(seg - 1);
            Vec3 p1 = control.get(seg);
            Vec3 p2 = control.get(seg + 1);
            Vec3 p3 = seg + 2 < control.size() ? control.get(seg + 2) : reflect(control.get(segments), control.get(segments - 1));
            int steps = seg == segments - 1 ? SAMPLES_PER_SEGMENT : SAMPLES_PER_SEGMENT - 1;
            for (int step = 0; step <= steps; step++) {
                double s = step / (double) SAMPLES_PER_SEGMENT;
                Vec3 point = centripetal(p0, p1, p2, p3, s);
                if (idx > 0) {
                    acc += baked.get(idx - 1).distanceTo(point);
                }
                baked.add(point);
                dist[idx++] = acc;
            }
        }
        this.samples = List.copyOf(baked);
        this.cumulative = dist;
        this.totalLength = acc;
    }

    /** Builds a path from an ordered list of at least one point (a single point becomes a stationary path). */
    public static ScenePath of(List<Vec3> points) {
        if (points.isEmpty()) {
            return new ScenePath(List.of(Vec3.ZERO, Vec3.ZERO));
        }
        if (points.size() == 1) {
            return new ScenePath(List.of(points.getFirst(), points.getFirst()));
        }
        return new ScenePath(List.copyOf(points));
    }

    /** Builds a path from {@code from}, optional intermediate {@code via} points, and {@code to}. */
    public static ScenePath of(Vec3 from, List<Vec3> via, Vec3 to) {
        if (via.isEmpty()) {
            return new ScenePath(List.of(from, to));
        }
        List<Vec3> all = new ArrayList<>(via.size() + 2);
        all.add(from);
        all.addAll(via);
        all.add(to);
        return new ScenePath(List.copyOf(all));
    }

    /** True arc length of the path in blocks. */
    public double length() {
        return totalLength;
    }

    /** Sampled points along the path for drawing in the editor. */
    public List<Vec3> samples() {
        return samples;
    }

    /** Number of control points in this path ({@code from}, {@code via...}, {@code to}). */
    public int controlCount() {
        return control.size();
    }

    /** Normalized arc-length fraction {@code u} in {@code [0, 1]} at which the path passes through control point {@code index}. */
    public double parameterOfControl(int index) {
        int clamped = Math.max(0, Math.min(control.size() - 1, index));
        if (control.size() <= 2) {
            return clamped == 0 ? 0.0 : 1.0;
        }
        if (totalLength < 1.0e-9) {
            return clamped / (double) (control.size() - 1);
        }
        return cumulative[clamped * SAMPLES_PER_SEGMENT] / totalLength;
    }

    /** Position at arc-length fraction {@code u} in {@code [0, 1]}. */
    public Vec3 pointAt(double u) {
        double clamped = Math.max(0.0, Math.min(1.0, u));
        if (control.size() <= 2) {
            return control.getFirst().lerp(control.getLast(), clamped);
        }
        if (totalLength < 1.0e-9) {
            return control.getFirst();
        }
        if (clamped <= 0.0) {
            return samples.getFirst();
        }
        if (clamped >= 1.0) {
            return samples.getLast();
        }
        double target = clamped * totalLength;
        int lo = 0;
        int hi = cumulative.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] <= target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = cumulative[hi] - cumulative[lo];
        double alpha = span > 1.0e-9 ? (target - cumulative[lo]) / span : 0.0;
        return samples.get(lo).lerp(samples.get(hi), alpha);
    }

    /** Direction of travel at arc-length fraction {@code u} in {@code [0, 1]} (not normalized). */
    public Vec3 tangentAt(double u) {
        if (control.size() <= 2) {
            return control.getLast().subtract(control.getFirst());
        }
        double clamped = Math.max(0.0, Math.min(1.0, u));
        double h = 0.002;
        double a = Math.max(0.0, clamped - h);
        double b = Math.min(1.0, clamped + h);
        return pointAt(b).subtract(pointAt(a));
    }

    private static Vec3 reflect(Vec3 end, Vec3 neighbor) {
        return end.scale(2.0).subtract(neighbor);
    }

    /** Centripetal Catmull-Rom (alpha = 0.5) via Barry-Goldman pyramid: no loops or cusps on uneven control points. */
    private static Vec3 centripetal(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double s) {
        double dt0 = Math.max(EPS, Math.sqrt(p0.distanceTo(p1)));
        double dt1 = Math.max(EPS, Math.sqrt(p1.distanceTo(p2)));
        double dt2 = Math.max(EPS, Math.sqrt(p2.distanceTo(p3)));
        double t0 = 0.0;
        double t1 = dt0;
        double t2 = t1 + dt1;
        double t3 = t2 + dt2;
        double t = t1 + s * dt1;

        Vec3 a1 = p0.lerp(p1, (t - t0) / (t1 - t0));
        Vec3 a2 = p1.lerp(p2, (t - t1) / (t2 - t1));
        Vec3 a3 = p2.lerp(p3, (t - t2) / (t3 - t2));
        Vec3 b1 = a1.lerp(a2, (t - t0) / (t2 - t0));
        Vec3 b2 = a2.lerp(a3, (t - t1) / (t3 - t1));
        return b1.lerp(b2, (t - t1) / (t2 - t1));
    }
}
