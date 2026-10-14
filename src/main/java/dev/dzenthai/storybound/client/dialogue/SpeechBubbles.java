package dev.dzenthai.storybound.client.dialogue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Lines of other players' dialogues above the speaker's head: a billboard with a background that fades
 * out. Several bubbles over one entity (parallel conversations) stack, newest at the bottom.
 */
public final class SpeechBubbles {
    private static final int MAX_PER_ENTITY = 3;
    private static final int MAX_WIDTH = 160;
    private static final long FADE_MILLIS = 500;
    private static final double MAX_RENDER_DISTANCE = 24;

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
            showAt = Math.max(now, from + ThinkTime.resolve(think, length));
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

    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        // After clouds, particles and weather: drawn earlier, the clouds behind a bubble covered parts of its text.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || BUBBLES.isEmpty() || minecraft.level == null) {
            return;
        }
        long now = Util.getMillis();
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Font font = minecraft.font;
        Iterator<Map.Entry<Integer, List<Bubble>>> entries = BUBBLES.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, List<Bubble>> entry = entries.next();
            entry.getValue().removeIf(bubble -> bubble.expiresAt() <= now);
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (entry.getValue().isEmpty() || entity == null || entity.isRemoved()) {
                entries.remove();
                continue;
            }
            Vec3 position = entity.getPosition(partialTick).add(0, entity.getBbHeight() + 0.75, 0);
            if (minecraft.options.hideGui || position.distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(position.x - cameraPos.x, position.y - cameraPos.y, position.z - cameraPos.z);
            poseStack.mulPose(camera.rotation());
            poseStack.scale(0.025f, -0.025f, 0.025f);
            Matrix4f matrix = poseStack.last().pose();
            float bottom = 0;
            List<Bubble> bubbles = entry.getValue();
            for (int i = bubbles.size() - 1; i >= 0; i--) {
                Bubble bubble = bubbles.get(i);
                float alpha = Mth.clamp((bubble.expiresAt() - now) / (float) FADE_MILLIS, 0.05f, 1f);
                Component shown = now < bubble.showAt() ? Component.literal(ThinkTime.dots(now - bubble.createdAt())) : bubble.text();
                List<FormattedCharSequence> lines = font.split(shown, MAX_WIDTH);
                bottom -= lines.size() * 10 + 4;
                float y = bottom;
                for (FormattedCharSequence line : lines) {
                    float x = -font.width(line) / 2f;
                    int textColor = ((int) (alpha * 255) << 24) | 0xFFFFFF;
                    int background = ((int) (alpha * 0x90) << 24);
                    font.drawInBatch(line, x, y, textColor, false, matrix, buffers, Font.DisplayMode.NORMAL, background, LightTexture.FULL_BRIGHT);
                    // The background lies in the text's plane and z-fights with it; this pass pulls the letters towards the camera.
                    font.drawInBatch(line, x, y, textColor, false, matrix, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
                    y += 10;
                }
            }
            poseStack.popPose();
        }
        buffers.endBatch();
    }

    /** @param showAt until then the NPC is "thinking": dots instead of the text */
    private record Bubble(Component text, long createdAt, long showAt, long expiresAt) {}
}
