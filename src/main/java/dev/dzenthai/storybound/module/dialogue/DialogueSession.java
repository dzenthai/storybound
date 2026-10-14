package dev.dzenthai.storybound.module.dialogue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** One player's running conversation. Lives only in memory: a restart or logout ends it. */
final class DialogueSession {
    final int id;
    final ServerPlayer player;
    /** The entity the dialogue was started with: the {@code bound} participant. */
    final @Nullable Entity bound;
    final long startedTick;
    /** Callers of {@code call} nodes, innermost first; the last one is the conversation's base. */
    final Deque<Frame> callers = new ArrayDeque<>();

    ResourceLocation dialogueId;
    DialogueDefinition dialogue;
    /** The last node passed; while waiting, the line or choice on screen. */
    String node = "";
    Waiting waiting = Waiting.NONE;
    /** Identifies the line or choice on screen; replies must name it. */
    int step;
    /** Indexes into the choice's options, in the order the client shows them. */
    List<Integer> shown = List.of();
    /** Game tick at which a timed line moves on or a choice times out; -1 for none. */
    long deadline = -1;
    /** A {@link ThinkTime} code for the next line, set when an option is chosen: the NPC pauses before answering. */
    int replyThink = ThinkTime.NONE;
    boolean closed;

    DialogueSession(int id, ServerPlayer player, @Nullable Entity bound, ResourceLocation dialogueId, DialogueDefinition dialogue, long startedTick) {
        this.id = id;
        this.player = player;
        this.bound = bound;
        this.dialogueId = dialogueId;
        this.dialogue = dialogue;
        this.startedTick = startedTick;
    }

    /** The dialogue at the bottom of the call stack: the one {@code dialogue_ended} and {@code on_abort} belong to. */
    ResourceLocation baseId() {
        return callers.isEmpty() ? dialogueId : callers.getLast().dialogueId();
    }

    DialogueDefinition base() {
        return callers.isEmpty() ? dialogue : callers.getLast().dialogue();
    }

    enum Waiting {
        NONE,
        LINE,
        CHOICE
    }

    /** Where a {@code call} returns to. */
    record Frame(ResourceLocation dialogueId, DialogueDefinition dialogue, String next) {}
}
