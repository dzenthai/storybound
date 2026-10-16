package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.event.DialogueEvent;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
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
    private static final String ONCE_PREFIX = "dialogue_once/";

    private static final Map<UUID, DialogueSession> SESSIONS = new HashMap<>();
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
    public static Optional<State> state(ServerPlayer player) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        return session == null ? Optional.empty()
                : Optional.of(new State(session.id, session.step, session.dialogueId, session.node, session.waiting == Waiting.CHOICE, session.shown.size(), session.camera));
    }

    /**
     * Starts a dialogue. {@code speaker} becomes the {@code bound} participant and the context entity of its logic.
     *
     * @return false if the dialogue is unknown, the module is disabled or the player is already talking
     */
    public static boolean start(ServerPlayer player, ResourceLocation dialogueId, @Nullable Entity speaker) {
        DialogueDefinition dialogue = definition(dialogueId);
        if (dialogue == null || !isEnabled() || SESSIONS.containsKey(player.getUUID()) || !player.isAlive()) {
            return false;
        }
        DialogueSession session = new DialogueSession(nextSession++, player, speaker, dialogueId, dialogue, player.server.getTickCount());
        SESSIONS.put(player.getUUID(), session);
        NeoForge.EVENT_BUS.post(new DialogueEvent.Started(player, dialogueId, speaker));
        proceed(session, dialogue.start());
        return true;
    }

    /** The player clicked past the line on screen. */
    public static boolean advance(ServerPlayer player, int sessionId, int step) {
        DialogueSession session = current(player, sessionId, step, Waiting.LINE);
        if (session == null) {
            return false;
        }
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
        if ((once != null && isOnceUsed(player, once)) || !option.conditions().test(context)) {
            reject(player, "option " + index + " is not available");
            // Something changed since the options were shown (an item was dropped); show the current ones.
            proceed(session, choiceNode);
            return false;
        }
        session.waiting = Waiting.NONE;
        session.deadline = -1;
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

    /** Interrupts the player's dialogue: {@code on_abort} runs. */
    public static boolean interrupt(ServerPlayer player, EndReason reason) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return false;
        }
        abort(session, reason);
        return true;
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

    /** Timed lines, choice timers, the NPC walking off or dying. Every tick. */
    static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        double maxDistance = StoryboundConfig.COMMON.dialogueMaxDistance.get();
        for (DialogueSession session : List.copyOf(SESSIONS.values())) {
            if (session.closed) {
                continue;
            }
            if (!isEnabled()) {
                abort(session, EndReason.STOPPED);
                continue;
            }
            Entity bound = session.bound;
            if (bound != null && bound != session.player) {
                if (!bound.isAlive()) {
                    abort(session, EndReason.SPEAKER_GONE);
                    continue;
                }
                if (bound.level() != session.player.level() || bound.distanceToSqr(session.player) > maxDistance * maxDistance) {
                    abort(session, EndReason.TOO_FAR);
                    continue;
                }
                holdStill(session, bound);
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

    /** True while the player talks to this entity; their right-clicks on it do nothing else. */
    static boolean isTalkingTo(ServerPlayer player, Entity entity) {
        DialogueSession session = SESSIONS.get(player.getUUID());
        return session != null && session.bound == entity;
    }

    static void reset() {
        SESSIONS.clear();
        ADDRESSED.clear();
    }

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
                session.dialogueId = caller.dialogueId();
                session.dialogue = caller.dialogue();
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
                case DialogueDefinition.Branch branch -> target = branch.conditions().test(context(session)) ? branch.then() : branch.otherwise();
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
                        session.dialogueId = jump.dialogue().get();
                        session.dialogue = other;
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
                    session.callers.push(new DialogueSession.Frame(session.dialogueId, session.dialogue, call.next()));
                    session.dialogueId = call.dialogue();
                    session.dialogue = other;
                    target = call.node().orElse(other.start());
                }
                case DialogueDefinition.End end -> {
                    Actions.runAll(end.actions(), context(session), origin(session, target), OnFail.ABORT);
                    target = DialogueDefinition.END;
                }
            }
        }
        fail(session, "more than " + MAX_STEPS + " nodes in a row without showing anything (a loop?)");
    }

    private static void moveOn(DialogueSession session) {
        Line line = (Line) session.dialogue.nodes().get(session.node);
        session.waiting = Waiting.NONE;
        session.deadline = -1;
        proceed(session, line.next());
    }

    private static void showLine(DialogueSession session, Line line) {
        ServerPlayer player = session.player;
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
                } else {
                    portrait = participant.portrait();
                }
            }
        }
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
        session.camera = cameraCue(session, line.shot(), line.transition());
        StoryboundNetwork.sendToPlayer(player, new DialogueLinePayload(session.id, session.step, session.dialogue.presentation(),
                session.dialogue.closable(), speaker, text, line.duration().isPresent(), think, session.camera));
        Entity source = entity;
        line.sound().ifPresent(sound -> playSound(session, source, sound));
        if (entity != null && entity != player) {
            ADDRESSED.put(entity.getUUID(), player.getUUID());
        }
        if (entity != null && session.dialogue.broadcast()) {
            bubble(entity, player, text, think == ThinkTime.NONE ? -1 : player.getId(), think);
        }
    }

    /** @return false when no option may be shown */
    private static boolean showChoice(DialogueSession session, Choice choice) {
        ServerPlayer player = session.player;
        StoryContext context = context(session);
        List<Integer> shown = new ArrayList<>();
        List<DialogueChoicesPayload.Shown> options = new ArrayList<>();
        for (int i = 0; i < choice.options().size(); i++) {
            Option option = choice.options().get(i);
            if (option.once() && isOnceUsed(player, onceKey(session.dialogueId, session.node, choice.optionId(i)))) {
                continue;
            }
            boolean available = option.conditions().test(context);
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
        int timeoutTicks = choice.timeout().map(timeout -> Math.max(1, Math.round(timeout.seconds() * 20))).orElse(0);
        session.waiting = Waiting.CHOICE;
        session.step = nextStep++;
        session.shown = List.copyOf(shown);
        session.deadline = timeoutTicks > 0 ? player.server.getTickCount() + timeoutTicks : -1;
        session.camera = cameraCue(session, CameraCue.Shot.AUTO, CameraCue.Transition.CUT);
        StoryboundNetwork.sendToPlayer(player, new DialogueChoicesPayload(session.id, session.step, session.dialogue.closable(), options, timeoutTicks, session.camera));
        return true;
    }

    /** The camera needs the server's permission, a dialogue that allows it, the dialogue screen and someone to talk to. */
    private static CameraCue cameraCue(DialogueSession session, CameraCue.Shot shot, CameraCue.Transition transition) {
        Entity partner = session.bound;
        boolean enabled = StoryboundConfig.COMMON.cameraAllowed.get()
                && session.dialogue.camera() == DialogueDefinition.Camera.AUTO
                && session.dialogue.presentation() == DialogueDefinition.Presentation.SCREEN
                && partner != null && partner != session.player;
        return enabled ? new CameraCue(true, shot, transition, partner.getId()) : CameraCue.OFF;
    }

    private static void finish(DialogueSession session) {
        if (session.closed) {
            return;
        }
        ResourceLocation base = session.baseId();
        String node = session.node;
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
        close(session);
        NeoForge.EVENT_BUS.post(new DialogueEvent.Ended(session.player, base, session.bound, node, reason));
        if (!baseDialogue.onAbort().isEmpty()) {
            Actions.runAll(baseDialogue.onAbort(), context(session), "dialogue " + base + " on_abort", OnFail.ABORT);
        }
    }

    private static void fail(DialogueSession session, String problem) {
        Storybound.LOGGER.error("Storybound: dialogue {} stopped for {}: {}", session.dialogueId, session.player.getGameProfile().getName(), problem);
        abort(session, EndReason.ERROR);
    }

    private static void close(DialogueSession session) {
        session.closed = true;
        session.waiting = Waiting.NONE;
        session.deadline = -1;
        SESSIONS.remove(session.player.getUUID(), session);
        if (session.bound != null) {
            ADDRESSED.remove(session.bound.getUUID(), session.player.getUUID());
        }
        StoryboundNetwork.sendToPlayer(session.player, new DialogueClosePayload(session.id));
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
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        UUID addressed = ADDRESSED.get(mob.getUUID());
        if (addressed == null || addressed.equals(session.player.getUUID())) {
            mob.getLookControl().setLookAt(session.player);
            // Brain-driven mobs (villagers) look where their LOOK_TARGET memory says.
            if (brain.checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED)) {
                brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(session.player, true));
            }
        }
    }

    private static void bubble(Entity speaker, ServerPlayer participant, ResolvedText text, int replyTo, int think) {
        SpeechBubblePayload payload = new SpeechBubblePayload(speaker.getId(), text, replyTo, think);
        for (ServerPlayer viewer : bubbleViewers(speaker, participant)) {
            StoryboundNetwork.sendToPlayer(viewer, payload);
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
