package dev.dzenthai.storybound.client.codex;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.StoryboundClient;
import dev.dzenthai.storybound.client.UiPalette;
import dev.dzenthai.storybound.client.dev.DevFrameCapture;
import dev.dzenthai.storybound.client.dialogue.ClientDialogue;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Presentation;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.InputEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Non-intrusive HUD prompt shown when a player discovers a new place in the Codex: lets them press the interaction key
 * ({@link StoryboundClient#TALK}, default H) to open the 16:9 viewfinder and snap their own Codex photo, or press
 * Backspace (or simply keep playing until the timer fades out) to skip it.
 */
public final class CodexPhotoPrompt {
    public static final ResourceLocation HUD_LAYER = Storybound.id("codex_photo_prompt");
    private static final long DURATION_MILLIS = 15_000;
    private static final long FADE_MILLIS = 220;

    private record Pending(ResourceLocation entryId, Component title) {}

    private static final Deque<Pending> QUEUE = new ArrayDeque<>();
    private static @Nullable Pending current;
    private static long shownSince;

    private CodexPhotoPrompt() {}

    /** Offers a photo capture opportunity for a newly unlocked place entry (ignored if already queued or photographed). */
    public static void offer(ResourceLocation entryId, Component title) {
        if (PlayerCodexPhotos.hasPhoto(entryId)) {
            return;
        }
        if (current != null && current.entryId().equals(entryId)) {
            return;
        }
        for (Pending item : QUEUE) {
            if (item.entryId().equals(entryId)) {
                return;
            }
        }
        QUEUE.addLast(new Pending(entryId, title));
        if (current == null) {
            advance();
        }
    }

    public static boolean isActive() {
        return current != null && !DevFrameCapture.isActive();
    }

    /** Player pressed the interaction key: launch the camera viewfinder for the discovered place. */
    public static boolean accept() {
        Pending pending = current;
        if (pending == null || DevFrameCapture.isActive()) {
            return false;
        }
        current = null;
        DevFrameCapture.captureForPlayer(pending.title(), null, image -> {
            Minecraft mc = Minecraft.getInstance();
            if (PlayerCodexPhotos.save(pending.entryId(), image) && mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("storybound.codex.photo.saved", pending.title()), true);
            }
        });
        return true;
    }

    /** Player pressed the skip key: dismiss the current prompt and move on. */
    public static void skip() {
        if (current == null) {
            return;
        }
        current = null;
        advance();
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            clear();
            return;
        }
        if (current == null && !QUEUE.isEmpty() && !DevFrameCapture.isActive()) {
            advance();
        }
        if (current != null && Util.getMillis() - shownSince >= DURATION_MILLIS) {
            skip();
        }
    }

    public static void onKey(InputEvent.Key event) {
        if (!isActive() || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui) {
            return;
        }
        if (event.getKey() == GLFW.GLFW_KEY_BACKSPACE) {
            skip();
        }
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Pending pending = current;
        Minecraft mc = Minecraft.getInstance();
        if (pending == null || DevFrameCapture.isActive() || mc.options.hideGui || mc.screen != null
                || (ClientDialogue.isActive() && ClientDialogue.presentation() == Presentation.SCREEN)) {
            return;
        }
        long elapsed = Util.getMillis() - shownSince;
        long remaining = DURATION_MILLIS - elapsed;
        if (remaining <= 0) {
            return;
        }
        float alpha = Math.min(
                Mth.clamp(elapsed / (float) FADE_MILLIS, 0f, 1f),
                Mth.clamp(remaining / (float) FADE_MILLIS, 0f, 1f));
        if (alpha < 0.05f) {
            return;
        }
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        Font font = mc.font;
        Component title = Component.translatable("storybound.codex.photo.prompt_title", pending.title());
        Component takeKey = StoryboundClient.TALK.isUnbound()
                ? Component.literal("H") : StoryboundClient.TALK.getTranslatedKeyMessage();
        Component takeLabel = Component.translatable("storybound.codex.photo.prompt_take");
        Component skipKey = Component.translatable("storybound.codex.photo.skip_key");
        Component skipLabel = Component.translatable("storybound.codex.photo.prompt_skip");

        int takeKeyW = font.width(takeKey) + 6;
        int skipKeyW = font.width(skipKey) + 6;
        int controlsW = takeKeyW + 4 + font.width(takeLabel) + 12 + skipKeyW + 4 + font.width(skipLabel);
        int boxW = Math.max(font.width(title), controlsW) + 18;
        int boxH = 34;
        int x = (graphics.guiWidth() - boxW) / 2;
        int y = graphics.guiHeight() - 78;

        int bg = faded(theme.hudBackground(), alpha);
        int border = faded(theme.hudTitle(), alpha);
        int textCol = faded(theme.hudText(), alpha);
        int mutedCol = faded(UiPalette.opaque(UiPalette.TAN_LIGHT), alpha);

        graphics.fill(x, y, x + boxW, y + boxH, bg);
        graphics.renderOutline(x, y, boxW, boxH, border);

        // Title centered on top row.
        graphics.drawString(font, title, x + (boxW - font.width(title)) / 2, y + 5, border, true);

        // Action key hints centered on bottom row.
        int rowY = y + 18;
        int cx = x + (boxW - controlsW) / 2;
        graphics.fill(cx, rowY - 1, cx + takeKeyW, rowY + 10, faded(0xCC1A140F, alpha));
        graphics.renderOutline(cx, rowY - 1, takeKeyW, 11, border);
        graphics.drawString(font, takeKey, cx + 3, rowY + 1, border, false);
        cx += takeKeyW + 4;
        graphics.drawString(font, takeLabel, cx, rowY + 1, textCol, true);
        cx += font.width(takeLabel) + 12;

        graphics.fill(cx, rowY - 1, cx + skipKeyW, rowY + 10, faded(0xAA1A140F, alpha));
        graphics.renderOutline(cx, rowY - 1, skipKeyW, 11, mutedCol);
        graphics.drawString(font, skipKey, cx + 3, rowY + 1, mutedCol, false);
        cx += skipKeyW + 4;
        graphics.drawString(font, skipLabel, cx, rowY + 1, mutedCol, true);

        // Thin countdown bar along the bottom edge.
        int barW = Math.max(0, Math.round((boxW - 2) * (remaining / (float) DURATION_MILLIS)));
        if (barW > 0) {
            graphics.fill(x + 1, y + boxH - 2, x + 1 + barW, y + boxH - 1, border);
        }
    }

    public static void clear() {
        QUEUE.clear();
        current = null;
        shownSince = 0;
    }

    private static void advance() {
        while (!QUEUE.isEmpty()) {
            Pending next = QUEUE.pollFirst();
            if (!PlayerCodexPhotos.hasPhoto(next.entryId())) {
                current = next;
                shownSince = Util.getMillis();
                return;
            }
        }
        current = null;
    }

    private static int faded(int argb, float alpha) {
        int a = Math.max(4, (int) (((argb >>> 24) & 0xFF) * alpha));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}

