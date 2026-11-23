package dev.dzenthai.storybound.client.waypoint;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dzenthai.storybound.client.ShaderPacks;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * A scent's strands: thin glowing lines in the waypoint's colour that run along a trail the way it leads, quicker than
 * its wisps, following its turns and waving a little as they go, like a gust of wind or a thread of scent in the air.
 * Each is a strip a few blocks long, turned to face the camera, thin to nothing at both ends, fading in and out over
 * its short life. Started by {@link TraceTrails} near the player; kept and moved here every client tick, drawn every
 * frame.
 */
public final class ScentStrands {
    /** The most strands at once, so a long scent never piles them up. */
    private static final int MAX = 64;
    private static final double LIFT_WAVE = 0.15;
    private static final double HALF_WIDTH = 0.03;
    private static final float ALPHA = 0.7f;
    private static final RandomSource RANDOM = RandomSource.create();

    /** One strand: where its head is along the trail's points, how it moves and waves, and how long it has lived. */
    private static final class Strand {
        final List<Vec3> points;
        final double[] along;
        final int rgb;
        final double speed = 0.07 + RANDOM.nextDouble() * 0.04;
        final double length = 1.5 + RANDOM.nextDouble();
        final double lift;
        final double sway = 0.12 + RANDOM.nextDouble() * 0.12;
        final double phase = RANDOM.nextDouble() * Mth.TWO_PI;
        final int lifetime = 30 + RANDOM.nextInt(20);
        double head;
        int age;

        Strand(List<Vec3> points, double[] along, double head, double lift, int rgb) {
            this.points = points;
            this.along = along;
            this.head = head;
            this.lift = lift;
            this.rgb = rgb;
        }
    }

    private static final List<Strand> STRANDS = new ArrayList<>();

    private ScentStrands() {}

    /** A strand starting at {@code distance} along the points (their flat length), {@code lift} over them. */
    static void start(List<Vec3> points, double distance, double lift, int rgb) {
        if (STRANDS.size() >= MAX || points.size() < 2) {
            return;
        }
        double[] along = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            along[i] = along[i - 1] + Math.hypot(b.x - a.x, b.z - a.z);
        }
        STRANDS.add(new Strand(points, along, distance, lift, rgb));
    }

    /** Every client tick: the strands move on; spent ones and those past the trail's end go. */
    static void tick(boolean running) {
        if (!running) {
            STRANDS.clear();
            return;
        }
        for (Iterator<Strand> it = STRANDS.iterator(); it.hasNext(); ) {
            Strand strand = it.next();
            strand.age++;
            strand.head += strand.speed;
            if (strand.age >= strand.lifetime || strand.head - strand.length > strand.along[strand.along.length - 1]) {
                it.remove();
            }
        }
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (STRANDS.isEmpty() || event.getStage() != ShaderPacks.worldOverlayStage() || ShaderPacks.renderingShadowPass()
                || minecraft.options.hideGui) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(AreaRings.RING_BAND);
        for (Strand strand : STRANDS) {
            draw(pose, out, camera, strand, partialTick);
        }
        buffers.endBatch(AreaRings.RING_BAND);
    }

    /** The strand as a strip from its tail to its head, each point on the trail moved aside by its wave. */
    private static void draw(PoseStack pose, VertexConsumer out, Vec3 camera, Strand strand, float partialTick) {
        double head = strand.head + strand.speed * partialTick;
        float life = (strand.age + partialTick) / strand.lifetime;
        float fade = ALPHA * Mth.clamp(Math.min(life / 0.2f, (1 - life) / 0.4f), 0, 1);
        int pieces = 14;
        Vec3[] centres = new Vec3[pieces + 1];
        float[] alphas = new float[pieces + 1];
        double end = strand.along[strand.along.length - 1];
        for (int k = 0; k <= pieces; k++) {
            double u = k / (double) pieces;
            double s = head - strand.length * (1 - u);
            if (s < 0 || s > end) {
                continue;
            }
            // The segment holding s, found by halves: a long trail has hundreds of points, drawn every frame.
            int found = java.util.Arrays.binarySearch(strand.along, s);
            int i = Mth.clamp(found >= 0 ? found : -found - 1, 1, strand.along.length - 1);
            Vec3 a = strand.points.get(i - 1);
            Vec3 b = strand.points.get(i);
            double segment = strand.along[i] - strand.along[i - 1];
            Vec3 at = segment < 1.0e-6 ? b : a.lerp(b, (s - strand.along[i - 1]) / segment);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            double flat = Math.max(1.0e-6, Math.hypot(dx, dz));
            double wave = strand.sway * Math.sin(s * 2.2 + strand.phase + (strand.age + partialTick) * 0.15);
            double rise = strand.lift + LIFT_WAVE * Math.sin(s * 1.3 + strand.phase);
            centres[k] = new Vec3(at.x - dz / flat * wave, at.y + rise, at.z + dx / flat * wave);
            alphas[k] = fade * (float) Math.sin(Math.PI * u);
        }
        int r = (strand.rgb >> 16) & 0xFF;
        int g = (strand.rgb >> 8) & 0xFF;
        int b = strand.rgb & 0xFF;
        PoseStack.Pose last = pose.last();
        for (int k = 0; k < pieces; k++) {
            Vec3 from = centres[k];
            Vec3 to = centres[k + 1];
            if (from == null || to == null) {
                continue;
            }
            // Across the strip: square to its way and to the line from the camera, so it is seen face on.
            Vec3 across = to.subtract(from).cross(from.subtract(camera));
            if (across.lengthSqr() < 1.0e-12) {
                continue;
            }
            across = across.normalize().scale(HALF_WIDTH);
            vertex(out, last, camera, from.subtract(across), r, g, b, alphas[k]);
            vertex(out, last, camera, from.add(across), r, g, b, alphas[k]);
            vertex(out, last, camera, to.add(across), r, g, b, alphas[k + 1]);
            vertex(out, last, camera, to.subtract(across), r, g, b, alphas[k + 1]);
        }
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose last, Vec3 camera, Vec3 at, int r, int g, int b, float alpha) {
        out.addVertex(last, (float) (at.x - camera.x), (float) (at.y - camera.y), (float) (at.z - camera.z)).setColor(r, g, b, (int) (alpha * 255));
    }
}
