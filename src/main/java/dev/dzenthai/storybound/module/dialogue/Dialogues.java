package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.core.event.DialogueEvent;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.Conditions;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Choice;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Line;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Node;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Option;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Participant;
import dev.dzenthai.storybound.module.dialogue.DialogueSession.Waiting;
import dev.dzenthai.storybound.network.DialogueChoicesPayload;
import dev.dzenthai.storybound.network.DialogueClosePayload;
import dev.dzenthai.storybound.network.DialogueLinePayload;
import dev.dzenthai.storybound.network.SpeechBubblePayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * Runs dialogue sessions on the server. The server walks invisible nodes itself, sends each line or
 * choice to the client and accepts only replies that name the current session and step; an option is
 * re-checked when chosen. Sessions of different players are independent, even with the same NPC.
 */
public final class Dialogues {
    /** Players this close to a speaker see its lines as speech bubbles. */
    public static final double BUBBLE_RANGE = 16;
    /** Invisible nodes in a row before the server gives up; stops condition and jump loops. */
    private static final int MAX_STEPS = 64;
    /** How far a broadcast voice-over is heard, so stopped: a sound at full volume carries 16 blocks. */
    private static final double VOICE_OVER_RANGE = 32;
    static final String ONCE_PREFIX = "dialogue_once/";

    private static final Map<UUID, DialogueSession> SESSIONS = new HashMap<>();
    /**
     * Per player: the dialogue a fight paused, at most one. Out of {@link #SESSIONS}, so the player is free meanwhile:
     * callouts (dialogues a fight does not pause) play over it, and it goes on once the fight is over and the player is
     * free again.
     */
    private static final Map<UUID, DialogueSession> PAUSED = new HashMap<>();
    /**
     * Per player: dialogues an action asked for while they were talking, started in order once they are free (one a
     * tick). A request waits at most {@link #QUEUE_WAIT_TICKS}; a player who logs out or dies loses theirs.
     */
    private static final Map<UUID, Deque<Queued>> QUEUED = new HashMap<>();
    static final int QUEUE_WAIT_TICKS = 60 * 20;
    private static final int QUEUE_MAX = 4;
    /** NPC → the player it spoke its last line to; it turns to face them. */
    private static final Map<UUID, UUID> ADDRESSED = new HashMap<>();
    private static int nextSession = 1;
    private static int nextStep = 1;

    private Dialogues() {}

    /** {@code modules.dialogues}; while disabled, no dialogue starts and running ones are stopped. */
    public static boolean isEnabled() {
        return StoryboundConfig.COMMON.dialogues.get();
    }

    public static @Nullable DialogueDefinition definition(ResourceLocation dialogue) {
        return DialogueRegistries.DIALOGUES.serverStore().getOrNull(dialogue);
    }

    /** What the player's conversation is waiting for; empty when they are not in one. */
    /** Another player talking with the entity now (bound or extra participant); null when nobody is. */
    public static @Nullable ServerPlayer talkingWith(Entity entity, ServerPlayer except) {
        for (DialogueSession session : SESSIONS.values()) {
            if (!session.closed && session.involves(entity) && session.player != except) {
                return session.player;
            }
        }
        return null;
    }

