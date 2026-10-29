package dev.dzenthai.storybound.module.quest;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.event.QuestChangedEvent;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.Conditions;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.action.WorldActions;
import dev.dzenthai.storybound.core.logic.condition.WorldConditions;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.notify.Notifications;
import dev.dzenthai.storybound.core.notify.NotifyKind;
import dev.dzenthai.storybound.core.state.PartyData;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.network.QuestSyncPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * The quest rules. Every change goes through here: it is written to the quest's scope, posted as
 * {@link QuestChangedEvent}, fired as triggers, synced to everyone sharing the quest and announced
 * with toasts.
 */
public final class Quests {
    /** Quests pinned on the HUD at most. */
    public static final int MAX_PINNED = 5;

    private Quests() {}

    /** {@code modules.quests}; while disabled, quests neither start nor progress (a reset still works). */
    public static boolean isEnabled() {
        return StoryboundConfig.COMMON.quests.get();
    }

    public static @Nullable QuestDefinition definition(ResourceLocation quest) {
        return QuestRegistries.QUESTS.serverStore().getOrNull(quest);
    }

    /** Progress in the quest's own scope; empty means unknown. */
    public static Optional<QuestProgress> progress(ServerPlayer player, ResourceLocation quest) {
        QuestDefinition definition = definition(quest);
        return definition == null ? Optional.empty() : StoryState.getQuest(player, definition.scope(), quest);
    }

