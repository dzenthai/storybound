package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.client.StoryboundClient;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Presentation;
import dev.dzenthai.storybound.network.TalkQueryPayload;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * "[H] Talk" under the crosshair while it is on an NPC the talk key would start something with. Only the server
 * knows the bindings and their conditions, so the client asks about the entity under the crosshair when it changes,
 * and again every couple of seconds while it stays (a quest stage may have opened a dialogue meanwhile).
 */
public final class TalkHint {
    private static final int REQUERY_TICKS = 40;
    private static final int BELOW_CROSSHAIR = 14;
    private static final long FADE_IN_MILLIS = 150;

    /** The entity last asked about, and when (game time); -1 for none. */
    private static int asked = -1;
    private static long askedAt;
    /** The entity the server said yes for; -1 while there is none or no answer yet. */
    private static int talkable = -1;
    private static long shownSince;

    private TalkHint() {}

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        Entity target = target(minecraft);
        if (target == null) {
            clear();
            return;
        }
        long now = minecraft.level.getGameTime();
        if (target.getId() != asked || now - askedAt >= REQUERY_TICKS) {
            if (target.getId() != asked) {
                talkable = -1;
            }
            asked = target.getId();
            askedAt = now;
            PacketDistributor.sendToServer(new TalkQueryPayload(asked));
        }
    }

    public static void answer(int entityId, boolean yes) {
        if (entityId != asked) {
            return;
        }
        if (yes && talkable != entityId) {
            shownSince = Util.getMillis();
        }
        talkable = yes ? entityId : -1;
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        // Shown during a dialogue in subtitles (a monologue): talking to an NPC takes over from it.
        if (talkable < 0 || minecraft.options.hideGui || minecraft.screen != null
                || ClientDialogue.isActive() && ClientDialogue.presentation() == Presentation.SCREEN || StoryboundClient.TALK.isUnbound()) {
            return;
        }
        Entity target = target(minecraft);
        if (target == null || target.getId() != talkable) {
            return;
        }
        float alpha = Mth.clamp((Util.getMillis() - shownSince) / (float) FADE_IN_MILLIS, 0f, 1f);
        if (alpha < 0.05f) {
            return;
        }
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        Font font = minecraft.font;
        Component key = StoryboundClient.TALK.getTranslatedKeyMessage();
        Component label = Component.translatable("storybound.talk.hint");
        int boxWidth = font.width(key) + 7;
        int boxHeight = 12;
        int x = (graphics.guiWidth() - (boxWidth + 4 + font.width(label))) / 2;
        int y = graphics.guiHeight() / 2 + BELOW_CROSSHAIR;
        graphics.fill(x, y, x + boxWidth, y + boxHeight, faded(theme.hudBackground(), alpha));
        graphics.renderOutline(x, y, boxWidth, boxHeight, faded(theme.hudTitle(), alpha));
        graphics.drawString(font, key, x + 4, y + 2, faded(theme.hudTitle(), alpha), false);
        graphics.drawString(font, label, x + boxWidth + 4, y + 2, faded(theme.hudText(), alpha), true);
    }

    /** Forgets the answer, so the next tick asks again: on leaving a world and when a dialogue ends. */
    public static void clear() {
        asked = -1;
        talkable = -1;
    }

    /** What the talk key would address: the entity a right-click would reach. */
    private static @Nullable Entity target(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.player.isSpectator() || !ClientModules.isEnabled("dialogues")) {
            return null;
        }
        return minecraft.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
    }

    private static int faded(int argb, float alpha) {
        // The font treats an alpha below 4 as opaque; the caller skips such faint frames.
        int a = Math.max(4, (int) (((argb >>> 24) & 0xFF) * alpha));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