    public static Optional<State> state(ServerPlayer player) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        return session == null ? Optional.empty()
                : Optional.of(new State(session.id, session.step, session.dialogueId, session.node, session.waiting == Waiting.CHOICE, session.shown.size(), session.camera));
    }

    /** The dialogue a fight paused for the player and the node it goes back to; empty when none is paused. For tests and tools. */
    public static Optional<Paused> paused(ServerPlayer player) {
        DialogueSession session = PAUSED.get(player.getUUID());
        return session == null ? Optional.empty() : Optional.of(new Paused(session.baseId(), session.notStarted ? "" : session.node, session.notStarted));
    }

    /** @param node the line or choice shown again after the fight; empty when it had not started */
    public record Paused(ResourceLocation dialogue, String node, boolean notStarted) {}

    /** The ids of the options the player is shown, in their order; empty while no choice is shown. For tests and tools. */
    public static List<String> shownOptionIds(ServerPlayer player) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        if (session == null || session.waiting != Waiting.CHOICE || !(session.dialogue.nodes().get(session.node) instanceof Choice choice)) {
            return List.of();
        }
        return session.shown.stream().map(choice::optionId).toList();
    }

    /**
     * Starts a dialogue. {@code speaker} becomes the {@code bound} participant and the context entity of its logic.
     *
     * A dialogue a fight pauses, started in a fight, waits for it to be over and then starts (nothing is shown
     * meanwhile). One that a fight pauses cannot start while another such one is paused: {@link #startOrQueue} queues it.
     *
     * @return false if the dialogue is unknown, the module is disabled, the player is already talking, or it waits paused already
     */
    public static boolean start(ServerPlayer player, ResourceLocation dialogueId, @Nullable Entity speaker) {
        DialogueDefinition dialogue = definition(dialogueId);
        DialogueSession paused = PAUSED.get(player.getUUID());
        String refused = dialogue == null ? "unknown dialogue" : !isEnabled() ? "the dialogues module is disabled"
                : SESSIONS.containsKey(player.getUUID()) ? "the player is already talking" : !player.isAlive() ? "the player is dead"
                : dev.dzenthai.storybound.module.scene.Scenes.keepsFromTalking(player) ? "the player watches a scene"
                : paused != null && paused.baseId().equals(dialogueId) ? "it waits paused by a fight already"
                : paused != null && dialogue.pausesForFights() ? "another dialogue a fight paused waits (" + paused.baseId() + ")" : null;
        if (refused != null) {
            StoryTrace.note(player, "dialogue " + dialogueId, "not started: " + refused);
            return false;
        }
        StoryTrace.event(player, "dialogue_started", "dialogue", dialogueId.toString(),
                "speaker", speaker != null ? BuiltInRegistries.ENTITY_TYPE.getKey(speaker.getType()) + " " + speaker.getUUID() : "none");
        long now = player.server.getTickCount();
        DialogueSession session = new DialogueSession(nextSession++, player, speaker, dialogueId, dialogue, now);
        resolveAllParticipants(session);
        NeoForge.EVENT_BUS.post(new DialogueEvent.Started(player, dialogueId, speaker));
        if (dialogue.pausesForFights() && inFight(session, now)) {
            session.notStarted = true;
            session.pausedAt = now;
            PAUSED.put(player.getUUID(), session);
            StoryTrace.note(player, "dialogue " + dialogueId, "waits: the player is in a fight; it starts once the fight is over");
            return true;
        }
        SESSIONS.put(player.getUUID(), session);
        proceed(session, dialogue.start());
        return true;
    }

    /**
     * {@link #start}, or, while the player is talking, the same once they are free: for actions (a story event, a
     * quest stage), whose dialogue would otherwise be lost because another one was on. The same dialogue is not queued
     * twice. A speaker gone by then is left out. A dialogue a fight pauses also waits while another one a fight paused
     * does; the one paused is not started again.
     *
     * @return false if it could never start (unknown dialogue, the module disabled) or too many already wait
     */
    public static boolean startOrQueue(ServerPlayer player, ResourceLocation dialogueId, @Nullable Entity speaker) {
        DialogueDefinition dialogue = definition(dialogueId);
        DialogueSession paused = PAUSED.get(player.getUUID());
        if (paused != null && paused.baseId().equals(dialogueId)) {
            return true;
        }
        boolean waitsForPaused = paused != null && dialogue != null && dialogue.pausesForFights();
        if (!SESSIONS.containsKey(player.getUUID()) && !waitsForPaused || dialogue == null || !isEnabled()) {
            return start(player, dialogueId, speaker);
        }
        Deque<Queued> queue = QUEUED.computeIfAbsent(player.getUUID(), uuid -> new ArrayDeque<>());
        if (queue.stream().anyMatch(waiting -> waiting.dialogue().equals(dialogueId))) {
            return true;
        }
        if (queue.size() >= QUEUE_MAX) {
            StoryTrace.note(player, "dialogue " + dialogueId, "not started: " + QUEUE_MAX + " dialogues already wait for this one to end");
            return false;
        }
        queue.add(new Queued(dialogueId, speaker, player.server.getTickCount() + QUEUE_WAIT_TICKS));
        StoryTrace.note(player, "dialogue " + dialogueId, "waits: the player is talking; it starts when that ends");
        return true;
    }

    /** The dialogues waiting for the player to be free, in order; for tests and tools. */
    public static List<ResourceLocation> queued(ServerPlayer player) {
        return QUEUED.getOrDefault(player.getUUID(), new ArrayDeque<>()).stream().map(Queued::dialogue).toList();
    }

    /** Forgets what waited for the player (they logged out or died). */
    public static void dropQueue(ServerPlayer player) {
        Deque<Queued> queue = QUEUED.remove(player.getUUID());
        if (queue != null && !queue.isEmpty()) {
            StoryTrace.note(player, "dialogues", "dropped, as they can no longer wait: " + queue.stream().map(Queued::dialogue).toList());
        }
    }

    /**
     * Whether the player's dialogue takes over their screen: one on the dialogue screen, or a subtitle dialogue (HUD,
     * bubbles) while it shows a choice. Only those stop the player: damage interrupts them, and the player cannot turn
     * to an NPC meanwhile. Subtitles let the player go on walking, fighting and talking.
     */
    public static boolean holdsTheScreen(ServerPlayer player) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        return session != null && (session.dialogue.presentation() == DialogueDefinition.Presentation.SCREEN
                || session.waiting == Waiting.CHOICE && session.choiceOpen);
    }

    /**
     * The player opens ({@code open}) or closes the window of a choice shown in subtitles or bubbles: the prompt "[H]
     * Answer" waits for the talk key, and ESC in the window goes back to it. Its timer counts only the time to open it:
     * it stops while the window is open and goes on from what was left once it is closed, so opening and closing gives
     * no more time. Only an open window holds the screen (a hit then ends the talk, as on the screen).
     */
    public static boolean openChoice(ServerPlayer player, int sessionId, int step, boolean open) {
        DialogueSession session = current(player, sessionId, step, Waiting.CHOICE);
        if (session == null || session.dialogue.presentation() == DialogueDefinition.Presentation.SCREEN || session.choiceOpen == open) {
            return false;
        }
        long now = player.server.getTickCount();
        session.choiceOpen = open;
        if (open && session.deadline >= 0) {
            session.choiceLeft = Math.max(1, session.deadline - now);
            session.deadline = -1;
        } else if (!open && session.choiceLeft >= 0) {
            session.deadline = now + session.choiceLeft;
            session.choiceLeft = -1;
        }
        StoryTrace.note(player, "dialogue " + session.dialogueId, open ? "the answers' window opened" : "the answers' window closed: back to the prompt");
        return true;
    }

    /** The player clicked past the line on screen. */
    public static boolean advance(ServerPlayer player, int sessionId, int step) {
        DialogueSession session = current(player, sessionId, step, Waiting.LINE);
        if (session == null) {
            return false;
        }
        // Clicked past: its voice-over stops with it. A timed line moving on by itself lets it finish.
        hush(session);
        moveOn(session);
        return true;
    }

    /** The player picked the option at {@code index} of the list they were shown. */
    public static boolean choose(ServerPlayer player, int sessionId, int step, int index) {
        DialogueSession session = current(player, sessionId, step, Waiting.CHOICE);
        if (session == null) {
            return false;
        }
        if (index < 0 || index >= session.shown.size()) {
            reject(player, "option " + index + " was never shown");
            return false;
        }
        String choiceNode = session.node;
        Choice choice = (Choice) session.dialogue.nodes().get(choiceNode);
        int optionIndex = session.shown.get(index);
        Option option = choice.options().get(optionIndex);
        StoryContext context = context(session);
        ResourceLocation once = option.once() ? onceKey(session.dialogueId, choiceNode, choice.optionId(optionIndex)) : null;
        if ((once != null && isOnceUsed(player, once)) || !Conditions.check(option.conditions(), context, origin(session, choiceNode) + " option " + optionIndex)) {
            reject(player, "option " + index + " is not available");
            // Something changed since the options were shown (an item was dropped); show the current ones.
            proceed(session, choiceNode);
            return false;
        }
        session.waiting = Waiting.NONE;
        session.deadline = -1;
        // Answered: the line before it was this choice's question, not the next one's (a choice right after it has none).
        session.lastLine = "";
        if (session.dialogue.broadcast()) {
            bubble(player, player, option.text().resolve(player), -1, ThinkTime.NONE);
        }
        if (once != null) {
            setOnce(player, once, true);
        }
        boolean done = Actions.runAll(option.actions(), context, origin(session, choiceNode), OnFail.ABORT);
        if (session.closed) {
            return true;
        }
        if (!done) {
            // The price could not be paid: the option was not really chosen.
            if (once != null) {
                setOnce(player, once, false);
            }
            proceed(session, choiceNode);
            return true;
        }
        session.replyThink = ThinkTime.encode(option.think());
        proceed(session, option.next());
        return true;
    }

    /** ESC; ignored unless the dialogue is {@code closable}. */
    public static boolean leave(ServerPlayer player, int sessionId) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        if (session == null || session.id != sessionId || !session.dialogue.closable()) {
            return false;
        }
        abort(session, EndReason.LEFT);
        return true;
    }

    /** Ends the player's dialogue as finished: {@code dialogue_ended} fires. */
    public static boolean end(ServerPlayer player) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return false;
        }
        finish(session);
        return true;
    }

    /** Interrupts the player's dialogue, and the one a fight paused: {@code on_abort} runs for each. */
    public static boolean interrupt(ServerPlayer player, EndReason reason) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        DialogueSession paused = PAUSED.get(player.getUUID());
        if (session != null) {
            abort(session, reason);
        }
        if (paused != null) {
            abort(paused, reason);
        }
        return session != null || paused != null;
    }

    /**
     * The NPC the player talks to got angry at them: a dialogue that a fight pauses is paused (the hit counts as a
     * fight, also on an invulnerable NPC, whose hit never lands); any other is interrupted, {@code on_abort} running:
     * nobody talks on with someone who just attacked them.
     */
    static void attackedBy(ServerPlayer player, LivingEntity npc, long now) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        if (session == null || session.closed || !session.involves(npc)) {
            return;
        }
        if (session.base().pausesForFights()) {
            hit(npc, player, now);
        } else {
            StoryTrace.note(player, "dialogue " + session.baseId(), "stopped: the player attacked the NPC");
            abort(session, EndReason.COMBAT);
        }
    }

    /** Interrupts the dialogue the player sees, not one a fight paused: {@code on_abort} runs. */
    public static boolean interruptShown(ServerPlayer player, EndReason reason) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return false;
        }
        abort(session, reason);
        return true;
    }

    /**
     * A hit between two living things. A player in it whose dialogue a fight pauses (or stops) has it do so now, before
     * the hit could interrupt it as damage does a dialogue holding the screen.
     */
    static void hit(LivingEntity victim, @Nullable Entity attacker, long now) {
        DialogueCombat.hit(victim, attacker, now);
        for (Entity side : new Entity[] {victim, attacker}) {
            if (side instanceof ServerPlayer player && SESSIONS.get(player.getUUID()) instanceof DialogueSession session && !session.closed) {
                meetFight(session, now);
            }
        }
    }

    private static boolean inFight(DialogueSession session, long now) {
        return DialogueCombat.inFight(session.player, session.liveNpcs(), Math.round(session.base().combat().quiet() * 20), now);
    }

    /** A running dialogue in a fight: paused or interrupted when its {@code combat} says so, in subtitles or bubbles. */
    private static void meetFight(DialogueSession session, long now) {
        DialogueDefinition base = session.base();
        if (session.dialogue.presentation() == DialogueDefinition.Presentation.SCREEN || base.combat().mode() == DialogueDefinition.Combat.Mode.CONTINUE
                || !inFight(session, now)) {
            return;
        }
        if (base.combat().mode() == DialogueDefinition.Combat.Mode.STOP) {
            StoryTrace.note(session.player, "dialogue " + session.baseId(), "stopped: a fight (combat mode stop)");
            abort(session, EndReason.COMBAT);
            return;
        }
        if (PAUSED.containsKey(session.player.getUUID())) {
            // Another one paused already: only one waits, this one goes on.
            return;
        }
        hush(session);
        SESSIONS.remove(session.player.getUUID(), session);
        PAUSED.put(session.player.getUUID(), session);
        session.pausedAt = now;
        session.waiting = Waiting.NONE;
        session.deadline = -1;
        session.replyThink = ThinkTime.NONE;
        StoryboundNetwork.sendToPlayer(session.player, new DialogueClosePayload(session.id));
        StoryTrace.note(session.player, "dialogue " + session.baseId(), "paused at '" + session.node + "': a fight; it goes on once the fight is over");
    }

    /**
     * Paused dialogues: interrupted when the module is off, the NPC is gone or the pause outlasts {@code forget_after};
     * resumed once the fight is over, the player is free and the NPC is near again.
     */
    private static void tickPaused(long now) {
        double maxDistance = StoryboundConfig.COMMON.dialogueMaxDistance.get();
        for (DialogueSession session : List.copyOf(PAUSED.values())) {
            if (session.closed) {
                PAUSED.remove(session.player.getUUID(), session);
                continue;
            }
            Entity bound = session.bound;
            boolean partner = bound != null && bound != session.player;
            if (!isEnabled() || !DialogueRegistries.DIALOGUES.serverStore().contains(session.dialogueId)) {
                abort(session, EndReason.STOPPED);
            } else if (partner && !bound.isAlive()) {
                abort(session, EndReason.SPEAKER_GONE);
            } else if (now - session.pausedAt > Math.round(session.base().combat().forgetAfter() * 20)) {
                StoryTrace.note(session.player, "dialogue " + session.baseId(), "stopped: paused longer than " + session.base().combat().forgetAfter() + " s (forget_after)");
                abort(session, EndReason.COMBAT);
            } else if (!inFight(session, now) && !SESSIONS.containsKey(session.player.getUUID())) {
                double reach = partner ? effectiveMaxDistance(session, bound, maxDistance) : maxDistance;
                if (!partner || bound.level() == session.player.level() && bound.distanceToSqr(session.player) <= reach * reach) {
                    resume(session);
                }
            }
        }
    }

    /**
     * Back from a pause: one that had not started starts; otherwise the resume (the line's own, else the dialogue's
     * {@code combat.resume}) plays and its {@code @end} returns to the line or choice the fight took away, shown again
     * from its start. A resume paused itself goes straight back to its line.
     */
    private static void resume(DialogueSession session) {
        PAUSED.remove(session.player.getUUID(), session);
        SESSIONS.put(session.player.getUUID(), session);
        session.pausedAt = -1;
        StoryTrace.note(session.player, "dialogue " + session.baseId(), "goes on: the fight is over");
        if (session.notStarted) {
            session.notStarted = false;
            proceed(session, session.dialogue.start());
            return;
        }
        String interrupted = session.node;
        // The resume is the interrupted node's: a choice has none of its own, so the dialogue's plays.
        Optional<String> own = session.dialogue.nodes().get(interrupted) instanceof Line line ? line.resume() : Optional.empty();
        if (session.dialogue.nodes().get(interrupted) instanceof Choice && !session.lastLine.isEmpty()
                && session.dialogueId.equals(session.lastLineIn) && session.dialogue.nodes().get(session.lastLine) instanceof Line) {
            // A choice: its question, the last line before it, is said again after the resume.
            session.replayLine = session.lastLine;
            session.thenChoice = interrupted;
            interrupted = session.lastLine;
        }
        if (session.resuming() || own.isEmpty() && session.base().combat().resume().isEmpty()) {
            proceed(session, interrupted);
            return;
        }
        ResourceLocation baseId = session.baseId();
        DialogueDefinition base = session.base();
        session.callers.push(new DialogueSession.Frame(session.dialogueId, session.dialogue, interrupted, true));
        if (own.isEmpty()) {
            session.dialogueId = baseId;
            session.dialogue = base;
        }
        proceed(session, own.orElseGet(() -> base.combat().resume().orElseThrow()));
    }

    /** Players who see a speaker's line as a speech bubble: everyone nearby except the one in the dialogue. */
    public static List<ServerPlayer> bubbleViewers(Entity speaker, ServerPlayer participant) {
        if (!(speaker.level() instanceof ServerLevel level)) {
            return List.of();
        }
        return level.players().stream()
                .filter(viewer -> viewer != participant && viewer.distanceToSqr(speaker) <= BUBBLE_RANGE * BUBBLE_RANGE)
                .toList();
    }

    /** Timed lines, choice timers, the NPC walking off or dying, fights, dialogues waiting their turn. Every tick. Public for tests. */
    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 100 == 0) {
            DialogueCombat.prune(now);
        }
        // A paused dialogue goes on before a queued one starts: it was there first.
        tickPaused(now);
        startQueued(server, now);
        if (SESSIONS.isEmpty()) {
            return;
        }
        double maxDistance = StoryboundConfig.COMMON.dialogueMaxDistance.get();
        for (DialogueSession session : List.copyOf(SESSIONS.values())) {
            if (session.closed) {
                continue;
            }
            if (!isEnabled() || !DialogueRegistries.DIALOGUES.serverStore().contains(session.dialogueId)) {
                abort(session, EndReason.STOPPED);
                continue;
            }
            if (session.waiting == Waiting.SCENE) {
                // A scene of the talk plays: no distance, fight or timer counts until it is over.
                continue;
            }
            Entity bound = session.bound;
            if (bound != null && bound != session.player) {
                if (!bound.isAlive()) {
                    abort(session, EndReason.SPEAKER_GONE);
                    continue;
                }
                double reach = effectiveMaxDistance(session, bound, maxDistance);
                if (bound.level() != session.player.level() || bound.distanceToSqr(session.player) > reach * reach) {
                    abort(session, EndReason.TOO_FAR);
                    continue;
                }
            }
            pruneParticipants(session);
            if (session.base().npcStops()) {
                for (Entity npc : session.liveNpcs()) {
                    holdStill(session, npc);
                }
            }
            if (session.closed) {
                continue;
            }
            meetFight(session, now);
            if (session.closed || session.pausedAt >= 0) {
                continue;
            }
            if (session.deadline >= 0 && now >= session.deadline) {
                session.deadline = -1;
                if (session.waiting == Waiting.LINE) {
                    moveOn(session);
                } else if (session.waiting == Waiting.CHOICE && session.dialogue.nodes().get(session.node) instanceof Choice choice
                        && choice.timeout().isPresent()) {
                    session.waiting = Waiting.NONE;
                    proceed(session, choice.timeout().get().next());
                }
            }
        }
    }

    /**
     * Effective maximum distance before a dialogue aborts as {@code TOO_FAR}: stationary and non-escort dialogues use
     * the configured range, while walk-and-talk ({@code npc_stops: false}) with an actively following companion gets
     * generous escort reach so sprint-jumping ahead never cuts off a road conversation.
     */
    private static double effectiveMaxDistance(DialogueSession session, Entity bound, double configured) {
        double followReach = NpcMovement.followDialogueReach(bound, session.player);
        if (followReach > 0.0 && !session.base().npcStops()) {
            return Math.max(configured, followReach);
        }
        return configured;
    }

    /** The next waiting dialogue of each player who is free; requests past their time are dropped. */
    private static void startQueued(MinecraftServer server, long now) {
        if (QUEUED.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Deque<Queued>> entry : List.copyOf(QUEUED.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                QUEUED.remove(entry.getKey());
                continue;
            }
            if (SESSIONS.containsKey(entry.getKey())) {
                continue;
            }
            Deque<Queued> queue = entry.getValue();
            Queued first = queue.peek();
            if (first != null && now <= first.expiresAt() && PAUSED.containsKey(entry.getKey())) {
                DialogueDefinition waiting = definition(first.dialogue());
                if (waiting != null && waiting.pausesForFights()) {
                    // It would pause in its turn: it waits for the paused one to go on and end.
                    continue;
                }
            }
            Queued next = queue.poll();
            if (queue.isEmpty()) {
                QUEUED.remove(entry.getKey());
            }
            if (next == null) {
                continue;
            }
            if (now > next.expiresAt()) {
                StoryTrace.note(player, "dialogue " + next.dialogue(), "not started: waited longer than " + QUEUE_WAIT_TICKS / 20 + " s for the player to be free");
                continue;
            }
            Entity speaker = next.speaker() != null && next.speaker().isAlive() ? next.speaker() : null;
            start(player, next.dialogue(), speaker);
        }
    }

    /** The music of the dialogue the player is in, while it runs (a paused one too); empty when it has none. */
    public static Optional<ResourceLocation> music(ServerPlayer player) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        if (session == null || session.closed) {
            return Optional.empty();
        }
        return session.base().music();
    }

    /** Whether the entity is involved in anyone's dialogue now, a paused one too: then it does not chatter. */
    public static boolean inDialogue(Entity entity) {
        for (Map<UUID, DialogueSession> sessions : List.of(SESSIONS, PAUSED)) {
            for (DialogueSession session : sessions.values()) {
                if (!session.closed && session.involves(entity)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether an active dialogue holds this entity still ({@code npc_stops: true}); false for walking HUD talks or paused talks. */
    public static boolean holdsStill(Entity entity) {
        for (DialogueSession session : SESSIONS.values()) {
            if (!session.closed && session.involves(entity) && session.base().npcStops()) {
                return true;
            }
        }
        return false;
    }

    /** True while the player talks to this entity; their right-clicks on it do nothing else. */
    static boolean isTalkingTo(ServerPlayer player, Entity entity) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        return session != null && session.involves(entity);
    }

    static void reset() {
        SESSIONS.clear();
        PAUSED.clear();
        ADDRESSED.clear();
        QUEUED.clear();
        DialogueCombat.reset();
    }

    /** A dialogue an action asked for while the player was talking. */
    private record Queued(ResourceLocation dialogue, @Nullable Entity speaker, long expiresAt) {}

    /** Walks invisible nodes until something is shown or the dialogue ends. */
    private static void proceed(DialogueSession session, String start) {
        String target = start;
        for (int steps = 0; steps < MAX_STEPS; steps++) {
            if (session.closed) {
                return;
            }
            if (target.equals(DialogueDefinition.EXIT)) {
                finish(session);
                return;
            }
            if (target.equals(DialogueDefinition.END)) {
                DialogueSession.Frame caller = session.callers.poll();
                if (caller == null) {
                    finish(session);
                    return;
                }
                enter(session, caller.dialogueId(), caller.dialogue());
                target = caller.next();
                continue;
            }
            Node node = session.dialogue.nodes().get(target);
            if (node == null) {
                fail(session, "unknown node '" + target + "'");
                return;
            }
            session.node = target;
            switch (node) {
                case Line line -> {
                    Participant speakerPart = line.speaker().equals(DialogueDefinition.PLAYER)
                            ? null : session.dialogue.participants().get(line.speaker());
                    if (speakerPart != null && speakerPart.kind() == Participant.Kind.ENTITY
                            && resolveParticipant(session, line.speaker(), speakerPart) == null) {
                        StoryTrace.note(session.player, "dialogue " + session.dialogueId,
                                "skipped '" + target + "': participant '" + line.speaker() + "' is not nearby");
                        target = nextAfterLine(session, line);
                        continue;
                    }
                    showLine(session, line);
                    return;
                }
                case Choice choice -> {
                    if (showChoice(session, choice)) {
                        return;
                    }
                    Storybound.LOGGER.warn("Storybound: dialogue {} choice '{}' has no option to show to {}; moving on",
                            session.dialogueId, target, session.player.getGameProfile().getName());
                    target = choice.timeout().map(DialogueDefinition.Timeout::next).orElse(DialogueDefinition.END);
                }
                case DialogueDefinition.Branch branch -> target = Conditions.check(branch.conditions(), context(session), origin(session, target))
                        ? branch.then() : branch.otherwise();
                case DialogueDefinition.Act act -> {
                    boolean done = Actions.runAll(act.actions(), context(session), origin(session, target), act.onFail());
                    if (session.closed) {
                        return;
                    }
                    if (!done && act.onFail() == OnFail.ABORT) {
                        abort(session, EndReason.ERROR);
                        return;
                    }
                    target = act.next();
                }
                case DialogueDefinition.RandomNode random -> target = pick(session.player, random);
                case DialogueDefinition.Jump jump -> {
                    if (jump.dialogue().isPresent()) {
                        DialogueDefinition other = definition(jump.dialogue().get());
                        if (other == null) {
                            fail(session, "jump to unknown dialogue " + jump.dialogue().get());
                            return;
                        }
                        enter(session, jump.dialogue().get(), other);
                        target = jump.node().orElse(other.start());
                    } else {
                        target = jump.node().orElseThrow();
                    }
                }
                case DialogueDefinition.Call call -> {
                    DialogueDefinition other = definition(call.dialogue());
                    if (other == null) {
                        fail(session, "call to unknown dialogue " + call.dialogue());
                        return;
                    }
                    session.callers.push(new DialogueSession.Frame(session.dialogueId, session.dialogue, call.next(), false));
                    enter(session, call.dialogue(), other);
                    target = call.node().orElse(other.start());
                }
                case DialogueDefinition.SceneNode scene -> {
                    playScene(session, scene);
                    return;
                }
                case DialogueDefinition.End end -> {
                    Actions.runAll(end.actions(), context(session), origin(session, target), OnFail.ABORT);
                    target = DialogueDefinition.END;
                }
            }
        }
        fail(session, "more than " + MAX_STEPS + " nodes in a row without showing anything (a loop?)");
    }

    /**
     * A scene node: the talk's screen closes (its session stays), the scene plays, and the talk goes on at the node's
     * {@code next} once it is over, unless the talk ended meanwhile (the player left).
     */
    private static void playScene(DialogueSession session, DialogueDefinition.SceneNode scene) {
        session.waiting = Waiting.SCENE;
        session.deadline = -1;
        StoryboundNetwork.sendToPlayer(session.player, new DialogueClosePayload(session.id));
        dev.dzenthai.storybound.module.scene.Scenes.playInDialogue(session.player, scene.scene(), () -> {
            if (session.closed || SESSIONS.get(session.player.getUUID()) != session || session.waiting != Waiting.SCENE) {
                return;
            }
            session.waiting = Waiting.NONE;
            proceed(session, scene.next());
        });
    }

    private static void moveOn(DialogueSession session) {
        Line line = (Line) session.dialogue.nodes().get(session.node);
        session.waiting = Waiting.NONE;
        session.deadline = -1;
        proceed(session, nextAfterLine(session, line));
    }

    private static String nextAfterLine(DialogueSession session, Line line) {
        String next = line.next();
        if (session.thenChoice != null && session.node.equals(session.replayLine) && session.dialogueId.equals(session.lastLineIn)) {
            // The question said again after a fight: back to its choice.
            next = session.thenChoice;
            session.thenChoice = null;
            session.replayLine = null;
        }
        return next;
    }

    /**
     * Moves the conversation into another dialogue (a jump, a call, the return from a call): a participant found for
     * the last one stays only when the new one names it under the same id the same way (the same NPC or the same
     * selector); the rest are let go, and the new dialogue's participants are looked for.
     */
    private static void enter(DialogueSession session, ResourceLocation dialogueId, DialogueDefinition dialogue) {
        DialogueDefinition before = session.dialogue;
        session.dialogueId = dialogueId;
        session.dialogue = dialogue;
        if (dialogue != before) {
            session.participants.entrySet().removeIf(entry -> {
                Participant was = before.participants().get(entry.getKey());
                Participant now = dialogue.participants().get(entry.getKey());
                boolean same = was != null && now != null && now.kind() == Participant.Kind.ENTITY
                        && Objects.equals(was.npc(), now.npc()) && Objects.equals(was.entity(), now.entity());
                if (!same) {
                    release(session, entry.getValue());
                }
                return !same;
            });
        }
        dialogue.participants().forEach((id, participant) -> {
            if (participant.kind() == Participant.Kind.ENTITY) {
                resolveParticipant(session, id, participant);
            }
        });
    }

    /** Looks for every {@code entity} participant of the dialogue the conversation starts with. */
    private static void resolveAllParticipants(DialogueSession session) {
        enter(session, session.dialogueId, session.dialogue);
    }

    /** An entity leaves the conversation: it no longer looks where this conversation told it to. */
    private static void release(DialogueSession session, @Nullable Entity entity) {
        if (entity != null) {
            ADDRESSED.remove(entity.getUUID(), session.player.getUUID());
            session.gazeTargets.remove(entity.getUUID());
        }
    }

    /** Whether another player's conversation, a paused one too, has this entity: then it is not taken as a participant. */
    private static boolean busyElsewhere(Entity entity, ServerPlayer player) {
        for (Map<UUID, DialogueSession> sessions : List.of(SESSIONS, PAUSED)) {
            for (DialogueSession other : sessions.values()) {
                if (!other.closed && other.player != player && other.involves(entity)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Drops resolved extra participants that died, changed dimension, or walked outside their search radius. */
    private static void pruneParticipants(DialogueSession session) {
        if (session.participants.isEmpty()) {
            return;
        }
        ServerPlayer player = session.player;
        session.participants.entrySet().removeIf(entry -> {
            Entity entity = entry.getValue();
            Participant def = session.dialogue.participants().get(entry.getKey());
            double radius = def != null ? def.searchRadius() : Participant.DEFAULT_RADIUS;
            boolean gone = entity == null || !entity.isAlive() || entity.level() != player.level()
                    || entity.distanceToSqr(player) > radius * radius;
            if (gone) {
                release(session, entity);
            }
            return gone;
        });
    }

    /**
     * Finds or refreshes the live entity for an {@code entity} participant: by Storybound NPC binding id (using its
     * one-of-a-kind holder or nearest matching entity) or by an entity selector. Never picks the player, an entity
     * already assigned to another participant slot in this conversation, or one in another player's conversation.
     */
    private static @Nullable Entity resolveParticipant(DialogueSession session, String id, Participant participant) {
        ServerPlayer player = session.player;
        double radius = participant.searchRadius();
        double maxDistSqr = radius * radius;
        Entity existing = session.participants.get(id);
        if (existing != null) {
            if (existing.isAlive() && existing.level() == player.level() && existing.distanceToSqr(player) <= maxDistSqr) {
                return existing;
            }
            session.participants.remove(id);
            release(session, existing);
        }
        Set<Entity> taken = session.claimedEntities(id);
        Predicate<Entity> free = candidate -> candidate != player && !taken.contains(candidate) && !busyElsewhere(candidate, player);
        Entity found = null;
        if (participant.npc().isPresent()) {
            found = findByNpcBinding(player, participant.npc().get(), radius, free);
        } else if (participant.entity().isPresent()) {
            EntitySelector selector = participant.entity().get();
            double bestDist = maxDistSqr;
            for (Entity candidate : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(radius), Entity::isAlive)) {
                double dist = candidate.distanceToSqr(player);
                if (dist <= bestDist && selector.matches(candidate) && free.test(candidate)) {
                    found = candidate;
                    bestDist = dist;
                }
            }
        }
        if (found != null) {
            session.participants.put(id, found);
        }
        return found;
    }

    private static @Nullable Entity findByNpcBinding(ServerPlayer player, ResourceLocation npcId, double radius, Predicate<Entity> free) {
        NpcBinding binding = DialogueRegistries.NPC_BINDINGS.serverStore().getOrNull(npcId);
        if (binding == null) {
            return null;
        }
        double maxDistSqr = radius * radius;
        if (binding.isOne()) {
            NpcParts.Part part = NpcParts.holding(player.server, npcId);
            if (part == null) {
                for (Entity candidate : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(radius), Entity::isAlive)) {
                    NpcParts.consider(candidate);
                    part = NpcParts.holding(player.server, npcId);
                    if (part != null) {
                        NpcNames.apply(candidate);
                        break;
                    }
                }
            }
            Entity holder = part != null ? player.serverLevel().getEntity(part.holder()) : null;
            return holder != null && holder.isAlive() && holder.level() == player.level()
                    && holder.distanceToSqr(player) <= maxDistSqr && free.test(holder) ? holder : null;
        }
        Entity best = null;
        double bestDist = maxDistSqr;
        for (Entity candidate : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(radius), Entity::isAlive)) {
            double dist = candidate.distanceToSqr(player);
            if (dist <= bestDist && NpcBindings.find(candidate)
                    .map(found -> found.chain().stream().anyMatch(layer -> npcId.equals(layer.id()))).orElse(false)
                    && free.test(candidate)) {
                best = candidate;
                bestDist = dist;
            }
        }
        return best;
    }

    /** Resolves who a participant id currently refers to as a live entity, or null for virtual/absent speakers. */
    private static @Nullable Entity entityFor(DialogueSession session, String participantId) {
        if (participantId.equals(DialogueDefinition.PLAYER)) {
            return session.player;
        }
        Participant participant = session.dialogue.participants().get(participantId);
        if (participant == null) {
            return null;
        }
        return switch (participant.kind()) {
            case BOUND -> session.bound != null && session.bound.isAlive() && session.bound.level() == session.player.level() ? session.bound : null;
            case ENTITY -> resolveParticipant(session, participantId, participant);
            case VIRTUAL -> null;
        };
    }

    /** Best live NPC partner for player lines, choices, or fallback camera framing. */
    private static @Nullable Entity bestNpcPartner(DialogueSession session) {
        ServerPlayer player = session.player;
        Entity last = session.lastNpcSpeaker;
        if (last != null && last != player && last.isAlive() && last.level() == player.level() && session.involves(last)) {
            return last;
        }
        if (session.bound != null && session.bound != player && session.bound.isAlive() && session.bound.level() == player.level()) {
            return session.bound;
        }
        List<Entity> live = session.liveNpcs();
        return live.isEmpty() ? null : live.getFirst();
    }

    /** Who the current speaker is addressing: explicit {@code to} if alive, else player (or last NPC for player lines). */
    private static @Nullable Entity resolveAddressee(DialogueSession session, Line line, @Nullable Entity speaker) {
        if (line.to().isPresent()) {
            Entity explicit = entityFor(session, line.to().get());
            if (explicit != null && explicit != speaker) {
                return explicit;
            }
        }
        return speaker == session.player ? bestNpcPartner(session) : session.player;
    }

    /**
     * Sets gaze for every live NPC: the speaker looks at their addressee; all other NPCs look at the speaker. An NPC
     * that last spoke to another player keeps looking at them until it speaks in this conversation.
     */
    private static void updateGaze(DialogueSession session, @Nullable Entity speaker, @Nullable Entity listener) {
        session.gazeTargets.clear();
        ServerPlayer player = session.player;
        for (Entity npc : session.liveNpcs()) {
            Entity target;
            if (npc == speaker) {
                target = listener != null && listener != npc ? listener : player;
            } else {
                target = speaker != null && speaker != npc ? speaker : player;
            }
            session.gazeTargets.put(npc.getUUID(), target);
            ADDRESSED.putIfAbsent(npc.getUUID(), player.getUUID());
        }
    }

    private static void showLine(DialogueSession session, Line line) {
        ServerPlayer player = session.player;
        markOpen(session);
        session.lastLine = session.node;
        session.lastLineIn = session.dialogueId;
        Entity entity = null;
        Optional<ResolvedText> name = Optional.empty();
        Optional<ResourceLocation> portrait = Optional.empty();
        if (line.speaker().equals(DialogueDefinition.PLAYER)) {
            entity = player;
        } else {
            Participant participant = session.dialogue.participants().get(line.speaker());
            if (participant != null) {
                name = participant.name().map(text -> text.resolve(player));
                if (participant.kind() == Participant.Kind.BOUND) {
                    entity = session.bound;
                } else if (participant.kind() == Participant.Kind.ENTITY) {
                    entity = resolveParticipant(session, line.speaker(), participant);
                } else {
                    portrait = participant.portrait();
                }
                if (name.isEmpty() && entity != null) {
                    // The NPC's own name, also while it is not shown over its head.
                    name = NpcNames.nameOf(entity).map(text -> text.resolve(player));
                }
            }
        }
        if (entity != null && entity != player) {
            session.lastNpcSpeaker = entity;
        }
        Entity listener = resolveAddressee(session, line, entity);
        updateGaze(session, entity, listener);
        Optional<Component> entityName = Optional.ofNullable(entity).map(Entity::getDisplayName);
        ResolvedText text = line.text().resolve(player);
        // Only an NPC answering the option just chosen pauses; the code reaches the clients, which time the dots.
        int think = entity != null && entity != player ? session.replyThink : ThinkTime.NONE;
        session.replyThink = ThinkTime.NONE;
        long thinkTicks = think == ThinkTime.NONE ? 0 : (think == ThinkTime.AUTO ? ThinkTime.MAX_MILLIS : think) / 50;
        session.waiting = Waiting.LINE;
        session.step = nextStep++;
        session.shown = List.of();
        session.deadline = line.duration().map(seconds -> player.server.getTickCount() + thinkTicks + Math.max(1, Math.round(seconds * 20))).orElse(-1L);
        DialogueLinePayload.Speaker speaker = new DialogueLinePayload.Speaker(name, entityName, entity != null ? entity.getId() : -1, portrait);
        session.camera = cameraCue(session, line.shot(), line.transition(), line.lookAt(), entity, listener);
        StoryboundNetwork.sendToPlayer(player, new DialogueLinePayload(session.id, session.step, session.dialogue.presentation(),
                session.dialogue.closable(), speaker, text, line.duration().isPresent(), think, session.camera));
        Entity source = entity;
        // A new line is never said over the last one's voice-over.
        hush(session);
        line.sound().ifPresent(sound -> playSound(session, source, sound));
        line.fade().ifPresent(fade -> fade.send(player));
        session.voiceOver = line.sound().orElse(null);
        if (line.sound().isEmpty() && entity != null && entity != player) {
            // A line with no sound of its own is said in the speaker's voice.
            NpcVoice.speak(entity, session.dialogue.broadcast() ? null : player);
        }
        if (entity != null && entity != player) {
            ADDRESSED.put(entity.getUUID(), player.getUUID());
        }
        if (entity != null) {
            // Not over the player's own head, unseen in first person: the client shows those lines as subtitles.
            boolean selfBubble = session.dialogue.presentation() == DialogueDefinition.Presentation.BUBBLE && entity != player;
            if (selfBubble || session.dialogue.broadcast()) {
                bubble(entity, player, text, think == ThinkTime.NONE ? -1 : player.getId(), think, selfBubble, session.dialogue.broadcast());
            }
        }
    }

    /** @return false when no option may be shown */
    private static boolean showChoice(DialogueSession session, Choice choice) {
        ServerPlayer player = session.player;
        markOpen(session);
        StoryContext context = context(session);
        List<Integer> shown = new ArrayList<>();
        List<DialogueChoicesPayload.Shown> options = new ArrayList<>();
        for (int i = 0; i < choice.options().size(); i++) {
            Option option = choice.options().get(i);
            if (option.once() && isOnceUsed(player, onceKey(session.dialogueId, session.node, choice.optionId(i)))) {
                continue;
            }
            boolean available = Conditions.check(option.conditions(), context, origin(session, session.node) + " option " + i + " shown");
            if (!available && option.locked() == Option.Locked.HIDE) {
                continue;
            }
            shown.add(i);
            options.add(new DialogueChoicesPayload.Shown(option.text().resolve(player), available,
                    available ? Optional.empty() : option.lockedText().map(text -> text.resolve(player))));
        }
        if (shown.isEmpty()) {
            return false;
        }
        session.replyThink = ThinkTime.NONE;
        session.thenChoice = null;
        session.replayLine = null;
        int timeoutTicks = choice.timeout().map(timeout -> Math.max(1, Math.round(timeout.seconds() * 20))).orElse(0);
        session.waiting = Waiting.CHOICE;
        session.step = nextStep++;
        session.shown = List.copyOf(shown);
        session.deadline = timeoutTicks > 0 ? player.server.getTickCount() + timeoutTicks : -1;
        // On the dialogue screen the answers show at once; in subtitles and bubbles the player opens them.
        boolean asks = session.dialogue.presentation() != DialogueDefinition.Presentation.SCREEN;
        session.choiceOpen = !asks;
        session.choiceLeft = -1;
        Entity partner = bestNpcPartner(session);
        updateGaze(session, player, partner);
        session.camera = cameraCue(session, CameraCue.Shot.AUTO, CameraCue.Transition.CUT, Optional.empty(), player, partner);
        StoryboundNetwork.sendToPlayer(player, new DialogueChoicesPayload(session.id, session.step,
                DialogueChoicesPayload.flags(session.dialogue.closable(), asks), options, timeoutTicks, session.camera));
        return true;
    }

    /** The camera needs the server's permission, a dialogue that allows it, the dialogue screen and at least one live NPC. */
    private static CameraCue cameraCue(DialogueSession session, CameraCue.Shot shot, CameraCue.Transition transition, Optional<LookAt> look,
                                       @Nullable Entity speaker, @Nullable Entity listener) {
        Entity partner = speaker != null && speaker != session.player ? speaker
                : listener != null && listener != session.player ? listener : bestNpcPartner(session);
        boolean enabled = StoryboundConfig.COMMON.cameraAllowed.get()
                && session.dialogue.camera() == DialogueDefinition.Camera.AUTO
                && session.dialogue.presentation() == DialogueDefinition.Presentation.SCREEN
                && partner != null && partner != session.player;
        if (!enabled) {
            return CameraCue.OFF;
        }
        int listenerId = listener != null ? listener.getId() : -1;
        return new CameraCue(true, shot, transition, partner.getId(), listenerId, session.liveCastIds(),
                look.map(target -> target.resolve(session.player, speaker)).orElse(CameraCue.Look.NONE));
    }

    private static void finish(DialogueSession session) {
        if (session.closed) {
            return;
        }
        ResourceLocation base = session.baseId();
        String node = session.node;
        StoryTrace.event(session.player, "dialogue_ended", "dialogue", base.toString(), "node", node, "reason", "finished");
        close(session);
        NeoForge.EVENT_BUS.post(new DialogueEvent.Ended(session.player, base, session.bound, node, EndReason.FINISHED));
        TriggerDispatcher.fire(DialogueLogic.DIALOGUE_ENDED.get(), context(session), new DialogueLogic.Ended(base, node));
    }

    private static void abort(DialogueSession session, EndReason reason) {
        if (session.closed) {
            return;
        }
        ResourceLocation base = session.baseId();
        DialogueDefinition baseDialogue = session.base();
        String node = session.node;
        StoryTrace.event(session.player, "dialogue_ended", "dialogue", base.toString(), "node", node, "reason", reason.name().toLowerCase(Locale.ROOT));
        close(session);
        NeoForge.EVENT_BUS.post(new DialogueEvent.Ended(session.player, base, session.bound, node, reason));
        if (!baseDialogue.onAbort().isEmpty()) {
            Actions.runAll(baseDialogue.onAbort(), context(session), "dialogue " + base + " on_abort", OnFail.ABORT);
        }
    }

    private static void fail(DialogueSession session, String problem) {
        StoryTrace.note(session.player, "dialogue " + session.dialogueId, "stopped: " + problem);
        Storybound.LOGGER.error("Storybound: dialogue {} stopped for {}: {}", session.dialogueId, session.player.getGameProfile().getName(), problem);
        abort(session, EndReason.ERROR);
    }

    private static void close(DialogueSession session) {
        hush(session);
        session.closed = true;
        session.waiting = Waiting.NONE;
        session.deadline = -1;
        SESSIONS.remove(session.player.getUUID(), session);
        PAUSED.remove(session.player.getUUID(), session);
        // The other one of the player (a callout over a paused dialogue, or the paused one under it) stays open after a crash.
        DialogueSession other = SESSIONS.containsKey(session.player.getUUID()) ? SESSIONS.get(session.player.getUUID()) : PAUSED.get(session.player.getUUID());
        session.player.setData(DialogueRegistries.OPEN_DIALOGUE, other != null && !other.notStarted ? other.baseId().toString() : "");
        if (session.bound != null) {
            ADDRESSED.remove(session.bound.getUUID(), session.player.getUUID());
        }
        for (Entity extra : session.participants.values()) {
            if (extra != null) {
                ADDRESSED.remove(extra.getUUID(), session.player.getUUID());
            }
        }
        session.participants.clear();
        session.gazeTargets.clear();
        StoryboundNetwork.sendToPlayer(session.player, new DialogueClosePayload(session.id));
    }

    /** Saved with the player while something shows: the dialogue whose {@code on_abort} a crash must not skip. */
    private static void markOpen(DialogueSession session) {
        String base = session.baseId().toString();
        if (!base.equals(session.player.getData(DialogueRegistries.OPEN_DIALOGUE))) {
            session.player.setData(DialogueRegistries.OPEN_DIALOGUE, base);
        }
    }

    /**
     * On joining: a dialogue still open in the player's data never ended, as the game or the server stopped without
     * them logging out (a crash, a killed process). Its {@code on_abort} runs now, as it would have at a logout, so
     * whatever the story does when a dialogue is cut short (an opening whisper that starts the first quest) is not
     * lost for good. Public for tests.
     */
    public static void recoverOpen(ServerPlayer player) {
        String open = player.getData(DialogueRegistries.OPEN_DIALOGUE);
        if (open.isEmpty() || SESSIONS.containsKey(player.getUUID()) || PAUSED.containsKey(player.getUUID())) {
            return;
        }
        player.setData(DialogueRegistries.OPEN_DIALOGUE, "");
        ResourceLocation id = ResourceLocation.tryParse(open);
        DialogueDefinition dialogue = id == null ? null : definition(id);
        Storybound.LOGGER.info("Storybound: {} was in dialogue {} when the game stopped; its on_abort runs now", player.getGameProfile().getName(), open);
        StoryTrace.note(player, "dialogue " + open, "never ended (the game stopped mid-dialogue): on_abort runs on joining");
        if (dialogue != null && !dialogue.onAbort().isEmpty()) {
            Actions.runAll(dialogue.onAbort(), StoryContext.of(player), "dialogue " + open + " on_abort", OnFail.ABORT);
        }
    }

    /** The session the reply refers to, if it is still the current one and waits for that kind of reply. */
    private static @Nullable DialogueSession current(ServerPlayer player, int sessionId, int step, Waiting waiting) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        if (session == null || session.id != sessionId || session.step != step || session.waiting != waiting) {
            reject(player, "stale or unknown session " + sessionId + " step " + step);
            return null;
        }
        return session;
    }

    private static void reject(ServerPlayer player, String why) {
        Storybound.LOGGER.debug("Storybound: dropped a dialogue reply from {}: {}", player.getGameProfile().getName(), why);
    }

    /** The NPC stands still and faces the player it last spoke to. */
    private static void holdStill(DialogueSession session, Entity bound) {
        if (!(bound instanceof Mob mob)) {
            return;
        }
        mob.getNavigation().stop();
        // A goal mob (a wandering trader) keeps none of its moving or looking goals while it talks: a stroll would
        // take a step before the next stop, a look at any mob near (its llama) would turn it away from the player.
        // Vanilla gives them back within five ticks after the talk.
        mob.goalSelector.disableControlFlag(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE);
        mob.goalSelector.disableControlFlag(net.minecraft.world.entity.ai.goal.Goal.Flag.LOOK);
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        UUID addressed = ADDRESSED.get(mob.getUUID());
        if (addressed == null || addressed.equals(session.player.getUUID())) {
            Entity gaze = session.gazeTargets.getOrDefault(mob.getUUID(), session.player);
            if (gaze == null || !gaze.isAlive() || gaze.level() != mob.level() || gaze == mob) {
                gaze = session.player;
            }
            mob.getLookControl().setLookAt(gaze);
            // Brain-driven mobs (villagers) look where their LOOK_TARGET memory says.
            if (brain.checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED)) {
                brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(gaze, true));
            }
        }
    }

    private static void bubble(Entity speaker, ServerPlayer participant, ResolvedText text, int replyTo, int think) {
        bubble(speaker, participant, text, replyTo, think, false, true);
    }

    private static void bubble(Entity speaker, ServerPlayer participant, ResolvedText text, int replyTo, int think,
                               boolean includeParticipant, boolean includeOthers) {
        SpeechBubblePayload payload = new SpeechBubblePayload(speaker.getId(), text, replyTo, think);
        if (includeParticipant) {
            StoryboundNetwork.sendToPlayer(participant, payload);
        }
        if (includeOthers) {
            for (ServerPlayer viewer : bubbleViewers(speaker, participant)) {
                StoryboundNetwork.sendToPlayer(viewer, payload);
            }
        }
    }

    /** Heard around the speaker when the dialogue is broadcast, otherwise by the player only. */
    private static void playSound(DialogueSession session, @Nullable Entity speaker, ResourceLocation sound) {
        SoundEvent event = SoundEvent.createVariableRangeEvent(sound);
        if (session.dialogue.broadcast()) {
            Entity source = speaker != null ? speaker : session.player;
            session.player.serverLevel().playSound(null, source.getX(), source.getEyeY(), source.getZ(), event, SoundSource.VOICE, 1f, 1f);
        } else {
            session.player.playNotifySound(event, SoundSource.VOICE, 1f, 1f);
        }
    }

    /**
     * Stops the voice-over still playing from the last line, for whoever hears it: the player, or everyone near them
     * when the dialogue is broadcast.
     */
    private static void hush(DialogueSession session) {
        ResourceLocation sound = session.voiceOver;
        if (sound == null) {
            return;
        }
        session.voiceOver = null;
        net.minecraft.network.protocol.game.ClientboundStopSoundPacket stop = new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(sound, SoundSource.VOICE);
        if (session.dialogue.broadcast()) {
            for (ServerPlayer near : session.player.serverLevel().players()) {
                if (near.distanceToSqr(session.player) <= VOICE_OVER_RANGE * VOICE_OVER_RANGE) {
                    near.connection.send(stop);
                }
            }
        } else {
            session.player.connection.send(stop);
        }
    }

    /** The voice-over being played now in the player's dialogue; for the tests. */
    public static Optional<ResourceLocation> voiceOver(ServerPlayer player) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        return session != null ? Optional.ofNullable(session.voiceOver) : Optional.empty();
    }

    private static String pick(ServerPlayer player, DialogueDefinition.RandomNode random) {
        int total = random.options().stream().mapToInt(DialogueDefinition.RandomNode.Weighted::weight).sum();
        int roll = player.getRandom().nextInt(total);
        for (DialogueDefinition.RandomNode.Weighted option : random.options()) {
            roll -= option.weight();
            if (roll < 0) {
                return option.next();
            }
        }
        return random.options().getLast().next();
    }

    private static StoryContext context(DialogueSession session) {
        return new StoryContext(session.player, session.bound);
    }

    private static String origin(DialogueSession session, String node) {
        return "dialogue " + session.dialogueId + " node " + node;
    }

    /** The internal player flag that remembers a {@code once} option was chosen. */
    public static ResourceLocation onceKey(ResourceLocation dialogue, String node, String option) {
        return Storybound.id(ONCE_PREFIX + dialogue.getNamespace() + "/" + dialogue.getPath() + "/" + pathSafe(node) + "/" + pathSafe(option));
    }

    private static String pathSafe(String id) {
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static boolean isOnceUsed(ServerPlayer player, ResourceLocation key) {
        return StoryState.getStored(player, StoryScope.PLAYER, key).isPresent();
    }

    private static void setOnce(ServerPlayer player, ResourceLocation key, boolean used) {
        try {
            if (used) {
                StoryState.set(player, StoryScope.PLAYER, key, new StoryValue.Bool(true));
            } else {
                StoryState.clear(player, StoryScope.PLAYER, key);
            }
        } catch (StoryException e) {
            Storybound.LOGGER.warn("Storybound: could not update {}: {}", key, e.getMessage());
        }
    }

    /**
     * @param waitingForChoice false while a line is shown
     * @param shownOptions     how many options the client got (valid indexes are below it)
     * @param camera           what the client was told about the camera for what is on screen
     */
    public record State(int session, int step, ResourceLocation dialogue, String node, boolean waitingForChoice, int shownOptions, CameraCue camera) {}
}