    /** The overworld's game time: what quest times (starts, ends, limits, cooldowns) are counted in. */
    static long now(ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    /**
     * Why the quest cannot start for the player now, or null when it can: unknown, the module off, already known (a
     * repeatable quest that ended can start again after its cooldown, until its times are used up).
     */
    public static @Nullable String whyNotStart(ServerPlayer player, ResourceLocation quest) {
        QuestDefinition definition = definition(quest);
        if (definition == null) {
            return "unknown quest";
        }
        if (!isEnabled()) {
            return "the quests module is disabled";
        }
        Optional<QuestProgress> known = progress(player, quest);
        if (known.isEmpty()) {
            return null;
        }
        QuestProgress progress = known.get();
        if (definition.repeatable().isEmpty() || progress.status() == QuestStatus.ACTIVE) {
            return "already " + describe(known);
        }
        QuestDefinition.Repeatable repeatable = definition.repeatable().get();
        if (repeatable.times().isPresent() && progress.completions() >= repeatable.times().get()) {
            return "repeated " + progress.completions() + " of " + repeatable.times().get() + " times";
        }
        long left = progress.finishedAt() + repeatable.cooldownTicks() - now(player);
        return left > 0 ? "repeatable again in " + (left + 19) / 20 + " s" : null;
    }

    /**
     * Starts an unknown quest at its first stage, or a repeatable one that ended over again (counters and log anew).
     *
     * @return false if it cannot start now, see {@link #whyNotStart}
     */
    public static boolean start(ServerPlayer player, ResourceLocation quest) {
        String refused = whyNotStart(player, quest);
        if (refused != null) {
            StoryTrace.note(player, "quest " + quest, "not started: " + refused);
            return false;
        }
        begin(player, quest, definition(quest), progress(player, quest));
        return true;
    }

    /** Puts the quest at its first stage, counters and log anew (completions kept), and runs its start. */
    private static void begin(ServerPlayer player, ResourceLocation quest, QuestDefinition definition, Optional<QuestProgress> known) {
        String first = definition.firstStage().orElse(null);
        QuestProgress started = known.map(progress -> progress.restarted(first, now(player))).orElseGet(() -> QuestProgress.started(first, now(player)));
        write(player, quest, definition, known, started);
        announce(player, definition, NotifyKind.QUEST_STARTED);
        for (ServerPlayer member : sharing(player, definition)) {
            if (tracked(member).isEmpty()) {
                track(member, quest);
            }
        }
        run(definition.onStart(), player, "quest " + quest + " on_start");
        definition.firstStage().flatMap(definition::stage)
                .ifPresent(stage -> run(stage.onEnter(), player, "quest " + quest + " stage " + stage.id() + " on_enter"));
    }

    /**
     * A finished quest made active again. A completed one starts over from its first stage (done again, as a
     * repeatable quest is); a failed one goes on from the stage it failed at with its counters (tried again), its time
     * limit counting anew. A stage whose objectives were all done when it ended has its counters reset, and a stage
     * that no longer exists means starting over: either way the quest could never move again.
     */
    private static void reopen(ServerPlayer player, ResourceLocation quest, QuestDefinition definition, QuestProgress finished) {
        QuestDefinition.Stage stage = finished.stage().flatMap(definition::stage).orElse(null);
        boolean lostStage = stage == null && !definition.stages().isEmpty();
        if (finished.status() == QuestStatus.COMPLETED || lostStage) {
            StoryTrace.note(player, "quest " + quest, "made active again: " + (lostStage ? "its stage is gone, " : "") + "starts over");
            begin(player, quest, definition, Optional.of(finished));
            return;
        }
        boolean stuck = stage != null && stage.isComplete(finished);
        StoryTrace.note(player, "quest " + quest, "made active again: goes on" + (stuck ? ", the stage's counters anew" : ""));
        write(player, quest, definition, Optional.of(finished), finished.resumed(now(player), stuck));
        announce(player, definition, NotifyKind.QUEST_STARTED);
        for (ServerPlayer member : sharing(player, definition)) {
            if (tracked(member).isEmpty()) {
                track(member, quest);
            }
        }
    }

    /**
     * A quest whose {@code scope} the author changed keeps its progress: progress is read from the quest's scope only,
     * so without this a quest made {@code party} after players had it as {@code player} would be unknown to everyone,
     * and nothing would start it again. When the quest has no progress in its scope for this player but some in
     * another one:
     * <ul>
     * <li>into a shared scope ({@code party}, {@code world}), the furthest progress among the players online who share
     * it moves there (completed, then active with the longest log, then failed) and replaces their own copies (an
     * offline member's stays hidden in their data);</li>
     * <li>into {@code player}, the player gets a copy of their party's (or else the world's) progress, which stays for
     * the other members to copy too.</li>
     * </ul>
     * Only data moves: no triggers fire, no actions run, no rewards are paid. On every data load and login, after the
     * migrations and before {@link #repairStages}. Public for tests.
     */
    public static void adoptScopes(ServerPlayer player) {
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> {
            StoryScope scope = definition.scope();
            if (StoryState.getQuest(player, scope, quest).isPresent()) {
                return;
            }
            if (scope == StoryScope.PLAYER) {
                for (StoryScope shared : List.of(StoryScope.PARTY, StoryScope.WORLD)) {
                    Optional<QuestProgress> found = StoryState.getQuest(player, shared, quest);
                    if (found.isPresent()) {
                        adopted(player, quest, "a copy of the " + shared.getSerializedName() + " progress");
                        StoryState.setQuest(player, scope, quest, found.get());
                        sync(player);
                        return;
                    }
                }
                return;
            }
            ServerPlayer from = null;
            QuestProgress furthest = null;
            for (ServerPlayer member : sharing(player, definition)) {
                Optional<QuestProgress> own = StoryState.getQuest(member, StoryScope.PLAYER, quest);
                if (own.isPresent() && (furthest == null || own.get().isFurtherThan(furthest))) {
                    from = member;
                    furthest = own.get();
                }
            }
            if (furthest == null) {
                return;
            }
            adopted(player, quest, "moved from " + from.getGameProfile().getName() + "'s own progress, the furthest of those online");
            StoryState.setQuest(player, scope, quest, furthest);
            for (ServerPlayer member : sharing(player, definition)) {
                // The shared progress replaces everyone's own: switched back to player, they all get a copy of it.
                StoryState.setQuest(member, StoryScope.PLAYER, quest, null);
                sync(member);
            }
        });
    }

