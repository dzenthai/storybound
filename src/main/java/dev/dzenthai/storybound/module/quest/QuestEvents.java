package dev.dzenthai.storybound.module.quest;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.event.PartyEvent;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Conditions;
import dev.dzenthai.storybound.core.logic.LogicOwners;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.trigger.Subscription;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.network.ModulesPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

public final class QuestEvents {
    private static final String SOURCE = Storybound.id("quests").toString();
    /** How often {@code fail_when} is checked; conditions are cheap, but every quest times every player adds up. */
    private static final int FAIL_CHECK_INTERVAL = 20;

    private QuestEvents() {}

    public static void register(IEventBus gameBus) {
        // HIGH, like story events: subscriptions must exist before anything reacts to the finished reload.
        gameBus.addListener(EventPriority.HIGH, QuestEvents::onTagsUpdated);
        // LOW: the client needs the quest definitions before the progress that refers to them.
        gameBus.addListener(EventPriority.LOW, QuestEvents::onDatapackSync);
        gameBus.addListener(QuestEvents::onPartyJoined);
        gameBus.addListener(QuestEvents::onPartyLeft);
        gameBus.addListener(QuestEvents::onServerTick);
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> QuestHints.forget(event.getEntity().getUUID()));
        gameBus.addListener((ServerStoppedEvent event) -> QuestHints.reset());
        ChapterMap.register(gameBus);
        LogicOwners.register(QuestRegistries.QUESTS, QuestEvents::collectReferences);
        ValidationService.registerValidator(QuestEvents::validate);
    }

    /** Subscribes quest starts and objectives to their triggers; nothing while the module is disabled. */
    public static void rebuild() {
        DifficultyScale.set(DifficultyScale.load(ConfigStorySource.root()));
        List<Subscription<?>> subscriptions = new ArrayList<>();
        if (Quests.isEnabled()) {
            QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> {
                definition.start().ifPresent(start -> subscriptions.add(subscribe("quest " + quest + " start", start.trigger(), context -> {
                    String refused = Quests.whyNotStart(context.player(), quest);
                    if (refused != null) {
                        StoryTrace.note(context.player(), "quest " + quest + " start", "skipped: " + refused);
                    } else if (Conditions.check(start.conditions(), context, "quest " + quest + " start")) {
                        Quests.start(context.player(), quest);
                    }
                })));
                for (QuestDefinition.Stage stage : definition.stages()) {
                    for (int i = 0; i < stage.objectives().size(); i++) {
                        int index = i;
                        stage.objectives().get(i).trigger().ifPresent(trigger -> subscriptions.add(subscribe(
                                "quest " + quest + " objective " + stage.id() + "/" + stage.objectiveId(index), trigger,
                                context -> Quests.objectiveTriggered(context, quest, stage.id(), index))));
                    }
                }
            });
        }
        TriggerDispatcher.setSubscriptions(SOURCE, subscriptions);
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            rebuild();
        }
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(player -> {
            StoryboundNetwork.sendToPlayer(player, ModulesPayload.fromConfig());
            Quests.sync(player);
        });
    }

    // Joining or leaving a party switches which party-scoped quests the player sees.
    private static void onPartyJoined(PartyEvent.Joined event) {
        syncPlayer(event);
    }

    private static void onPartyLeft(PartyEvent.Left event) {
        syncPlayer(event);
    }

    private static void syncPlayer(PartyEvent event) {
        ServerPlayer player = event.getServer().getPlayerList().getPlayer(event.getPlayer());
        if (player != null) {
            Quests.sync(player);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        for (ServerPlayer player : List.copyOf(event.getServer().getPlayerList().getPlayers())) {
            // Each player on a tick of their own: a full server spreads the checks over the second instead of a spike.
            if (Math.floorMod(tick + player.getId(), FAIL_CHECK_INTERVAL) == 0) {
                Quests.checkFailures(player);
                Quests.checkItems(player);
                QuestHints.check(player);
            }
        }
    }

    private static <E> Subscription<E> subscribe(String name, Trigger<E> trigger, Subscription.Listener listener) {
        return new Subscription<>(name, trigger, listener);
    }

    /**
     * Checks each quest's own structure (unique stage ids, {@code next} targets, objectives with triggers)
     * and that every definition with logic refers only to existing quests and stages.
     */
    private static void validate(Consumer<ValidationIssue> sink) {
        DefinitionType<QuestDefinition> type = QuestRegistries.QUESTS;
        DifficultyScale scale = DifficultyScale.load(ConfigStorySource.root());
        type.serverStore().all().forEach((id, quest) -> {
            quest.difficulty().ifPresent(difficulty -> {
                if (difficulty.level().isPresent() && scale.level(difficulty).isEmpty()) {
                    sink.accept(ValidationIssue.warning(type.id(), id, "Difficulty '" + difficulty.level().get() + "' is not a level of the scale in project.json"));
                }
                if (scale.style() == DifficultyScale.Style.LEVELS && difficulty.stars().isPresent() && difficulty.stars().get() > scale.levels().size()) {
                    sink.accept(ValidationIssue.warning(type.id(), id, "Difficulty " + difficulty.stars().get() + " has no level of its own: the scale in project.json has "
                            + scale.levels().size() + " levels, so it shows as the last one"));
                }
            });
            Set<String> stageIds = new HashSet<>();
            for (QuestDefinition.Stage stage : quest.stages()) {
                if (stage.id().startsWith("@")) {
                    sink.accept(ValidationIssue.error(type.id(), id, "Stage id '" + stage.id() + "' may not start with '@'"));
                }
                if (!stageIds.add(stage.id())) {
                    sink.accept(ValidationIssue.error(type.id(), id, "Duplicate stage id '" + stage.id() + "'"));
                }
                Set<String> objectiveIds = new HashSet<>();
                for (int i = 0; i < stage.objectives().size(); i++) {
                    if (!objectiveIds.add(stage.objectiveId(i))) {
                        sink.accept(ValidationIssue.error(type.id(), id, "Duplicate objective id '" + stage.objectiveId(i) + "' in stage '" + stage.id() + "'"));
                    }
                    if (stage.objectives().get(i).trigger().isEmpty() && stage.objectives().get(i).items().isEmpty()) {
                        sink.accept(ValidationIssue.error(type.id(), id, "Objective '" + stage.objectiveId(i) + "' in stage '" + stage.id() + "' has neither a trigger nor items"));
                    }
                }
            }
            for (QuestDefinition.Stage stage : quest.stages()) {
                stage.next().ifPresent(next -> {
                    if (!next.equals(QuestDefinition.COMPLETE) && !next.equals(QuestDefinition.FAIL) && !stageIds.contains(next)) {
                        sink.accept(ValidationIssue.error(type.id(), id, "Stage '" + stage.id() + "' leads to unknown stage '" + next + "'"));
                    }
                });
            }
        });
        LogicOwners.walkAll((owner, id) -> unknownQuests(owner, id, sink));
        // Migrations rename stages and objectives inside a quest named by its new id.
        CoreDefinitions.MIGRATIONS.serverStore().all().forEach((file, migration) -> {
            migration.stages().forEach((quest, stages) -> {
                QuestDefinition definition = type.serverStore().getOrNull(quest);
                if (definition == null) {
                    sink.accept(ValidationIssue.warning(CoreDefinitions.MIGRATIONS.id(), file, "stages of unknown quest " + quest + " (name it by its new id)"));
                    return;
                }
                stages.forEach((from, to) -> {
                    if (definition.stage(to).isEmpty()) {
                        sink.accept(ValidationIssue.warning(CoreDefinitions.MIGRATIONS.id(), file, "stages " + quest + " " + from + " → " + to + ": the quest has no stage '" + to + "'"));
                    }
                });
            });
            migration.objectives().keySet().stream().filter(quest -> type.serverStore().getOrNull(quest) == null).forEach(quest ->
                    sink.accept(ValidationIssue.warning(CoreDefinitions.MIGRATIONS.id(), file, "objectives of unknown quest " + quest + " (name it by its new id)")));
        });
    }

    /** Walks every id a quest's logic refers to. */
    public static void collectReferences(QuestDefinition quest, References references) {
        quest.start().ifPresent(start -> {
            start.trigger().collectReferences(references);
            start.conditions().collectReferences(references);
        });
        quest.failWhen().ifPresent(condition -> condition.collectReferences(references));
        List<Action> actions = new ArrayList<>();
        actions.addAll(quest.onStart());
        actions.addAll(quest.onComplete());
        actions.addAll(quest.onFailed());
        actions.addAll(quest.rewards().actions());
        for (QuestDefinition.Stage stage : quest.stages()) {
            actions.addAll(stage.onEnter());
            for (QuestDefinition.Objective objective : stage.objectives()) {
                objective.trigger().ifPresent(trigger -> trigger.collectReferences(references));
                objective.hints().forEach(hint -> hint.conditions().collectReferences(references));
                objective.conditions().collectReferences(references);
            }
        }
        actions.forEach(action -> action.collectReferences(references));
    }

    private static References unknownQuests(DefinitionType<?> owner, ResourceLocation ownerId, Consumer<ValidationIssue> sink) {
        return new References() {
            @Override
            public void quest(ResourceLocation quest, @Nullable String stage) {
                QuestDefinition definition = QuestRegistries.QUESTS.serverStore().getOrNull(quest);
                if (definition == null) {
                    sink.accept(ValidationIssue.error(owner.id(), ownerId, "Unknown quest " + quest));
                } else if (stage != null && definition.stage(stage).isEmpty()) {
                    sink.accept(ValidationIssue.error(owner.id(), ownerId, "Quest " + quest + " has no stage '" + stage + "'"));
                }
            }
        };
    }
}
