package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.client.waypoint.TraceTrails;
import dev.dzenthai.storybound.module.waypoint.WaypointTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Recording a trail for a {@code trace} waypoint by walking it: the editor steps aside, as for a pick in the world;
 * a point is taken every {@link #STEP} blocks while the author stands on the ground or swims (so a jump makes no
 * bump), the trail showing as it grows; a click (either mouse button) ends it where they stand and the editor comes
 * back with the points, the straight stretches thinned out ({@link #simplify}). ESC, or going to another dimension,
 * gives up and returns.
 */
public final class DevTraceRecorder {
    /** A point every this many blocks walked. */
    private static final double STEP = 2;
    /** Points off the line through their neighbours by less than this, in blocks, are dropped. */
    private static final double TOLERANCE = 0.4;
    /** Points kept while walking, before thinning: a long walk stops taking more. */
    private static final int RAW_MAX = 8192;
    private static final int COOLDOWN = 4;

    private static @Nullable List<Vec3> points;
    private static @Nullable ResourceLocation dimension;
    private static @Nullable DevModeScreen returnTo;
    private static @Nullable BiConsumer<List<Vec3>, ResourceLocation> onDone;
    private static int cooldown;

    private DevTraceRecorder() {}

    public static boolean isActive() {
        return points != null;
    }

    /** Starts where the author stands; {@code done} gets the points and the dimension once the editor is back. */
    public static void start(DevModeScreen screen, BiConsumer<List<Vec3>, ResourceLocation> done) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        DevWorldPicker.clear();
        points = new ArrayList<>(List.of(minecraft.player.position()));
        dimension = minecraft.level.dimension().location();
        returnTo = screen;
        onDone = done;
        cooldown = COOLDOWN;
        TraceTrails.preview(points, dimension);
        minecraft.setScreen(null);
    }

    private static void clear() {
        points = null;
        dimension = null;
        returnTo = null;
        onDone = null;
        cooldown = 0;
        TraceTrails.preview(null, null);
    }

    public static void tick() {
        List<Vec3> walked = points;
        if (walked == null) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            clear();
            return;
        }
        if (minecraft.screen instanceof PauseScreen || !minecraft.level.dimension().location().equals(dimension)) {
            if (!(minecraft.screen instanceof PauseScreen)) {
                player.displayClientMessage(DevUi.text("trace.other_dimension"), false);
            }
            DevModeScreen screen = returnTo;
            clear();
            minecraft.setScreen(screen != null ? screen : new DevModeScreen(null));
            return;
        }
        if ((player.onGround() || player.isInWater()) && walked.size() < RAW_MAX
                && player.position().distanceTo(walked.getLast()) >= STEP) {
            walked.add(player.position());
            TraceTrails.preview(walked, dimension);
        }
    }

    public static void onInteractionTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        List<Vec3> walked = points;
        if (walked == null) {
            return;
        }
        // While recording, a click ends it: it neither hits nor uses anything.
        event.setCanceled(true);
        event.setSwingHand(false);
        Minecraft minecraft = Minecraft.getInstance();
        if (cooldown > 0 || minecraft.player == null) {
            return;
        }
        Vec3 here = minecraft.player.position();
        if (here.distanceTo(walked.getLast()) > 0.3) {
            walked.add(here);
        }
        List<Vec3> trail = thinTo(simplify(walked), WaypointTrace.MAX_POINTS);
        ResourceLocation where = dimension;
        BiConsumer<List<Vec3>, ResourceLocation> done = onDone;
        DevModeScreen screen = returnTo;
        clear();
        minecraft.execute(() -> {
            minecraft.setScreen(screen != null ? screen : new DevModeScreen(null));
            if (done != null && where != null) {
                done.accept(trail, where);
            }
        });
    }

    /** The points with the straight stretches thinned out (Douglas–Peucker), the first and the last always kept. */
    static List<Vec3> simplify(List<Vec3> walked) {
        if (walked.size() <= 2) {
            return List.copyOf(walked);
        }
        boolean[] keep = new boolean[walked.size()];
        keep[0] = true;
        keep[walked.size() - 1] = true;
        List<int[]> spans = new ArrayList<>(List.of(new int[] {0, walked.size() - 1}));
        while (!spans.isEmpty()) {
            int[] span = spans.removeLast();
            int far = -1;
            double farthest = TOLERANCE;
            for (int i = span[0] + 1; i < span[1]; i++) {
                double off = offLine(walked.get(i), walked.get(span[0]), walked.get(span[1]));
                if (off > farthest) {
                    farthest = off;
                    far = i;
                }
            }
            if (far >= 0) {
                keep[far] = true;
                spans.add(new int[] {span[0], far});
                spans.add(new int[] {far, span[1]});
            }
        }
        List<Vec3> kept = new ArrayList<>();
        for (int i = 0; i < walked.size(); i++) {
            if (keep[i]) {
                kept.add(walked.get(i));
            }
        }
        return kept;
    }

    /** At most {@code max} points, evenly picked, the first and the last kept. */
    static List<Vec3> thinTo(List<Vec3> trail, int max) {
        if (trail.size() <= max) {
            return trail;
        }
        List<Vec3> thinned = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            thinned.add(trail.get((int) Math.round(i * (trail.size() - 1) / (double) (max - 1))));
        }
        return thinned;
    }

    /** How far {@code point} lies from the segment from {@code a} to {@code b}. */
    private static double offLine(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double length = ab.lengthSqr();
        if (length < 1.0e-9) {
            return point.distanceTo(a);
        }
        double t = Math.clamp(point.subtract(a).dot(ab) / length, 0, 1);
        return point.distanceTo(a.add(ab.scale(t)));
    }

    /** What is going on, above the crosshair. */
    public static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        List<Vec3> walked = points;
        if (walked == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int w = graphics.guiWidth();
        Component title = Component.literal("⏺ ").append(DevUi.text("trace.recording"));
        Component hint = DevUi.text("trace.recording_hint", walked.size());
        int boxW = Math.max(font.width(title), font.width(hint)) + 24;
        int boxX = (w - boxW) / 2;
        int boxY = 18;
        graphics.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + 31, DevUi.ACCENT);
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 30, 0xEE16191F);
        graphics.drawString(font, title, (w - font.width(title)) / 2, boxY + 5, DevUi.TEXT, false);
        graphics.drawString(font, hint, (w - font.width(hint)) / 2, boxY + 17, DevUi.DETAIL, false);
    }
}