    private static void adopted(ServerPlayer player, ResourceLocation quest, String how) {
        Storybound.LOGGER.info("Storybound: quest {} changed its scope; progress for {}: {}", quest, player.getGameProfile().getName(), how);
        StoryTrace.note(player, "quest " + quest, "its scope changed: " + how);
    }

    /**
     * Active quests at a stage their definition no longer has (an author removed or renamed it without a migration)
     * would never move again: they go to the first stage, with a warning. On every data load and login, after the
     * migrations, so a migrated stage is never mistaken for a lost one. Public for tests.
     */
    public static void repairStages(ServerPlayer player) {
        if (!isEnabled()) {
            return;
        }
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> {
            Optional<QuestProgress> current = StoryState.getQuest(player, definition.scope(), quest);
            if (current.isEmpty() || current.get().status() != QuestStatus.ACTIVE || current.get().stage().isEmpty()
                    || definition.stage(current.get().stage().get()).isPresent() || definition.firstStage().isEmpty()) {
                return;
            }
            String lost = current.get().stage().get();
            String first = definition.firstStage().get();
            Storybound.LOGGER.warn("Storybound: quest {} of {} was at stage '{}', which no longer exists; moved to the first stage '{}'"
                    + " (rename stages with a migration to keep progress)", quest, player.getGameProfile().getName(), lost, first);
            StoryTrace.note(player, "quest " + quest, "stage '" + lost + "' no longer exists: moved to the first stage '" + first + "'");
            write(player, quest, definition, current, current.get().movedTo(first));
            announce(player, definition, NotifyKind.QUEST_UPDATED);
            definition.stage(first).ifPresent(stage -> run(stage.onEnter(), player, "quest " + quest + " stage " + first + " on_enter"));
        });
    }

    /** Moves an active quest to a stage; an unknown quest is started first. */
    public static boolean setStage(ServerPlayer player, ResourceLocation quest, String stageId) {
        QuestDefinition definition = definition(quest);
        if (definition == null || !isEnabled()) {
            return false;
        }
        QuestDefinition.Stage stage = definition.stage(stageId).orElse(null);
        if (stage == null) {
            return false;
        }
        if (progress(player, quest).isEmpty()) {
            start(player, quest);
        }
        Optional<QuestProgress> current = progress(player, quest);
        if (current.isEmpty() || current.get().status() != QuestStatus.ACTIVE) {
            return false;
        }
        if (current.get().stage().equals(Optional.of(stageId))) {
            return true;
        }
        write(player, quest, definition, current, current.get().withStage(stageId));
        announce(player, definition, NotifyKind.QUEST_UPDATED);
        run(stage.onEnter(), player, "quest " + quest + " stage " + stageId + " on_enter");
        return true;
    }

    /** {@code null} forgets the quest (back to unknown). A finished quest may be made active again. */
    public static boolean setStatus(ServerPlayer player, ResourceLocation quest, @Nullable QuestStatus status) {
        QuestDefinition definition = definition(quest);
        if (definition == null) {
            return false;
        }
        Optional<QuestProgress> current = progress(player, quest);
        if (status == null) {
            if (current.isPresent()) {
                write(player, quest, definition, current, null);
                untrack(player, quest, definition);
            }
            return true;
        }
        if (!isEnabled()) {
            return false;
        }
        if (status == QuestStatus.ACTIVE) {
            if (current.isEmpty()) {
                return start(player, quest);
            }
            if (current.get().status() != QuestStatus.ACTIVE) {
                reopen(player, quest, definition, current.get());
            }
            return true;
        }
        return finish(player, quest, definition, current, status);
    }

    private static boolean finish(ServerPlayer player, ResourceLocation quest, QuestDefinition definition, Optional<QuestProgress> current, QuestStatus status) {
        if (current.isPresent() && current.get().status() == status) {
            return true;
        }
        QuestProgress base = current.orElseGet(() -> QuestProgress.started(definition.firstStage().orElse(null), now(player)));
        write(player, quest, definition, current, base.finished(status, now(player)));
        boolean completed = status == QuestStatus.COMPLETED;
        announce(player, definition, completed ? NotifyKind.QUEST_COMPLETED : NotifyKind.QUEST_FAILED);
        untrack(player, quest, definition);
        run(completed ? definition.onComplete() : definition.onFailed(), player, "quest " + quest + (completed ? " on_complete" : " on_failed"));
        if (completed) {
            List<ServerPlayer> online = sharing(player, definition);
            for (ServerPlayer member : online) {
                QuestRewards.grant(member, quest, definition.rewards());
            }
            if (definition.scope() == StoryScope.PARTY) {
                // Members away now are paid when they next join (the world shares no list of members to wait for).
                PartyData.get(player.server).party(StoryState.owner(player, StoryScope.PARTY)).ifPresent(party -> party.members().stream()
                        .filter(member -> online.stream().noneMatch(present -> present.getUUID().equals(member)))
                        .forEach(member -> QuestRewardsOwed.get(player.server).owe(member, quest)));
            }
        }
        return true;
    }

    /** An objective's trigger fired for a player; counts it if the quest is at that stage. */
    static void objectiveTriggered(StoryContext context, ResourceLocation quest, String stageId, int objectiveIndex) {
        QuestDefinition definition = definition(quest);
        if (definition == null || !isEnabled()) {
            return;
        }
        QuestDefinition.Stage stage = definition.stage(stageId).orElse(null);
        Optional<QuestProgress> current = StoryState.getQuest(context.player(), definition.scope(), quest);
        if (stage == null || current.isEmpty() || current.get().status() != QuestStatus.ACTIVE || !current.get().stage().equals(Optional.of(stageId))) {
            // Only for a quest the player has: every kill would otherwise list every quest with a kill objective.
            if (current.isPresent()) {
                StoryTrace.note(context.player(), "quest " + quest + " stage " + stageId + " objective " + objectiveIndex,
                        "not counted: the quest is " + describe(current));
            }
            return;
        }
        QuestDefinition.Objective objective = stage.objectives().get(objectiveIndex);
        String objectiveId = stage.objectiveId(objectiveIndex);
        int count = current.get().count(objectiveId);
        String source = "quest " + quest + " stage " + stageId + " objective " + objectiveId;
        if (count >= objective.count()) {
            StoryTrace.note(context.player(), source, "not counted: already done (" + count + "/" + objective.count() + ")");
            return;
        }
        if (objective.items().isPresent()) {
            countItems(context, quest, definition, stage, current.get(), objectiveIndex, true);
            return;
        }
        if (!Conditions.check(objective.conditions(), context, source)) {
            return;
        }
        if (!firstCount(context.player(), definition, quest, objectiveId)) {
            StoryTrace.note(context.player(), source, "not counted: the same happening was counted for the shared progress already");
            return;
        }
        advance(context.player(), quest, definition, stage, current.get(), objectiveId, objective, count + 1);
    }

    /**
     * Whether a party or world quest's objective may count the happening being handled: one happening fired for
     * several players who share the progress (a kill shared with the party, a boss's victory for each credited player,
     * a party quest's change for each member online) counts once (October 29, 2026; before, once per player it
     * reached, so two members at one boss counted two). See {@link TriggerDispatcher#asOne}.
     */
    private static long countedOccurrence = -1;
    /** What the happening {@link #countedOccurrence} counted: owner, quest and objective. */
    private static final Set<String> COUNTED = new HashSet<>();

    private static boolean firstCount(ServerPlayer player, QuestDefinition definition, ResourceLocation quest, String objective) {
        if (definition.scope() == StoryScope.PLAYER) {
            return true;
        }
        UUID owner = StoryState.owner(player, definition.scope());
        if (TriggerDispatcher.countedBefore(owner)) {
            return false;
        }
        long occurrence = TriggerDispatcher.occurrence();
        if (occurrence != countedOccurrence) {
            countedOccurrence = occurrence;
            COUNTED.clear();
        }
        return COUNTED.add(owner + " " + quest + " " + objective);
    }

    /**
     * An item objective: counts what the player holds, or hands it in ({@code consume}). The conditions are checked
     * only when the count would change, so the check every second writes nothing to the trace while nothing happens.
     *
     * @param traced whether a trigger asked (its "nothing to count" goes to the trace)
     */
    private static void countItems(StoryContext context, ResourceLocation quest, QuestDefinition definition, QuestDefinition.Stage stage,
                                   QuestProgress current, int objectiveIndex, boolean traced) {
        QuestDefinition.Objective objective = stage.objectives().get(objectiveIndex);
        QuestDefinition.Items items = objective.items().orElseThrow();
        String objectiveId = stage.objectiveId(objectiveIndex);
        String source = "quest " + quest + " stage " + stage.id() + " objective " + objectiveId;
        int count = current.count(objectiveId);
        int held = WorldConditions.countItems(context.player(), items.matcher());
        int next = items.consume() ? count + Math.min(held, objective.count() - count) : Math.min(held, objective.count());
        if (next <= count) {
            if (traced) {
                StoryTrace.note(context.player(), source, "not counted: the player holds " + held + " (" + count + "/" + objective.count() + ")");
            }
            return;
        }
        if (!Conditions.check(objective.conditions(), context, source)) {
            return;
        }
        if (items.consume()) {
            // What could not be taken (an item gone in between) is not counted.
            next = count + WorldActions.removeItems(context.player(), items.matcher(), next - count);
        }
        advance(context.player(), quest, definition, stage, current, objectiveId, objective, next);
    }

    /** Item objectives without a trigger, for the player's active quests; every second (see {@link QuestEvents}). Public for tests. */
    public static void checkItems(ServerPlayer player) {
        if (!isEnabled()) {
            return;
        }
        StoryContext context = StoryContext.of(player);
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> {
            Optional<QuestProgress> current = StoryState.getQuest(player, definition.scope(), quest);
            if (current.isEmpty() || current.get().status() != QuestStatus.ACTIVE) {
                return;
            }
            QuestDefinition.Stage stage = current.get().stage().flatMap(definition::stage).orElse(null);
            if (stage == null) {
                return;
            }
            for (int i = 0; i < stage.objectives().size(); i++) {
                QuestDefinition.Objective objective = stage.objectives().get(i);
                if (objective.items().isPresent() && objective.trigger().isEmpty()) {
                    // Read again: an objective before this one may have moved the quest on.
                    Optional<QuestProgress> now = StoryState.getQuest(player, definition.scope(), quest);
                    if (now.isEmpty() || !now.get().stage().equals(Optional.of(stage.id())) || now.get().status() != QuestStatus.ACTIVE) {
                        return;
                    }
                    countItems(context, quest, definition, stage, now.get(), i, false);
                }
            }
        });
    }

    /**
     * Triggers that announce a state (see {@link Trigger#stateKind()}) fire only when it changes, so whatever waits for
     * one would be stuck if the change came first: the player walked into the village before the quest reached that
     * stage, read the document earlier, joined after a party member finished the quest the next one waits for, or the
     * quest was added after it all happened. Every second, with the other checks, this looks at the state itself:
     * <ul>
     * <li>an objective of the current stage asking for it once ({@code count} 1; "enter it three times" is left to real
     * crossings, so one cannot count twice) counts when its state holds, unless it says {@code since_stage};</li>
     * <li>a quest that is not repeatable starts when the state of its start trigger and its conditions hold (a
     * repeatable one restarts only on the event, or it would restart by itself after every cooldown).</li>
     * </ul>
     * States that stay done once they happened ({@link Trigger.StateKind#HISTORY}: a document read, a boss beaten)
     * never count in a repeatable quest. Conditions are tested quietly, so a state that holds while they do not writes
     * nothing to the trace every second. Objectives with {@code items} keep their own check. Public for tests.
     */
    public static void checkStates(ServerPlayer player) {
        if (!isEnabled()) {
            return;
        }
        StoryContext context = StoryContext.of(player);
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> {
            Optional<QuestProgress> current = StoryState.getQuest(player, definition.scope(), quest);
            boolean repeatable = definition.repeatable().isPresent();
            if (current.isEmpty()) {
                definition.start().filter(start -> !repeatable && holds(start.trigger(), repeatable, context)
                                && start.conditions().test(context) && whyNotStart(player, quest) == null)
                        .ifPresent(start -> {
                            StoryTrace.note(player, "quest " + quest + " start", "its start already holds: started");
                            start(player, quest);
                        });
                return;
            }
            QuestDefinition.Stage stage = current.filter(progress -> progress.status() == QuestStatus.ACTIVE)
                    .flatMap(QuestProgress::stage).flatMap(definition::stage).orElse(null);
            if (stage == null) {
                return;
            }
            for (int i = 0; i < stage.objectives().size(); i++) {
                QuestDefinition.Objective objective = stage.objectives().get(i);
                if (objective.count() == 1 && !objective.sinceStage() && objective.items().isEmpty()
                        && current.get().count(stage.objectiveId(i)) == 0
                        && objective.trigger().map(trigger -> holds(trigger, repeatable, context)).orElse(false)
                        && objective.conditions().test(context)) {
                    StoryTrace.note(player, "quest " + quest + " stage " + stage.id() + " objective " + stage.objectiveId(i),
                            "its state already holds: counted");
                    // Counts through the trigger's path: nothing if the quest moved meanwhile.
                    objectiveTriggered(context, quest, stage.id(), i);
                    return;
                }
            }
        });
    }

    /** Whether a trigger's state holds now and may count here (no past events in a repeatable quest). */
    private static boolean holds(Trigger<?> trigger, boolean repeatable, StoryContext context) {
        Trigger.StateKind kind = trigger.stateKind();
        if (kind == Trigger.StateKind.NONE || (kind == Trigger.StateKind.HISTORY && repeatable)) {
            return false;
        }
        try {
            return trigger.holdsNow(context);
        } catch (RuntimeException e) {
            Storybound.LOGGER.error("Storybound: checking the state of a {} trigger failed", trigger.type(), e);
            return false;
        }
    }

    /** Writes the objective's new count and moves the quest on when the stage is complete. */
    private static void advance(ServerPlayer player, ResourceLocation quest, QuestDefinition definition, QuestDefinition.Stage stage,
                                QuestProgress current, String objectiveId, QuestDefinition.Objective objective, int count) {
        StoryTrace.event(player, "objective", "quest", quest.toString(), "stage", stage.id(), "objective", objectiveId,
                "count", count + "/" + objective.count());
        QuestProgress next = current.withCount(objectiveId, count);
        write(player, quest, definition, Optional.of(current), next);
        if (stage.isComplete(next)) {
            String target = definition.nextAfter(stage);
            switch (target) {
                case QuestDefinition.COMPLETE -> setStatus(player, quest, QuestStatus.COMPLETED);
                case QuestDefinition.FAIL -> setStatus(player, quest, QuestStatus.FAILED);
                default -> setStage(player, quest, target);
            }
        }
    }

    /** Fails active quests whose time limit is up or whose {@code fail_when} now holds for this player. */
    public static void checkFailures(ServerPlayer player) {
        if (!isEnabled()) {
            return;
        }
        StoryContext context = StoryContext.of(player);
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> {
            Optional<QuestProgress> current = StoryState.getQuest(player, definition.scope(), quest);
            if (current.isEmpty() || current.get().status() != QuestStatus.ACTIVE) {
                return;
            }
            if (definition.timeLimit().isPresent()) {
                if (current.get().startedAt() == 0) {
                    // Started before the start was kept: the time limit counts from now.
                    write(player, quest, definition, current, current.get().startedNow(now(player)));
                    return;
                }
                if (now(player) - current.get().startedAt() >= Math.round(definition.timeLimit().get() * 20)) {
                    StoryTrace.note(player, "quest " + quest + " time_limit", "the time is up: the quest fails");
                    setStatus(player, quest, QuestStatus.FAILED);
                    return;
                }
            }
            if (definition.failWhen().isPresent() && definition.failWhen().get().test(context)) {
                StoryTrace.note(player, "quest " + quest + " fail_when", "holds: the quest fails");
                setStatus(player, quest, QuestStatus.FAILED);
            }
        });
    }

    /** The main pinned quest (the first). */
    public static Optional<ResourceLocation> tracked(ServerPlayer player) {
        List<ResourceLocation> pinned = pinned(player);
        return pinned.isEmpty() ? Optional.empty() : Optional.of(pinned.getFirst());
    }

    /** The quests pinned on the player's HUD, the main one first. */
    public static List<ResourceLocation> pinned(ServerPlayer player) {
        return player.getData(QuestRegistries.TRACKED).quests();
    }

    /** Pins the quest first (the main one; the oldest beyond {@link #MAX_PINNED} goes); {@code null} unpins every quest. */
    public static void track(ServerPlayer player, @Nullable ResourceLocation quest) {
        List<ResourceLocation> next = new ArrayList<>();
        if (quest != null) {
            next.add(quest);
            pinned(player).stream().filter(other -> !other.equals(quest)).limit(MAX_PINNED - 1).forEach(next::add);
        }
        player.setData(QuestRegistries.TRACKED, new QuestRegistries.TrackedQuest(next));
        sync(player);
    }

    public static void unpin(ServerPlayer player, ResourceLocation quest) {
        List<ResourceLocation> next = new ArrayList<>(pinned(player));
        if (next.remove(quest)) {
            player.setData(QuestRegistries.TRACKED, new QuestRegistries.TrackedQuest(next));
            sync(player);
        }
    }

    /** From the Journal's Pin button: only an active quest of this player can be pinned; any can be unpinned. */
    public static void trackRequested(ServerPlayer player, ResourceLocation quest, boolean pin) {
        if (!pin) {
            unpin(player, quest);
        } else if (progress(player, quest).map(progress -> progress.status() == QuestStatus.ACTIVE).orElse(false)) {
            track(player, quest);
        }
    }

    /** Every known quest of the player, each from its own scope, plus the tracked one. */
    public static void sync(ServerPlayer player) {
        StoryboundNetwork.sendToPlayer(player, new QuestSyncPayload(known(player), pinned(player), DifficultyScale.current(), QuestRewardData.of(player).pending(),
                QuestRewardData.of(player).received()));
    }

    /** Every quest the player knows, each read from its own scope. */
    public static Map<ResourceLocation, QuestProgress> known(ServerPlayer player) {
        Map<ResourceLocation, QuestProgress> quests = new LinkedHashMap<>();
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) ->
                StoryState.getQuest(player, definition.scope(), quest).ifPresent(progress -> quests.put(quest, progress)));
        return quests;
    }

    private static void write(ServerPlayer player, ResourceLocation quest, QuestDefinition definition, Optional<QuestProgress> old, @Nullable QuestProgress next) {
        StoryState.setQuest(player, definition.scope(), quest, next);
        for (ServerPlayer member : sharing(player, definition)) {
            sync(member);
        }
        Optional<QuestStatus> oldStatus = old.map(QuestProgress::status);
        Optional<QuestStatus> newStatus = Optional.ofNullable(next).map(QuestProgress::status);
        Optional<String> oldStage = old.flatMap(QuestProgress::stage);
        Optional<String> newStage = Optional.ofNullable(next).flatMap(QuestProgress::stage);
        if (oldStatus.equals(newStatus) && oldStage.equals(newStage)) {
            return;
        }
        StoryTrace.event(player, "quest", "quest", quest.toString(), "from", describe(old), "to", describe(Optional.ofNullable(next)));
        NeoForge.EVENT_BUS.post(new QuestChangedEvent(player.server, quest, definition.scope(), StoryState.owner(player, definition.scope()),
                oldStatus, newStatus, oldStage, newStage, player));
        QuestLogic.QuestChange change = new QuestLogic.QuestChange(quest, oldStatus, newStatus, oldStage, newStage);
        TriggerDispatcher.asOne(() -> notifyWitnesses(player, definition, change));
    }

    private static void notifyWitnesses(ServerPlayer player, QuestDefinition definition, QuestLogic.QuestChange change) {
        Optional<QuestStatus> oldStatus = change.oldStatus();
        Optional<QuestStatus> newStatus = change.newStatus();
        Optional<String> oldStage = change.oldStage();
        Optional<String> newStage = change.newStage();
        for (ServerPlayer witness : witnesses(player, definition)) {
            StoryContext context = StoryContext.of(witness);
            if (!oldStatus.equals(newStatus)) {
                TriggerDispatcher.fire(QuestLogic.QUEST_STATE_CHANGED.get(), context, change);
            }
            if (!oldStage.equals(newStage)) {
                TriggerDispatcher.fire(QuestLogic.QUEST_STAGE_CHANGED.get(), context, change);
            }
        }
    }

    /**
     * Who the quest triggers fire for: the player who made the change, then, for a party or world quest, everyone else
     * online who shares it, so what follows a shared quest (a personal quest starting, a story event for the player)
     * happens to each of them. The quest's own {@code on_*} actions still run once. (World quests since October 29,
     * 2026: before, a story event on a world quest's change ran for the one who made it only.)
     */
    private static List<ServerPlayer> witnesses(ServerPlayer player, QuestDefinition definition) {
        List<ServerPlayer> witnesses = new ArrayList<>(List.of(player));
        if (definition.scope() != StoryScope.PLAYER) {
            sharing(player, definition).stream().filter(member -> member != player).forEach(witnesses::add);
        }
        return witnesses;
    }

    /** {@code unknown}, {@code active at stage hunt}, {@code completed}: for the trace. */
    private static String describe(Optional<QuestProgress> progress) {
        return progress.map(value -> value.status().name().toLowerCase(Locale.ROOT) + value.stage().map(stage -> " at stage " + stage).orElse(""))
                .orElse("unknown");
    }

    private static void untrack(ServerPlayer player, ResourceLocation quest, QuestDefinition definition) {
        for (ServerPlayer member : sharing(player, definition)) {
            if (pinned(member).contains(quest)) {
                unpin(member, quest);
            }
        }
    }

    private static void announce(ServerPlayer player, QuestDefinition definition, NotifyKind kind) {
        if (!definition.announce()) {
            return;
        }
        for (ServerPlayer member : sharing(player, definition)) {
            Notifications.send(member, kind, definition.title().resolve(member), Optional.empty());
        }
    }

    /** Online players who share this quest's progress with {@code player}, always including them. */
    static List<ServerPlayer> sharing(ServerPlayer player, QuestDefinition definition) {
        List<ServerPlayer> players = new ArrayList<>(StoryState.onlinePlayersOf(player.server, definition.scope(), StoryState.owner(player, definition.scope())));
        if (!players.contains(player)) {
            players.add(player);
        }
        return players;
    }

    private static void run(List<Action> actions, ServerPlayer player, String origin) {
        if (!actions.isEmpty()) {
            Actions.runAll(actions, StoryContext.of(player), origin, OnFail.ABORT);
        }
    }
}
