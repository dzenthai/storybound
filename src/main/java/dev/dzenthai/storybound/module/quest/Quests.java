package dev.dzenthai.storybound.module.quest;

import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.event.QuestChangedEvent;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.notify.NotifyKind;
import dev.dzenthai.storybound.core.notify.Notifications;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.network.QuestSyncPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Starts an unknown quest at its first stage. @return false if the quest is unknown to the data, disabled or already known */
    public static boolean start(ServerPlayer player, ResourceLocation quest) {
        QuestDefinition definition = definition(quest);
        if (definition == null || !isEnabled() || progress(player, quest).isPresent()) {
            return false;
        }
        QuestProgress started = QuestProgress.started(definition.firstStage().orElse(null));
        write(player, quest, definition, Optional.empty(), started);
        announce(player, definition, NotifyKind.QUEST_STARTED);
        for (ServerPlayer member : sharing(player, definition)) {
            if (tracked(member).isEmpty()) {
                track(member, quest);
            }
        }
        run(definition.onStart(), player, "quest " + quest + " on_start");
        definition.firstStage().flatMap(definition::stage)
                .ifPresent(stage -> run(stage.onEnter(), player, "quest " + quest + " stage " + stage.id() + " on_enter"));
        return true;
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
                write(player, quest, definition, current, current.get().withStatus(QuestStatus.ACTIVE));
                announce(player, definition, NotifyKind.QUEST_STARTED);
            }
            return true;
        }
        return finish(player, quest, definition, current, status);
    }

    private static boolean finish(ServerPlayer player, ResourceLocation quest, QuestDefinition definition, Optional<QuestProgress> current, QuestStatus status) {
        if (current.isPresent() && current.get().status() == status) {
            return true;
        }
        QuestProgress base = current.orElseGet(() -> QuestProgress.started(definition.firstStage().orElse(null)));
        write(player, quest, definition, current, base.finished(status));
        boolean completed = status == QuestStatus.COMPLETED;
        announce(player, definition, completed ? NotifyKind.QUEST_COMPLETED : NotifyKind.QUEST_FAILED);
        untrack(player, quest, definition);
        run(completed ? definition.onComplete() : definition.onFailed(), player, "quest " + quest + (completed ? " on_complete" : " on_failed"));
        if (completed) {
            for (ServerPlayer member : sharing(player, definition)) {
                run(definition.rewards(), member, "quest " + quest + " rewards");
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
            return;
        }
        QuestDefinition.Objective objective = stage.objectives().get(objectiveIndex);
        String objectiveId = stage.objectiveId(objectiveIndex);
        int count = current.get().count(objectiveId);
        if (count >= objective.count() || !objective.conditions().test(context)) {
            return;
        }
        QuestProgress next = current.get().withCount(objectiveId, count + 1);
        write(context.player(), quest, definition, current, next);
        if (stage.isComplete(next)) {
            String target = definition.nextAfter(stage);
            switch (target) {
                case QuestDefinition.COMPLETE -> setStatus(context.player(), quest, QuestStatus.COMPLETED);
                case QuestDefinition.FAIL -> setStatus(context.player(), quest, QuestStatus.FAILED);
                default -> setStage(context.player(), quest, target);
            }
        }
    }

    /** Fails active quests whose {@code fail_when} now holds for this player. */
    static void checkFailures(ServerPlayer player) {
        if (!isEnabled()) {
            return;
        }
        StoryContext context = StoryContext.of(player);
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> definition.failWhen().ifPresent(condition -> {
            Optional<QuestProgress> current = StoryState.getQuest(player, definition.scope(), quest);
            if (current.isPresent() && current.get().status() == QuestStatus.ACTIVE && condition.test(context)) {
                setStatus(player, quest, QuestStatus.FAILED);
            }
        }));
    }

    public static Optional<ResourceLocation> tracked(ServerPlayer player) {
        return player.getData(QuestRegistries.TRACKED).quest();
    }

    /** {@code null} tracks nothing. */
    public static void track(ServerPlayer player, @Nullable ResourceLocation quest) {
        player.setData(QuestRegistries.TRACKED, new QuestRegistries.TrackedQuest(Optional.ofNullable(quest)));
        sync(player);
    }

    /** From the client's Track button: only an active quest of this player can be tracked. */
    public static void trackRequested(ServerPlayer player, Optional<ResourceLocation> quest) {
        if (quest.isEmpty() || progress(player, quest.get()).map(progress -> progress.status() == QuestStatus.ACTIVE).orElse(false)) {
            track(player, quest.orElse(null));
        }
    }

    /** Every known quest of the player, each from its own scope, plus the tracked one. */
    public static void sync(ServerPlayer player) {
        Map<ResourceLocation, QuestProgress> quests = new LinkedHashMap<>();
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) ->
                StoryState.getQuest(player, definition.scope(), quest).ifPresent(progress -> quests.put(quest, progress)));
        StoryboundNetwork.sendToPlayer(player, new QuestSyncPayload(quests, tracked(player)));
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
        NeoForge.EVENT_BUS.post(new QuestChangedEvent(player.server, quest, definition.scope(), StoryState.owner(player, definition.scope()),
                oldStatus, newStatus, oldStage, newStage, player));
        QuestLogic.QuestChange change = new QuestLogic.QuestChange(quest, oldStatus, newStatus, oldStage, newStage);
        StoryContext context = StoryContext.of(player);
        if (!oldStatus.equals(newStatus)) {
            TriggerDispatcher.fire(QuestLogic.QUEST_STATE_CHANGED.get(), context, change);
        }
        if (!oldStage.equals(newStage)) {
            TriggerDispatcher.fire(QuestLogic.QUEST_STAGE_CHANGED.get(), context, change);
        }
    }

    private static void untrack(ServerPlayer player, ResourceLocation quest, QuestDefinition definition) {
        for (ServerPlayer member : sharing(player, definition)) {
            if (tracked(member).equals(Optional.of(quest))) {
                track(member, null);
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
