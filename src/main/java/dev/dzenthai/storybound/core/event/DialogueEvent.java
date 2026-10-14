package dev.dzenthai.storybound.core.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/** A player's dialogue started or ended. Posted on {@code NeoForge.EVENT_BUS}; backs the KubeJS dialogue events. */
public abstract sealed class DialogueEvent extends Event {
    private final ServerPlayer player;
    private final ResourceLocation dialogue;
    private final @Nullable Entity speaker;

    protected DialogueEvent(ServerPlayer player, ResourceLocation dialogue, @Nullable Entity speaker) {
        this.player = player;
        this.dialogue = dialogue;
        this.speaker = speaker;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    /** For {@link Ended}: the dialogue the conversation was in at its base (after jumps, not inside calls). */
    public ResourceLocation getDialogue() {
        return dialogue;
    }

    /** The entity the dialogue was started with, if any. */
    public @Nullable Entity getSpeaker() {
        return speaker;
    }

    public static final class Started extends DialogueEvent {
        public Started(ServerPlayer player, ResourceLocation dialogue, @Nullable Entity speaker) {
            super(player, dialogue, speaker);
        }
    }

    public static final class Ended extends DialogueEvent {
        private final String node;
        private final EndReason reason;

        public Ended(ServerPlayer player, ResourceLocation dialogue, @Nullable Entity speaker, String node, EndReason reason) {
            super(player, dialogue, speaker);
            this.node = node;
            this.reason = reason;
        }

        /** The last node the conversation passed. */
        public String getNode() {
            return node;
        }

        public EndReason getReason() {
            return reason;
        }

        public boolean isAborted() {
            return reason != EndReason.FINISHED;
        }
    }

    public enum EndReason {
        /** Reached {@code @end}, {@code @exit}, an {@code end} node or {@code end_dialogue}. */
        FINISHED,
        /** ESC on a closable dialogue. */
        LEFT,
        TOO_FAR,
        SPEAKER_GONE,
        DAMAGED,
        DIED,
        LOGGED_OUT,
        /** A command, a script or the module being disabled. */
        STOPPED,
        /** Broken data or an aborted action node; see the log. */
        ERROR
    }
}
