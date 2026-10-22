package dev.dzenthai.storybound.module.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.ConditionType;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.TriggerType;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/** The quest module's additions to the logic language. */
public final class QuestLogic {
    static final DeferredRegister<ConditionType<?>> REGISTER_CONDITIONS = DeferredRegister.create(LogicRegistries.CONDITION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<ActionType<?>> REGISTER_ACTIONS = DeferredRegister.create(LogicRegistries.ACTION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<TriggerType<?, ?>> REGISTER_TRIGGERS = DeferredRegister.create(LogicRegistries.TRIGGER_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ConditionType<?>, ConditionType<QuestIs>> QUEST =
            REGISTER_CONDITIONS.register("quest", () -> new ConditionType<>(QuestIs.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<SetQuestState>> SET_QUEST_STATE =
            REGISTER_ACTIONS.register("set_quest_state", () -> new ActionType<>(SetQuestState.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<SetQuestStage>> SET_QUEST_STAGE =
            REGISTER_ACTIONS.register("set_quest_stage", () -> new ActionType<>(SetQuestStage.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<TrackQuest>> TRACK_QUEST =
            REGISTER_ACTIONS.register("track_quest", () -> new ActionType<>(TrackQuest.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<StateChanged, QuestChange>> QUEST_STATE_CHANGED =
            REGISTER_TRIGGERS.register("quest_state_changed", () -> new TriggerType<>(StateChanged.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<StageChanged, QuestChange>> QUEST_STAGE_CHANGED =
            REGISTER_TRIGGERS.register("quest_stage_changed", () -> new TriggerType<>(StageChanged.CODEC));

    private QuestLogic() {}

    /** The payload of the quest triggers. */
    public record QuestChange(ResourceLocation quest, Optional<QuestStatus> oldStatus, Optional<QuestStatus> newStatus,
                              Optional<String> oldStage, Optional<String> newStage) {}

    /** A quest state as written in JSON, including {@code unknown}. */
    public enum State implements StringRepresentable {
        UNKNOWN("unknown", null),
        ACTIVE("active", QuestStatus.ACTIVE),
        COMPLETED("completed", QuestStatus.COMPLETED),
        FAILED("failed", QuestStatus.FAILED);

        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);

        private final String name;
        private final @Nullable QuestStatus status;

        State(String name, @Nullable QuestStatus status) {
            this.name = name;
            this.status = status;
        }

        public @Nullable QuestStatus status() {
            return status;
        }

        public boolean matches(Optional<QuestStatus> actual) {
            return actual.orElse(null) == status;
        }

        public static State of(Optional<QuestStatus> status) {
            return status.map(value -> switch (value) {
                case ACTIVE -> ACTIVE;
                case COMPLETED -> COMPLETED;
                case FAILED -> FAILED;
            }).orElse(UNKNOWN);
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * {@code { "type": "quest", "quest": "mypack:wolf_hunt", "state": "active" }} or with {@code "stage": "kill"}
     * (true while the quest is active at that stage). At least one of {@code state} and {@code stage}.
     */
    public record QuestIs(ResourceLocation quest, Optional<State> state, Optional<String> stage) implements Condition {
        public static final MapCodec<QuestIs> CODEC = RecordCodecBuilder.<QuestIs>mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("quest").forGetter(QuestIs::quest),
                State.CODEC.optionalFieldOf("state").forGetter(QuestIs::state),
                Codec.STRING.optionalFieldOf("stage").forGetter(QuestIs::stage)
        ).apply(instance, QuestIs::new)).validate(condition -> condition.state().isPresent() || condition.stage().isPresent()
                ? DataResult.success(condition)
                : DataResult.error(() -> "A quest condition needs 'state' or 'stage'"));

        @Override
        public ConditionType<?> type() {
            return QUEST.get();
        }

        @Override
        public boolean test(StoryContext context) {
            Optional<QuestProgress> progress = Quests.progress(context.player(), quest);
            if (state.isPresent() && !state.get().matches(progress.map(QuestProgress::status))) {
                return false;
            }
            return stage.isEmpty() || progress.map(value -> value.status() == QuestStatus.ACTIVE && value.stage().equals(stage)).orElse(false);
        }

        @Override
        public String describe(StoryContext context) {
            Optional<QuestProgress> progress = Quests.progress(context.player(), quest);
            String actual = State.of(progress.map(QuestProgress::status)).getSerializedName()
                    + progress.flatMap(QuestProgress::stage).map(at -> " at stage " + at).orElse("");
            return "quest " + quest + " is " + actual;
        }

        @Override
        public void collectReferences(References references) {
            references.quest(quest, stage.orElse(null));
        }
    }

    /** {@code { "type": "set_quest_state", "quest": "mypack:wolf_hunt", "state": "completed" }}; {@code unknown} resets it. */
    public record SetQuestState(ResourceLocation quest, State state) implements Action {
        public static final MapCodec<SetQuestState> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("quest").forGetter(SetQuestState::quest),
                State.CODEC.fieldOf("state").forGetter(SetQuestState::state)
        ).apply(instance, SetQuestState::new));

        @Override
        public ActionType<?> type() {
            return SET_QUEST_STATE.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            if (!Quests.setStatus(context.player(), quest, state.status())) {
                throw failure(quest);
            }
        }

        @Override
        public void collectReferences(References references) {
            references.quest(quest, null);
        }
    }

    /** Moves the quest to a stage, starting it if it is unknown. */
    public record SetQuestStage(ResourceLocation quest, String stage) implements Action {
        public static final MapCodec<SetQuestStage> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("quest").forGetter(SetQuestStage::quest),
                Codec.STRING.fieldOf("stage").forGetter(SetQuestStage::stage)
        ).apply(instance, SetQuestStage::new));

        @Override
        public ActionType<?> type() {
            return SET_QUEST_STAGE.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            if (!Quests.setStage(context.player(), quest, stage)) {
                throw failure(quest);
            }
        }

        @Override
        public void collectReferences(References references) {
            references.quest(quest, stage);
        }
    }

    /**
     * Pins a quest on the player's HUD, as the main one ({@code "pinned": false} unpins it); without {@code quest}
     * unpins every quest.
     */
    public record TrackQuest(Optional<ResourceLocation> quest, boolean pinned) implements Action {
        public static final MapCodec<TrackQuest> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("quest").forGetter(TrackQuest::quest),
                Codec.BOOL.optionalFieldOf("pinned", true).forGetter(TrackQuest::pinned)
        ).apply(instance, TrackQuest::new));

        @Override
        public ActionType<?> type() {
            return TRACK_QUEST.get();
        }

        @Override
        public void run(StoryContext context) {
            if (quest.isPresent() && !pinned) {
                Quests.unpin(context.player(), quest.get());
            } else {
                Quests.track(context.player(), quest.orElse(null));
            }
        }

        @Override
        public void collectReferences(References references) {
            quest.ifPresent(id -> references.quest(id, null));
        }
    }

    /** Fires when a quest's state changes (to {@code state}, if given). */
    public record StateChanged(Optional<ResourceLocation> quest, Optional<State> state) implements Trigger<QuestChange> {
        public static final MapCodec<StateChanged> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("quest").forGetter(StateChanged::quest),
                State.CODEC.optionalFieldOf("state").forGetter(StateChanged::state)
        ).apply(instance, StateChanged::new));

        @Override
        public TriggerType<?, QuestChange> type() {
            return QUEST_STATE_CHANGED.get();
        }

        @Override
        public boolean matches(StoryContext context, QuestChange change) {
            return quest.map(change.quest()::equals).orElse(true) && state.map(value -> value.matches(change.newStatus())).orElse(true);
        }

        @Override
        public void collectReferences(References references) {
            quest.ifPresent(id -> references.quest(id, null));
        }
    }

    /** Fires when a quest moves to another stage (to {@code stage}, if given). */
    public record StageChanged(Optional<ResourceLocation> quest, Optional<String> stage) implements Trigger<QuestChange> {
        public static final MapCodec<StageChanged> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("quest").forGetter(StageChanged::quest),
                Codec.STRING.optionalFieldOf("stage").forGetter(StageChanged::stage)
        ).apply(instance, StageChanged::new));

        @Override
        public TriggerType<?, QuestChange> type() {
            return QUEST_STAGE_CHANGED.get();
        }

        @Override
        public boolean matches(StoryContext context, QuestChange change) {
            return quest.map(change.quest()::equals).orElse(true) && stage.map(value -> change.newStage().equals(Optional.of(value))).orElse(true);
        }

        @Override
        public void collectReferences(References references) {
            quest.ifPresent(id -> references.quest(id, stage.orElse(null)));
        }
    }

    private static StoryException failure(ResourceLocation quest) {
        return new StoryException(Component.translatableWithFallback("storybound.quest.cannot_change",
                "Quest %s cannot change: unknown quest or stage, or the quest module is disabled", quest.toString()));
    }
}
