package dev.dzenthai.storybound.core.logic.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.ConditionType;
import dev.dzenthai.storybound.core.logic.StatRef;
import dev.dzenthai.storybound.core.logic.StoryContext;
import java.util.Optional;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;

/** The player's own progress: advancements, the experience level, statistics. */
public final class ProgressConditions {
    private ProgressConditions() {}

    /**
     * {@code { "type": "advancement", "advancement": "calamity:chapter_one" }}: the player has it ({@code "done": false}:
     * has not). An advancement that does not exist (a mod removed) is never done.
     */
    public record Advancement(ResourceLocation advancement, boolean done) implements Condition {
        public static final MapCodec<Advancement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("advancement").forGetter(Advancement::advancement),
                Codec.BOOL.optionalFieldOf("done", true).forGetter(Advancement::done)
        ).apply(instance, Advancement::new));

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.ADVANCEMENT.get();
        }

        @Override
        public boolean test(StoryContext context) {
            return isDone(context) == done;
        }

        private boolean isDone(StoryContext context) {
            AdvancementHolder holder = context.server().getAdvancements().get(advancement);
            return holder != null && context.player().getAdvancements().getOrStartProgress(holder).isDone();
        }

        @Override
        public String describe(StoryContext context) {
            if (context.server().getAdvancements().get(advancement) == null) {
                return "no advancement " + advancement + " is loaded";
            }
            return "the advancement " + advancement + " is " + (isDone(context) ? "done" : "not done");
        }
    }

    /** {@code { "type": "level", "min": 30 }}: the player's experience level is in {@code min..max}. */
    public record Level(Optional<Integer> min, Optional<Integer> max) implements Condition {
        public static final MapCodec<Level> CODEC = RecordCodecBuilder.<Level>mapCodec(instance -> instance.group(
                Codec.intRange(0, 1_000_000).optionalFieldOf("min").forGetter(Level::min),
                Codec.intRange(0, 1_000_000).optionalFieldOf("max").forGetter(Level::max)
        ).apply(instance, Level::new)).validate(level -> level.min().isEmpty() && level.max().isEmpty()
                ? DataResult.error(() -> "A level condition needs 'min' or 'max'")
                : DataResult.success(level));

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.LEVEL.get();
        }

        @Override
        public boolean test(StoryContext context) {
            int level = context.player().experienceLevel;
            return level >= min.orElse(0) && level <= max.orElse(Integer.MAX_VALUE);
        }

        @Override
        public String describe(StoryContext context) {
            return "the experience level is " + context.player().experienceLevel;
        }
    }

    /** {@code { "type": "stat", "stat": "minecraft:mob_kills", "min": 100 }}: see {@link StatRef}; {@code min} defaults to 1. */
    public record Stat(StatRef stat, int min) implements Condition {
        public static final MapCodec<Stat> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                StatRef.MAP_CODEC.forGetter(Stat::stat),
                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("min", 1).forGetter(Stat::min)
        ).apply(instance, Stat::new));

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.STAT.get();
        }

        @Override
        public boolean test(StoryContext context) {
            return stat.value(context.player()) >= min;
        }

        @Override
        public String describe(StoryContext context) {
            return stat.describe() + " is " + stat.value(context.player()) + ", wanted " + min;
        }
    }
}
