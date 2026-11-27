package dev.dzenthai.storybound.module.dialogue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    /** Additional live NPCs resolved for {@code entity} participants: participant id -> entity. */
    final Map<String, Entity> participants = new LinkedHashMap<>();
    /** For each held NPC in this session, who they currently look at (the speaker or their addressee). */
    final Map<UUID, Entity> gazeTargets = new HashMap<>();
    /** Most recent live NPC speaker in this conversation, used as default addressee for player lines and choices. */
    @Nullable Entity lastNpcSpeaker;
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
     * Whether the choice's window is open. Always on the dialogue screen; in subtitles and bubbles the player opens it
     * with the talk key ("[H] Answer"), so a choice no longer throws them into a window mid-fight.
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
    /** The voice-over of the line on screen while it may still be playing; stopped when the player moves on. */
    @Nullable ResourceLocation voiceOver;
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
        this.lastNpcSpeaker = bound != null && bound != player ? bound : null;
        this.dialogueId = dialogueId;
        this.dialogue = dialogue;
        this.startedTick = startedTick;
    }

    /** True when {@code entity} is the bound partner or any resolved additional participant of this session. */
    boolean involves(Entity entity) {
        if (bound == entity) {
            return true;
        }
        for (Entity extra : participants.values()) {
            if (extra == entity) {
                return true;
            }
        }
        return false;
    }

    /** All unique alive non-player NPC entities currently participating in this conversation. */
    List<Entity> liveNpcs() {
        List<Entity> npcs = new ArrayList<>();
        if (bound != null && bound != player && bound.isAlive() && bound.level() == player.level()) {
            npcs.add(bound);
        }
        for (Entity extra : participants.values()) {
            if (extra != null && extra != player && extra.isAlive() && extra.level() == player.level() && !npcs.contains(extra)) {
                npcs.add(extra);
            }
        }
        return npcs;
    }

    /** Entity ids of the player and all live NPCs in this conversation, for {@code group_shot} camera framing. */
    List<Integer> liveCastIds() {
        List<Integer> ids = new ArrayList<>();
        ids.add(player.getId());
        for (Entity npc : liveNpcs()) {
            ids.add(npc.getId());
        }
        return ids;
    }

    /** Entities already claimed by {@code bound} or another participant key, so two roles never pick the same mob. */
    Set<Entity> claimedEntities(@Nullable String exceptKey) {
        Set<Entity> claimed = new HashSet<>();
        if (bound != null) {
            claimed.add(bound);
        }
        participants.forEach((key, entity) -> {
            if (entity != null && !key.equals(exceptKey)) {
                claimed.add(entity);
            }
        });
        return claimed;
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
        CHOICE,
        /** A scene node plays: the talk waits for it, its screen closed meanwhile. */
        SCENE
    }

    /** Whether a resume is playing: one paused again goes back to its line without another "Where was I". */
    boolean resuming() {
        return callers.stream().anyMatch(Frame::resume);
    }

    /** Where a {@code call} returns to; {@code resume}: to the line a fight paused, after its resume. */
    record Frame(ResourceLocation dialogueId, DialogueDefinition dialogue, String next, boolean resume) {}
}
