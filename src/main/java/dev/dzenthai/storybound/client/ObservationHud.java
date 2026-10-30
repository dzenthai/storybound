package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.client.dialogue.TalkHint;
import dev.dzenthai.storybound.core.logic.trigger.ObservationTracker;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Observing, around the crosshair (October 30, 2026): a pixel ring that fills clockwise from the top, and under it what
 * is observed with its percent; at the end the ring flashes gold and the words say it is done. The server decides when
 * it counts; the client draws it every frame: it checks its own crosshair on the target the server named (with the
 * server's margin), so the ring stops the very frame the player looks away and moves on smoothly while they look,
 * never running far ahead of the server. It fades in and out. Before, the same day: a bar under the crosshair that
 * followed the server's few numbers a second, ran on after the player looked away and jumped back.
 */
public final class ObservationHud {
    private static final int PAPER = 0xF7E4C2;
    private static final int OUTLINE = 0xAD794B;
    private static final int GOLD = 0xE8C170;
    private static final int TRACK = 0x2A1F16;
    /** The ring: outer radius and thickness, in GUI pixels. */
    private static final int RADIUS = 11;
    private static final int THICKNESS = 2;
    private static final float MS_PER_TICK = 50f;
    /** How far the ring may run ahead of the server's count (in ticks of looking), so it never has to jump back far. */
    private static final int LEAD_TICKS = 2 * ObservationTracker.INTERVAL + 2;
    private static final long FADE_MS = 160;
    private static final long DONE_MS = 900;
    /** With nothing heard for this long, it goes by itself (a lost packet, a world left). */
    private static final long STALE_MS = 1000;

    /** The ring's pixels, clockwise from the top: drawn in this order as it fills. */
    private static final List<int[]> RING = ring();

    private static @Nullable Component label;
    private static float server;
    private static float perTick;
    private static int entity = -1;
    private static @Nullable BlockPos pos;
    private static long heard;
    /** What is shown, moved on every frame. */
    private static float shown;
    private static boolean looking;
    private static long lastFrame;
    private static long appeared;
    private static long doneAt;
    private static long hideAt;

    private ObservationHud() {}

    public static void update(@Nullable Component text, float progress, float rate, boolean active, int target, @Nullable BlockPos block) {
        long now = System.currentTimeMillis();
        if (text == null) {
            if (label != null && doneAt == 0) {
                hideAt = now + FADE_MS;
            }
            return;
        }
        if (label == null || hideAt > 0 && doneAt == 0) {
            appeared = now;
            shown = progress;
        }
        hideAt = 0;
        label = text;
        heard = now;
        perTick = rate;
        if (progress >= 1f) {
            if (doneAt == 0) {
                doneAt = now;
            }
            server = 1f;
            return;
        }
        doneAt = 0;
        // The server's count is behind the screen by a check and the way here: the ring keeps its own unless they
        // differ much (a reset, another observation shown).
        if (Math.abs(progress - shown) > (LEAD_TICKS + 2) * rate || progress == 0) {
            shown = progress;
        }
        server = progress;
        entity = target;
        pos = block;
        looking = active;
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        long frame = now - lastFrame;
        lastFrame = now;
        if (label == null || minecraft.player == null) {
            return;
        }
        if (doneAt > 0 && now - doneAt > DONE_MS) {
            label = null;
            doneAt = 0;
            return;
        }
        if (hideAt > 0 && now > hideAt || hideAt == 0 && doneAt == 0 && now - heard > STALE_MS) {
            label = null;
            hideAt = 0;
            return;
        }
        if (minecraft.options.hideGui) {
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(true);
        boolean done = doneAt > 0;
        if (done) {
            shown = Math.min(1f, shown + frame / 120f);
        } else {
            looking = looksAt(minecraft, partial);
            if (looking && frame < 250) {
                // Moves on at the observation's own pace, but not far past what the server counted.
                shown = Math.min(Math.min(server + LEAD_TICKS * perTick, 0.995f), shown + frame / MS_PER_TICK * perTick);
            }
        }
        float fadeIn = Mth.clamp((now - appeared) / (float) FADE_MS, 0, 1);
        float fadeOut = hideAt > 0 ? Mth.clamp((hideAt - now) / (float) FADE_MS, 0, 1) : done ? Mth.clamp((DONE_MS - (now - doneAt)) / 250f, 0, 1) : 1;
        float alpha = fadeIn * fadeOut * (looking || done ? 1f : 0.55f);
        if (alpha <= 0.02f) {
            return;
        }
        int centerX = graphics.guiWidth() / 2;
        int centerY = graphics.guiHeight() / 2;
        drawRing(graphics, centerX, centerY, shown, done, alpha, now);
        Font font = minecraft.font;
        Component line = done ? Component.translatable("storybound.observation.done")
                : Component.empty().append(label).append("  " + Math.round(shown * 100) + "%");
        int a = Math.round(alpha * 255) << 24;
        // Under the talk hint when the NPC observed can be talked to: both are drawn under the crosshair.
        int textY = TalkHint.visible() ? centerY + TalkHint.bottom() + 4 : centerY + RADIUS + 5;
        graphics.drawString(font, line, centerX - font.width(line) / 2, textY, a | (done ? GOLD : PAPER), true);
    }

    private static void drawRing(GuiGraphics graphics, int centerX, int centerY, float progress, boolean done, float alpha, long now) {
        int filled = Math.round(RING.size() * progress);
        // At the end, a short glow travels round the ring.
        float glow = done ? Mth.clamp((now - doneAt) / 300f, 0, 1) : 0;
        int a = Math.round(alpha * 255);
        for (int i = 0; i < RING.size(); i++) {
            int[] pixel = RING.get(i);
            int x = centerX + pixel[0];
            int y = centerY + pixel[1];
            int color;
            if (i < filled) {
                color = done && i <= RING.size() * glow ? GOLD : pixel[2] == 1 ? GOLD : OUTLINE;
                color |= a << 24;
            } else {
                color = (Math.round(a * 0.7f) << 24) | TRACK;
            }
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    /**
     * The pixels of a ring {@link #THICKNESS} wide inside {@link #RADIUS}, by angle from the top, clockwise; the inner
     * edge is marked (a lighter line, as light falls on the fill).
     */
    private static List<int[]> ring() {
        List<int[]> pixels = new ArrayList<>();
        for (int y = -RADIUS; y < RADIUS; y++) {
            for (int x = -RADIUS; x < RADIUS; x++) {
                double distance = Math.hypot(x + 0.5, y + 0.5);
                if (distance < RADIUS && distance >= RADIUS - THICKNESS) {
                    pixels.add(new int[]{x, y, distance < RADIUS - THICKNESS + 1 ? 1 : 0});
                }
            }
        }
        pixels.sort((p, q) -> Double.compare(angle(p), angle(q)));
        return pixels;
    }

    private static double angle(int[] pixel) {
        double angle = Math.atan2(pixel[0] + 0.5, -(pixel[1] + 0.5));
        return angle < 0 ? angle + 2 * Math.PI : angle;
    }

    /** Whether the crosshair is on the target now, as the server decides it: within half a block of its box, nothing nearer. */
    private static boolean looksAt(Minecraft minecraft, float partial) {
        Entity camera = minecraft.getCameraEntity();
        if (camera == null || minecraft.level == null) {
            return false;
        }
        Vec3 eye = camera.getEyePosition(partial);
        Vec3 end = eye.add(camera.getViewVector(partial).scale(64));
        AABB box;
        if (entity >= 0) {
            Entity target = minecraft.level.getEntity(entity);
            if (target == null || !target.isAlive()) {
                return false;
            }
            box = target.getBoundingBox().inflate(ObservationTracker.AIM_MARGIN);
        } else if (pos != null) {
            box = new AABB(pos).inflate(ObservationTracker.AIM_MARGIN);
        } else {
            return looking;
        }
        var hit = box.clip(eye, end);
        if (hit.isEmpty()) {
            return false;
        }
        // A block in front of it hides it (the crosshair's own target, within reach).
        HitResult crosshair = minecraft.hitResult;
        return !(crosshair instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK && !block.getBlockPos().equals(pos)
                && block.getLocation().distanceTo(eye) + 0.1 < hit.get().distanceTo(eye));
    }
}
