package dev.dzenthai.storybound.module.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.StoryIds;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code data/<ns>/storybound/quests/<path>.json}: a story record with a status and stages.
 *
 * @param onComplete story effects, run once (in the context of the player who finished it)
 * @param rewards    run for every online player sharing the quest, so each party member is paid
 * @param announce   JSON {@code notify}: show toasts when the quest starts, advances and ends
 */
public record QuestDefinition(ResourceLocation category, StoryScope scope, StoryText title, Optional<StoryText> description,
                              Texts text, Optional<Start> start, List<Stage> stages, Optional<Condition> failWhen,
                              List<Action> onStart, List<Action> onComplete, List<Action> onFailed, List<Action> rewards,
                              boolean announce) {
    public static final String COMPLETE = "@complete";
    public static final String FAIL = "@fail";

    public static final Codec<QuestDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StoryIds.CODEC.optionalFieldOf("category", Storybound.id("side")).forGetter(QuestDefinition::category),
            StoryScope.CODEC.optionalFieldOf("scope", StoryScope.PLAYER).forGetter(QuestDefinition::scope),
            StoryText.CODEC.fieldOf("title").forGetter(QuestDefinition::title),
            StoryText.CODEC.optionalFieldOf("description").forGetter(QuestDefinition::description),
            Texts.CODEC.optionalFieldOf("text", Texts.EMPTY).forGetter(QuestDefinition::text),
            Start.CODEC.optionalFieldOf("start").forGetter(QuestDefinition::start),
            Stage.CODEC.listOf().optionalFieldOf("stages", List.of()).forGetter(QuestDefinition::stages),
            LogicCodecs.CONDITION.optionalFieldOf("fail_when").forGetter(QuestDefinition::failWhen),
            LogicCodecs.ACTIONS.optionalFieldOf("on_start", List.of()).forGetter(QuestDefinition::onStart),
            LogicCodecs.ACTIONS.optionalFieldOf("on_complete", List.of()).forGetter(QuestDefinition::onComplete),
            LogicCodecs.ACTIONS.optionalFieldOf("on_failed", List.of()).forGetter(QuestDefinition::onFailed),
            LogicCodecs.ACTIONS.optionalFieldOf("rewards", List.of()).forGetter(QuestDefinition::rewards),
            Codec.BOOL.optionalFieldOf("notify", true).forGetter(QuestDefinition::announce)
    ).apply(instance, QuestDefinition::new));

    public Optional<Stage> stage(String id) {
        return stages.stream().filter(stage -> stage.id().equals(id)).findFirst();
    }

    public Optional<String> firstStage() {
        return stages.isEmpty() ? Optional.empty() : Optional.of(stages.getFirst().id());
    }

    /** Where a finished stage leads: its {@code next}, else the following stage, else completion. */
    public String nextAfter(Stage stage) {
        if (stage.next().isPresent()) {
            return stage.next().get();
        }
        int index = stages.indexOf(stage);
        return index >= 0 && index + 1 < stages.size() ? stages.get(index + 1).id() : COMPLETE;
    }

    /** What clients receive: texts, stages and objective counts; no triggers, conditions or actions. */
    QuestDefinition clientView() {
        return new QuestDefinition(category, scope, title, description, text, Optional.empty(),
                stages.stream().map(Stage::clientView).toList(), Optional.empty(), List.of(), List.of(), List.of(), List.of(), announce);
    }

    /** Description per status, shown above the stage log. */
    public record Texts(Optional<StoryText> active, Optional<StoryText> completed, Optional<StoryText> failed) {
        public static final Texts EMPTY = new Texts(Optional.empty(), Optional.empty(), Optional.empty());
        public static final Codec<Texts> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StoryText.CODEC.optionalFieldOf("active").forGetter(Texts::active),
                StoryText.CODEC.optionalFieldOf("completed").forGetter(Texts::completed),
                StoryText.CODEC.optionalFieldOf("failed").forGetter(Texts::failed)
        ).apply(instance, Texts::new));

        public Optional<StoryText> forStatus(QuestStatus status) {
            return switch (status) {
                case ACTIVE -> active;
                case COMPLETED -> completed;
                case FAILED -> failed;
            };
        }
    }

    /** Starts the quest for the player when the trigger fires and the conditions pass. */
    public record Start(Trigger<?> trigger, Condition conditions) {
        public static final Codec<Start> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                LogicCodecs.TRIGGER.fieldOf("trigger").forGetter(Start::trigger),
                LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Start::conditions)
        ).apply(instance, Start::new));
    }

    /**
     * @param log  the stage's entry in the quest's story, shown from the moment the stage is reached (Witcher-style)
     * @param next      a stage id, {@code @complete} or {@code @fail}; omitted means the following stage
     * @param waypoints static waypoints shown while the quest is at this stage
     */
    public record Stage(String id, Optional<StoryText> log, List<Objective> objectives, Optional<String> next, List<Action> onEnter,
                        List<ResourceLocation> waypoints) {
        public static final Codec<Stage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Stage::id),
                StoryText.CODEC.optionalFieldOf("log").forGetter(Stage::log),
                Objective.CODEC.listOf().optionalFieldOf("objectives", List.of()).forGetter(Stage::objectives),
                Codec.STRING.optionalFieldOf("next").forGetter(Stage::next),
                LogicCodecs.ACTIONS.optionalFieldOf("on_enter", List.of()).forGetter(Stage::onEnter),
                ResourceLocation.CODEC.listOf().optionalFieldOf("waypoints", List.of()).forGetter(Stage::waypoints)
        ).apply(instance, Stage::new));

        /** An objective's id, or its position when it has none. */
        public String objectiveId(int index) {
            return objectives.get(index).id().orElse(String.valueOf(index));
        }

        /** Every required objective has reached its count; a stage without required objectives waits for an action. */
        public boolean isComplete(QuestProgress progress) {
            boolean anyRequired = false;
            for (int i = 0; i < objectives.size(); i++) {
                Objective objective = objectives.get(i);
                if (objective.optional()) {
                    continue;
                }
                anyRequired = true;
                if (progress.count(objectiveId(i)) < objective.count()) {
                    return false;
                }
            }
            return anyRequired;
        }

        Stage clientView() {
            return new Stage(id, log, objectives.stream().map(Objective::clientView).toList(), next, List.of(), waypoints);
        }
    }

    /**
     * @param trigger required on the server (the validator reports a missing one); stripped for clients
     * @param text    the line shown in the quest view and on the HUD
     */
    public record Objective(Optional<String> id, Optional<Trigger<?>> trigger, Condition conditions, int count,
                            Optional<StoryText> text, boolean hidden, boolean optional) {
        public static final Codec<Objective> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("id").forGetter(Objective::id),
                LogicCodecs.TRIGGER.optionalFieldOf("trigger").forGetter(Objective::trigger),
                LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Objective::conditions),
                Codec.intRange(1, 100_000).optionalFieldOf("count", 1).forGetter(Objective::count),
                StoryText.CODEC.optionalFieldOf("text").forGetter(Objective::text),
                Codec.BOOL.optionalFieldOf("hidden", false).forGetter(Objective::hidden),
                Codec.BOOL.optionalFieldOf("optional", false).forGetter(Objective::optional)
        ).apply(instance, Objective::new));

        Objective clientView() {
            return new Objective(id, Optional.empty(), LogicConditions.ALWAYS, count, text, hidden, optional);
        }
    }
}
