package dev.dzenthai.storybound.module.quest;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.StoryIds;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.ItemMatcher;
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
import net.minecraft.util.StringRepresentable;

/**
 * {@code data/<ns>/storybound/quests/<path>.json}: a story record with a status and stages.
 *
 * @param onComplete story effects, run once (in the context of the player who finished it)
 * @param rewards    for every online player sharing the quest, so each party member is paid: given at once or claimed in the Journal (see {@link QuestRewards})
 * @param announce   JSON {@code notify}: show toasts when the quest starts, advances and ends
 * @param repeatable the quest may be started again after it ends
 * @param difficulty shown in the Journal, on the scale the modpack picks in {@code project.json}
 * @param timeLimit  seconds of game time from the start; then the quest fails
 * @param visibility how the players' chapter map shows it before it is known
 */
public record QuestDefinition(ResourceLocation category, StoryScope scope, StoryText title, Optional<StoryText> description,
                              Texts text, Optional<Start> start, List<Stage> stages, Optional<Condition> failWhen,
                              List<Action> onStart, List<Action> onComplete, List<Action> onFailed, QuestRewards.Rewards rewards,
                              boolean announce, Optional<Repeatable> repeatable, Optional<Difficulty> difficulty, Optional<Double> timeLimit,
                              Visibility visibility) {
    public static final String COMPLETE = "@complete";
    public static final String FAIL = "@fail";

    // A record codec takes at most 16 fields: the rest is paired with it.
    private static final MapCodec<QuestDefinition> FIELDS = RecordCodecBuilder.mapCodec(instance -> instance.group(
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
            QuestRewards.Rewards.CODEC.optionalFieldOf("rewards", QuestRewards.Rewards.NONE).forGetter(QuestDefinition::rewards),
            Codec.BOOL.optionalFieldOf("notify", true).forGetter(QuestDefinition::announce),
            Repeatable.CODEC.optionalFieldOf("repeatable").forGetter(QuestDefinition::repeatable),
            Difficulty.CODEC.optionalFieldOf("difficulty").forGetter(QuestDefinition::difficulty),
            Codec.doubleRange(1, 10_000_000).optionalFieldOf("time_limit").forGetter(QuestDefinition::timeLimit)
    ).apply(instance, (category, scope, title, description, text, start, stages, failWhen, onStart, onComplete, onFailed, rewards,
            announce, repeatable, difficulty, timeLimit) -> new QuestDefinition(category, scope, title, description, text, start, stages,
            failWhen, onStart, onComplete, onFailed, rewards, announce, repeatable, difficulty, timeLimit, Visibility.VISIBLE)));

    public static final Codec<QuestDefinition> CODEC = Codec.mapPair(FIELDS, Visibility.CODEC.optionalFieldOf("map_visibility", Visibility.VISIBLE))
            .xmap(pair -> pair.getFirst().withVisibility(pair.getSecond()), quest -> Pair.of(quest, quest.visibility())).codec();

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
                stages.stream().map(Stage::clientView).toList(), Optional.empty(), List.of(), List.of(), List.of(), rewards.clientView(), announce,
                repeatable, difficulty, timeLimit, visibility);
    }

    QuestDefinition withVisibility(Visibility visibility) {
        return new QuestDefinition(category, scope, title, description, text, start, stages, failWhen, onStart, onComplete, onFailed, rewards,
                announce, repeatable, difficulty, timeLimit, visibility);
    }

    /**
     * How the players' chapter map shows a quest the player does not know yet: {@code visible} as it is, {@code secret}
     * as "???" until every quest before it is completed, {@code invisible} not at all until it starts.
     */
    public enum Visibility implements StringRepresentable {
        VISIBLE("visible"),
        SECRET("secret"),
        INVISIBLE("invisible");

        public static final Codec<Visibility> CODEC = StringRepresentable.fromEnum(Visibility::values);

        private final String name;

        Visibility(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * {@code "repeatable": { "cooldown": 1200, "times": 5 }} or {@code "repeatable": true}: after the quest is completed
     * or failed, its start trigger starts it again once {@code cooldown} seconds of game time have passed, at most
     * {@code times} completions in all.
     */
    public record Repeatable(double cooldown, Optional<Integer> times) {
        private static final Codec<Repeatable> OBJECT = RecordCodecBuilder.create(instance -> instance.group(
                Codec.doubleRange(0, 10_000_000).optionalFieldOf("cooldown", 0d).forGetter(Repeatable::cooldown),
                Codec.intRange(1, 1_000_000).optionalFieldOf("times").forGetter(Repeatable::times)
        ).apply(instance, Repeatable::new));
        public static final Codec<Repeatable> CODEC = Codec.either(Codec.BOOL, OBJECT).comapFlatMap(
                either -> either.map(flag -> flag ? DataResult.success(new Repeatable(0, Optional.empty()))
                        : DataResult.error(() -> "Leave 'repeatable' out instead of false"), DataResult::success),
                repeatable -> repeatable.cooldown() == 0 && repeatable.times().isEmpty() ? Either.left(true) : Either.right(repeatable));

        public long cooldownTicks() {
            return Math.round(cooldown * 20);
        }
    }

    /** {@code "difficulty": 3} (stars) or {@code "difficulty": "hard"} (a level of the modpack's scale). */
    public record Difficulty(Optional<Integer> stars, Optional<String> level) {
        public static final Codec<Difficulty> CODEC = Codec.either(Codec.intRange(1, DifficultyScale.MAX_STARS), Codec.STRING).xmap(
                either -> either.map(stars -> new Difficulty(Optional.of(stars), Optional.empty()), level -> new Difficulty(Optional.empty(), Optional.of(level))),
                difficulty -> difficulty.stars().<Either<Integer, String>>map(Either::left).orElseGet(() -> Either.right(difficulty.level().orElse(""))));
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
     * @param hint    shown under the objective in the Quests tab until the objective is done
     */
    /**
     * @param items {@code count} items to have (or, with {@code consume}, to hand in); without a trigger they are
     *              looked at every second, with one when it fires
     */
    public record Objective(Optional<String> id, Optional<Trigger<?>> trigger, Condition conditions, int count,
                            Optional<StoryText> text, boolean hidden, boolean optional, Optional<StoryText> hint, Optional<Items> items,
                            List<Hint> hints) {
        public static final Codec<Objective> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("id").forGetter(Objective::id),
                LogicCodecs.TRIGGER.optionalFieldOf("trigger").forGetter(Objective::trigger),
                LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Objective::conditions),
                Codec.intRange(1, 100_000).optionalFieldOf("count", 1).forGetter(Objective::count),
                StoryText.CODEC.optionalFieldOf("text").forGetter(Objective::text),
                Codec.BOOL.optionalFieldOf("hidden", false).forGetter(Objective::hidden),
                Codec.BOOL.optionalFieldOf("optional", false).forGetter(Objective::optional),
                StoryText.CODEC.optionalFieldOf("hint").forGetter(Objective::hint),
                Items.CODEC.optionalFieldOf("items").forGetter(Objective::items),
                Hint.CODEC.listOf().optionalFieldOf("hints", List.of()).forGetter(Objective::hints)
        ).apply(instance, Objective::new));

        public Objective(Optional<String> id, Optional<Trigger<?>> trigger, Condition conditions, int count,
                         Optional<StoryText> text, boolean hidden, boolean optional) {
            this(id, trigger, conditions, count, text, hidden, optional, Optional.empty(), Optional.empty(), List.of());
        }

        Objective clientView() {
            // The items stay: the Journal and JEI show what the objective asks for.
            return new Objective(id, Optional.empty(), LogicConditions.ALWAYS, count, text, hidden, optional, hint, items,
                    hints.stream().map(Hint::clientView).toList());
        }
    }

    /**
     * {@code { "text": "mypack.hint.wait_night", "conditions": { "type": "time", "period": "day" }, "after": 300 }}: shown under
     * the objective while its conditions hold and, with {@code after}, once the objective has not moved for that many
     * seconds (the player is stuck). The server decides which hints show (see {@link QuestHints}); clients get the texts.
     */
    public record Hint(StoryText text, Condition conditions, Optional<Double> after) {
        public static final Codec<Hint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StoryText.CODEC.fieldOf("text").forGetter(Hint::text),
                LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Hint::conditions),
                Codec.doubleRange(1, 86_400).optionalFieldOf("after").forGetter(Hint::after)
        ).apply(instance, Hint::new));

        Hint clientView() {
            return new Hint(text, LogicConditions.ALWAYS, after);
        }
    }

    /**
     * {@code "items": { "item": "minecraft:leather", "consume": true }}: the objective's {@code count} items. Without
     * {@code consume} it counts what the player holds (also what was there before the stage), never going back down;
     * with it the items are handed in as they come, taken from the inventory.
     */
    public record Items(ItemMatcher matcher, boolean consume) {
        public static final Codec<Items> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemMatcher.MAP_CODEC.forGetter(Items::matcher),
                Codec.BOOL.optionalFieldOf("consume", false).forGetter(Items::consume)
        ).apply(instance, Items::new));
    }
}
