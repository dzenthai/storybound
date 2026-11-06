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
    /**
     * Whether the choice's window is open (November 6, 2026). Always on the dialogue screen; in subtitles and bubbles the
     * player opens it with the talk key ("[H] Answer"), so a choice no longer throws them into a window mid-fight.
     */
    boolean choiceOpen;
    /** While the window is open, the ticks its timer had left (it counts only the time to open it); -1 for none. */
    long choiceLeft = -1;
    /** The last line shown and the dialogue it is in, to say it again before a choice a fight took away. */
    String lastLine = "";
    @Nullable ResourceLocation lastLineIn;
    /**
     * Said again after a fight, this line leads back to that choice ({@code thenChoice}) rather than to its own next:
     * what lies between them (an action spawning husks) does not run twice.
     */
    @Nullable String replayLine;
    @Nullable String thenChoice;
    /** A {@link ThinkTime} code for the next line, set when an option is chosen: the NPC pauses before answering. */
    int replyThink = ThinkTime.NONE;
    /** The camera cue sent with what is on screen. */
    CameraCue camera = CameraCue.OFF;
    boolean closed;
    /** Game tick a fight paused it at (its {@code combat} says {@code pause}); -1 while it runs. */
    long pausedAt = -1;
    /** Started in a fight: nothing was shown yet, so once it is over it starts from {@code start}, without a resume. */
    boolean notStarted;

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

    /** Whether a resume is playing: one paused again goes back to its line without another "Where was I". */
    boolean resuming() {
        return callers.stream().anyMatch(Frame::resume);
    }

    /** Where a {@code call} returns to; {@code resume}: to the line a fight paused, after its resume. */
    record Frame(ResourceLocation dialogueId, DialogueDefinition dialogue, String next, boolean resume) {}
}
