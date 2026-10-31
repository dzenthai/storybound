package dev.dzenthai.storybound.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dialogue.TalkHint;
import dev.dzenthai.storybound.core.logic.trigger.ObservationTracker;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Observing, under the crosshair: a bar that fills from the left, and under it what is observed with its percent; at
 * the end the fill turns gold from the left and the words say it is done. The server decides when it counts; the
 * client draws it every frame: it checks its own crosshair on the target the server named (with the server's margin),
 * so the bar stops the very frame the player looks away and moves on smoothly while they look, never running far
 * ahead of the server. It fades in and out. The scroll arrows' soft click sounds at each tenth, rising, and a click
 * that settles a step lower at the end, as if it clicked into place.
 * <p>
 * Looking away holds the bar, dimmed, while the server keeps the progress ({@link ObservationTracker#GRACE}); once it
 * is lost the fill runs back to nothing, its percent counting down, and only then fades. Looked at again on the way
 * down, it turns and fills from where it is.
 * <p>
 * The bar is the author's art ({@code textures/gui/observation/bar.png}, from {@code ui-kit/concepts/documents/
 * newloader.png}, October 31, 2026; before, a pixel ring round the crosshair drawn by the code, then the same day a
 * 73 × 7 bar drawn a step larger than the HUD): five strips of {@link #BAR_WIDTH} × {@link #BAR_HEIGHT}, the frame and
 * the full fill for a light background, the full fill in gold, the frame and the full fill for a dark one, each fill
 * strip with its tip (the chevron spark, {@link #TIP} columns) beside it. While it grows the fill is the strip's left
 * part with the tip at its end; full, it is the whole strip, its right end as round as its left, up to the frame. The bar and its words are in HUD pixels ({@link HudScale}), as the rest of the HUD: the art is drawn large
 * enough for it (2 window pixels at 1080 by default, 220 × 22).
 * <p>
 * Which variant shows follows how light the picture under the bar is ({@link Backdrop}), with room between the two
 * thresholds so it does not flicker, crossfading; the words under it go dark with a light edge over a light picture,
 * and light with a dark edge over a dark one.
 */
public final class ObservationHud {
    private static final ResourceLocation BAR = Storybound.id("textures/gui/observation/bar.png");
    private static final int BAR_WIDTH = 110;
    private static final int BAR_HEIGHT = 11;
    /** Where the fill starts, and how far its tip's end goes before it is full (the tip one column off the frame's gap). */
    private static final int FILL_LEFT = 5;
    private static final int TRAVEL = 99;
    /** The tip, beside each fill strip in the texture. */
    private static final int TIP = 3;
    private static final int TIP_U = BAR_WIDTH;
    private static final int TEXTURE_WIDTH = BAR_WIDTH + TIP;
    /** The strips: for a light background, the gold of the end, for a dark background. */
    private static final int FRAME = 0;
    private static final int FILL = BAR_HEIGHT;
    private static final int GOLD_FILL = 2 * BAR_HEIGHT;
    private static final int FRAME_ON_DARK = 3 * BAR_HEIGHT;
    private static final int FILL_ON_DARK = 4 * BAR_HEIGHT;
    private static final int TEXTURE_HEIGHT = 5 * BAR_HEIGHT;
    /** From the crosshair's edge to the bar's top, in HUD pixels. */
    private static final int BELOW_CROSSHAIR = 2;
    /** The picture under the bar darker than this takes the dark variant; lighter than the second, the light one. */
    private static final float DARK_BELOW = 0.33f;
    private static final float LIGHT_ABOVE = 0.43f;
    private static final long SWITCH_MS = 250;
    private static final int GOLD = UiPalette.GOLD_LIGHT;
    /** Running back, the fill covers its way in about this many milliseconds at first, slowing to the end. */
    private static final float DRAIN_MS = 200f;
    private static final float MS_PER_TICK = 50f;
    /** How far the bar may run ahead of the server's count (in ticks of looking), so it never has to jump back far. */
    private static final int LEAD_TICKS = 2 * ObservationTracker.INTERVAL + 2;
    private static final long FADE_MS = 160;
    private static final long DONE_MS = 900;
    /** With nothing heard for this long, it goes by itself (a lost packet, a world left). */
    private static final long STALE_MS = 1000;

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
    /** The tenth of the ring last chimed, so each is heard once on the way up. */
    private static int chimed;
    /** When the settling click of the end is due; 0 for none. */
    private static long settleAt;
    /** The server lost the progress: the fill runs back to nothing, then fades. */
    private static boolean draining;
    /** How far the bar has gone to its variant for a dark background, 0 to 1; and where it is going. */
    private static float dark;
    private static boolean onDark;

    private ObservationHud() {}

    public static void update(@Nullable Component text, float progress, float rate, boolean active, int target, @Nullable BlockPos block) {
        long now = System.currentTimeMillis();
        if (text == null) {
            if (label != null && doneAt == 0 && hideAt == 0) {
                if (shown > 0.005f) {
                    draining = true;
                    server = 0;
                    looking = false;
                } else {
                    hideAt = now + FADE_MS;
                }
            }
            return;
        }
        if (label == null || doneAt > 0 && progress < 1f) {
            appeared = now;
            shown = progress;
            chimed = (int) (progress * 10);
        } else if (hideAt > 0) {
            // Comes back while fading out: in again from as far as it had faded.
            appeared = now - Math.max(0, hideAt - now);
            shown = progress;
            chimed = (int) (progress * 10);
        }
        // Looked at again while running back: it turns where it is (render).
        draining = false;
        hideAt = 0;
        label = text;
        heard = now;
        perTick = rate;
        if (progress >= 1f) {
            if (doneAt == 0) {
                doneAt = now;
                // Done: the next step of the clicks, a little louder, then one a step lower as it settles, as if it
                // clicked into place (the same day the enchanting table's rustle, then a bell: off the clicks' tune).
                play(SoundEvents.UI_BUTTON_CLICK.value(), 1.9f, 0.35f);
                settleAt = now + 70;
            }
            server = 1f;
            return;
        }
        doneAt = 0;
        // The server's count is behind the screen by a check and the way here: the ring keeps its own unless the server
        // is much further (another observation shown). Much behind, the ring runs back to it (render).
        if (progress > shown + (LEAD_TICKS + 2) * rate) {
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
        if (hideAt > 0 && now > hideAt || hideAt == 0 && doneAt == 0 && !draining && now - heard > STALE_MS) {
            label = null;
            hideAt = 0;
            return;
        }
        if (minecraft.options.hideGui) {
            return;
        }
        if (settleAt > 0 && now >= settleAt) {
            settleAt = 0;
            play(SoundEvents.UI_BUTTON_CLICK.value(), 1.45f, 0.3f);
        }
        float partial = delta.getGameTimeDeltaPartialTick(true);
        boolean done = doneAt > 0;
        if (done) {
            shown = Math.min(1f, shown + frame / 120f);
        } else {
            if (!draining) {
                looking = looksAt(minecraft, partial);
            }
            // Not far past what the server counted; lost, nothing.
            float cap = draining ? 0 : Math.min(server + LEAD_TICKS * perTick, 0.995f);
            if (shown > cap) {
                // Runs back to it, quickly at first and easing to the end.
                shown = Math.max(cap, shown - Math.min(frame, 100) * Math.max(0.0012f, (shown - cap) / DRAIN_MS));
            } else if (looking && frame < 250) {
                // Moves on at the observation's own pace.
                shown = Math.min(cap, shown + frame / MS_PER_TICK * perTick);
            }
            if (draining && shown <= 0.005f) {
                shown = 0;
                draining = false;
                hideAt = now + FADE_MS;
            }
            // The scroll arrows' soft click at each tenth on the way up, a little higher each time (the same day an amethyst
            // chime, too quiet to hear).
            int tenth = (int) (shown * 10);
            if (tenth > chimed && tenth >= 1 && tenth <= 9) {
                play(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f + tenth * 0.09f, 0.25f);
            }
            chimed = tenth;
        }
        float fadeIn = Mth.clamp((now - appeared) / (float) FADE_MS, 0, 1);
        float fadeOut = hideAt > 0 ? Mth.clamp((hideAt - now) / (float) FADE_MS, 0, 1) : done ? Mth.clamp((DONE_MS - (now - doneAt)) / 250f, 0, 1) : 1;
        float alpha = fadeIn * fadeOut * (looking || done ? 1f : 0.55f);
        if (alpha <= 0.02f) {
            return;
        }
        var window = minecraft.getWindow();
        double guiScale = window.getGuiScale();
        // In HUD pixels (HudScale). Under the crosshair, which keeps the GUI scale (7 GUI pixels to its edge), or under
        // the talk hint when the NPC observed can be talked to (also the GUI scale's, until the dialogue's art is redone).
        int pixel = HudScale.pixel();
        double belowPx = TalkHint.visible() ? (graphics.guiHeight() / 2 + TalkHint.bottom() + 4) * guiScale
                : window.getHeight() / 2.0 + 8 * guiScale + BELOW_CROSSHAIR * pixel;
        int left = (window.getWidth() - BAR_WIDTH * pixel) / 2;
        int top = (int) Math.ceil(belowPx / pixel) * pixel;
        Backdrop.sample(left, top, BAR_WIDTH * pixel, BAR_HEIGHT * pixel);
        float luminance = Backdrop.luminance();
        if (luminance >= 0) {
            onDark = luminance < DARK_BELOW || onDark && luminance <= LIGHT_ABOVE;
        }
        float step = Math.min(frame, 100) / (float) SWITCH_MS;
        dark = onDark ? Math.min(1, dark + step) : Math.max(0, dark - step);
        graphics.pose().pushPose();
        graphics.pose().scale((float) (1 / guiScale), (float) (1 / guiScale), 1);
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale(pixel, pixel, 1);
        drawBar(graphics, shown, done, alpha, now);
        graphics.pose().popPose();
        HudScale.push(graphics);
        Font font = minecraft.font;
        Component line = done ? Component.translatable("storybound.observation.done")
                : Component.empty().append(label).append("  " + Math.round(shown * 100) + "%");
        int a = Math.round(alpha * 255) << 24;
        int textX = (HudScale.width() - font.width(line)) / 2;
        int textY = (int) Math.ceil((top + BAR_HEIGHT * pixel) / (double) pixel) + 3;
        // A light edge under dark words over a light picture, a dark one under light words over a dark picture.
        boolean light = dark >= 0.5f;
        int text = done ? GOLD : light ? UiPalette.PAPER : UiPalette.INK;
        int edge = done || light ? UiPalette.INK : UiPalette.PAPER_SHADE;
        graphics.drawString(font, line, textX + 1, textY + 1, (Math.round(alpha * 160) << 24) | edge, false);
        graphics.drawString(font, line, textX, textY, a | text, false);
        HudScale.pop(graphics);
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    /**
     * In the bar's own pixels from its top left: each variant's frame and fill to {@code progress}, as much as the bar
     * has gone to it, and at the end the gold running over the fill from the left.
     */
    private static void drawBar(GuiGraphics graphics, float progress, boolean done, float alpha, long now) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (dark < 1) {
            graphics.setColor(1f, 1f, 1f, alpha * (1 - dark));
            graphics.blit(BAR, 0, 0, 0, FRAME, BAR_WIDTH, BAR_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            fill(graphics, progress, FILL);
        }
        if (dark > 0) {
            graphics.setColor(1f, 1f, 1f, alpha * dark);
            graphics.blit(BAR, 0, 0, 0, FRAME_ON_DARK, BAR_WIDTH, BAR_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            fill(graphics, progress, FILL_ON_DARK);
        }
        if (done) {
            graphics.setColor(1f, 1f, 1f, alpha);
            fill(graphics, Mth.clamp((now - doneAt) / 300f, 0, 1), GOLD_FILL);
        }
        graphics.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /** The fill strip at {@code v} to {@code progress}: its left part with the tip at its end, or, full, all of it. */
    private static void fill(GuiGraphics graphics, float progress, int v) {
        if (progress >= 1f) {
            graphics.blit(BAR, 0, 0, 0, v, BAR_WIDTH, BAR_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            return;
        }
        int length = Math.min(Math.round(progress * TRAVEL), TRAVEL);
        if (length <= 0) {
            return;
        }
        // Shorter than the tip: the tip's last columns.
        int tip = Math.min(TIP, length);
        int body = length - tip;
        if (body > 0) {
            graphics.blit(BAR, FILL_LEFT, 0, FILL_LEFT, v, body, BAR_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        }
        graphics.blit(BAR, FILL_LEFT + body, 0, TIP_U + TIP - tip, v, tip, BAR_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
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
