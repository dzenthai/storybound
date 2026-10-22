package dev.dzenthai.storybound.core.logic.condition;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.ConditionType;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.dzenthai.storybound.core.logic.StoryContext;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Combinators, randomness and script hooks. */
public final class LogicConditions {
    /** The default for an omitted {@code conditions} field. */
    public static final Condition ALWAYS = new All(List.of());

    private LogicConditions() {}

    public record All(List<Condition> conditions) implements Condition {
        public static final MapCodec<All> CODEC = LogicCodecs.CONDITION.listOf().fieldOf("conditions").xmap(All::new, All::conditions);

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.ALL.get();
        }

        @Override
        public boolean test(StoryContext context) {
            for (Condition condition : conditions) {
                if (!condition.test(context)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void collectReferences(References references) {
            conditions.forEach(condition -> condition.collectReferences(references));
        }
    }

    public record Any(List<Condition> conditions) implements Condition {
        public static final MapCodec<Any> CODEC = LogicCodecs.CONDITION.listOf().fieldOf("conditions").xmap(Any::new, Any::conditions);

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.ANY.get();
        }

        @Override
        public boolean test(StoryContext context) {
            for (Condition condition : conditions) {
                if (condition.test(context)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void collectReferences(References references) {
            conditions.forEach(condition -> condition.collectReferences(references));
        }
    }

    public record Not(Condition condition) implements Condition {
        public static final MapCodec<Not> CODEC = LogicCodecs.CONDITION.fieldOf("condition").xmap(Not::new, Not::condition);

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.NOT.get();
        }

        @Override
        public boolean test(StoryContext context) {
            return !condition.test(context);
        }

        @Override
        public void collectReferences(References references) {
            condition.collectReferences(references);
        }
    }

    /** {@code { "type": "chance", "value": 0.25 }}. */
    public record Chance(float probability) implements Condition {
        public static final MapCodec<Chance> CODEC = Codec.floatRange(0, 1).fieldOf("value").xmap(Chance::new, Chance::probability);

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.CHANCE.get();
        }

        @Override
        public boolean test(StoryContext context) {
            return context.player().getRandom().nextFloat() < probability;
        }
    }

    /**
     * {@code { "type": "kubejs", "id": "mypack:is_night", "args": { "dimension": "minecraft:the_nether" } }}: a predicate
     * registered by a script or a mod, given {@code args} (an empty object without them).
     */
    public record Script(ResourceLocation id, JsonObject args) implements Condition {
        public static final MapCodec<Script> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(Script::id),
                LogicCodecs.JSON_OBJECT.optionalFieldOf("args", new JsonObject()).forGetter(Script::args)
        ).apply(instance, Script::new));

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.KUBEJS.get();
        }

        @Override
        public boolean test(StoryContext context) {
            return ScriptHooks.testCondition(id, context, args);
        }

        @Override
        public void collectReferences(References references) {
            references.script(id, true);
        }

        @Override
        public String describe(StoryContext context) {
            return ScriptHooks.hasCondition(id) ? "the script returned false" : "no script registers the condition " + id;
        }
    }
}
