package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.event.DialogueEvent;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** {@code dialogueStarted} and {@code dialogueEnded}; node and reason are null for a start. */
public class DialogueKubeEvent implements KubeEvent {
    private final DialogueEvent event;

    DialogueKubeEvent(DialogueEvent event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public ResourceLocation getDialogue() {
        return event.getDialogue();
    }

    /** The entity the dialogue was started with, if any. */
    public @Nullable Entity getSpeaker() {
        return event.getSpeaker();
    }

    /** The last node the conversation passed. */
    public @Nullable String getNode() {
        return event instanceof DialogueEvent.Ended ended ? ended.getNode() : null;
    }

    /** {@code finished}, {@code left}, {@code too_far}, {@code speaker_gone}, {@code damaged}, {@code died}, {@code logged_out}, {@code stopped}, {@code error}. */
    public @Nullable String getReason() {
        return event instanceof DialogueEvent.Ended ended ? ended.getReason().name().toLowerCase(Locale.ROOT) : null;
    }

    public boolean isAborted() {
        return event instanceof DialogueEvent.Ended ended && ended.isAborted();
    }
}
