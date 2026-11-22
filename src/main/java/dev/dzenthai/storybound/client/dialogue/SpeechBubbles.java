package dev.dzenthai.storybound.client.dialogue;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dzenthai.storybound.client.HudScale;
import dev.dzenthai.storybound.client.ScreenProjection;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Lines of other players' dialogues and NPCs' phrases above the speaker's head, with a background that fades out.
 * Several bubbles over one entity (parallel conversations) stack, newest at the bottom.
 */
public final class SpeechBubbles {
    private static final int MAX_PER_ENTITY = 3;
    private static final int MAX_WIDTH = 160;
    private static final long FADE_MILLIS = 500;
    private static final double MAX_RENDER_DISTANCE = 24;
    /** Blocks per text pixel, as the bubble would hang in the world. */
    private static final float WORLD_SCALE = 0.025f;

    private static final Map<Integer, List<Bubble>> BUBBLES = new HashMap<>();

    private SpeechBubbles() {}

    /**
     * @param replyTo the player whose option this line answers, or -1: the line waits behind animated dots,
     *                timed from that player's bubble as this client shows it
     */
    public static void add(int entityId, ResolvedText text, int replyTo, int think) {
        if (!StoryboundClientConfig.INSTANCE.speechBubbles.get()) {
            return;
        }
        Component component = text.toComponent();
        long now = Util.getMillis();
        long showAt = now;
        if (think != ThinkTime.NONE) {
            List<Bubble> said = BUBBLES.getOrDefault(replyTo, List.of());
            Bubble option = said.isEmpty() ? null : said.getLast();
            long from = option != null ? option.createdAt() : now;
            int length = option != null ? option.text().getString().length() : 0;
            showAt = Math.max(now, from + ThinkTime.resolve(think, length, StoryboundClientConfig.INSTANCE.thinkPause.get()));
        }
        long duration = Mth.clamp(2500L + 60L * component.getString().length(), 3000L, 10000L);
        List<Bubble> bubbles = BUBBLES.computeIfAbsent(entityId, id -> new ArrayList<>());
        bubbles.add(new Bubble(component, now, showAt, showAt + duration));
        while (bubbles.size() > MAX_PER_ENTITY) {
            bubbles.removeFirst();
        }
    }

    public static void clear() {
        BUBBLES.clear();
    }

    /**
     * Drawn on the HUD at the speaker's place, sized as if it hung in the world: shader packs, clouds and fog leave it
     * alone, and blocks over the speaker's head do not cut it. A speaker the player cannot see (behind a wall or a
     * hill) shows no bubble.
     */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (BUBBLES.isEmpty() || minecraft.level == null || minecraft.player == null) {
            return;
        }
        long now = Util.getMillis();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        Vec3 camera = ScreenProjection.camera();
        Vec3 up = new Vec3(minecraft.gameRenderer.getMainCamera().getUpVector());
        int width = HudScale.width();
        int height = HudScale.height();
        List<Shown> shown = new ArrayList<>();
        Iterator<Map.Entry<Integer, List<Bubble>>> entries = BUBBLES.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, List<Bubble>> entry = entries.next();
            entry.getValue().removeIf(bubble -> bubble.expiresAt() <= now);
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (entry.getValue().isEmpty() || entity == null || entity.isRemoved()) {
                entries.remove();
                continue;
            }
            Vec3 anchor = entity.getPosition(partialTick).add(0, entity.getBbHeight() + 0.75, 0);
            double distance = anchor.distanceTo(camera);
            if (distance > MAX_RENDER_DISTANCE || !seen(minecraft, camera, entity, partialTick)) {
                continue;
            }
            float[] at = ScreenProjection.project(anchor, width, height);
            float[] above = ScreenProjection.project(anchor.add(up), width, height);
            if (at == null || above == null) {
                continue;
            }
            // HUD pixels per block where the speaker is, so the text keeps its size in the world.
            float scale = WORLD_SCALE * (float) Math.hypot(above[0] - at[0], above[1] - at[1]);
            shown.add(new Shown(entry.getValue(), at[0], at[1], scale, distance));
        }
        if (shown.isEmpty()) {
            return;
        }
        // Far ones first, so a near speaker's bubble lies over them.
        shown.sort(Comparator.comparingDouble(Shown::distance).reversed());
        Font font = minecraft.font;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        HudScale.push(graphics);
        for (Shown speaker : shown) {
            graphics.pose().pushPose();
            graphics.pose().translate(speaker.x(), speaker.y(), 0);
            graphics.pose().scale(speaker.scale(), speaker.scale(), 1f);
            float bottom = 0;
            List<Bubble> bubbles = speaker.bubbles();
            for (int i = bubbles.size() - 1; i >= 0; i--) {
                Bubble bubble = bubbles.get(i);
                float alpha = Mth.clamp((bubble.expiresAt() - now) / (float) FADE_MILLIS, 0.05f, 1f);
                Component text = now < bubble.showAt() ? Component.literal(ThinkTime.dots(now - bubble.createdAt())) : bubble.text();
                List<FormattedCharSequence> lines = font.split(text, MAX_WIDTH);
                bottom -= lines.size() * 10 + 4;
                int y = Math.round(bottom);
                for (FormattedCharSequence line : lines) {
                    int x = -font.width(line) / 2;
                    graphics.fill(x - 1, y - 1, x + font.width(line), y + 9, (int) (alpha * 0x90) << 24);
                    graphics.drawString(font, line, x, y, ((int) (alpha * 255) << 24) | 0xFFFFFF, false);
                    y += 10;
                }
            }
            graphics.pose().popPose();
        }
        HudScale.pop(graphics);
        RenderSystem.disableBlend();
    }

    /** Whether the player sees the speaker: nothing solid between the camera and its eyes, or its middle. */
    private static boolean seen(Minecraft minecraft, Vec3 camera, Entity entity, float partialTick) {
        Vec3 feet = entity.getPosition(partialTick);
        for (double height : new double[]{entity.getEyeHeight(), entity.getBbHeight() / 2}) {
            Vec3 point = feet.add(0, height, 0);
            HitResult hit = minecraft.level.clip(new ClipContext(camera, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    /** A speaker's bubbles where they go on the HUD this frame. */
    private record Shown(List<Bubble> bubbles, float x, float y, float scale, double distance) {}

    /** @param showAt until then the NPC is "thinking": dots instead of the text */
    private record Bubble(Component text, long createdAt, long showAt, long expiresAt) {}
}
