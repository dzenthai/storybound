package dev.dzenthai.storybound.core.logic.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.ConditionType;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.StoryValues;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Checks against Story State. An omitted scope means the flag's declared scope. */
public final class StateConditions {
    private StateConditions() {}

    /** {@code { "type": "flag", "key": "mypack:met_elder", "value": false }}; {@code value} defaults to true. */
    public record Flag(ResourceLocation key, Optional<StoryScope> scope, boolean value) implements Condition {
        public static final MapCodec<Flag> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("key").forGetter(Flag::key),
                StoryScope.CODEC.optionalFieldOf("scope").forGetter(Flag::scope),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(Flag::value)
        ).apply(instance, Flag::new));

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.FLAG.get();
        }

        @Override
        public boolean test(StoryContext context) {
            StoryScope resolved = scope.orElseGet(() -> StoryState.declaredScope(key));
            StoryValue actual = StoryState.get(context.player(), resolved, key).orElse(new StoryValue.Bool(false));
            return actual.equals(new StoryValue.Bool(value));
        }

        @Override
        public void collectReferences(References references) {
            references.flag(key);
        }
    }

    /** {@code { "type": "var", "key": "mypack:gold", "op": ">=", "value": 10 }}; {@code op} defaults to {@code =}. */
    public record Var(ResourceLocation key, Optional<StoryScope> scope, Comparison op, StoryValue value) implements Condition {
        public static final MapCodec<Var> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("key").forGetter(Var::key),
                StoryScope.CODEC.optionalFieldOf("scope").forGetter(Var::scope),
                Comparison.CODEC.optionalFieldOf("op", Comparison.EQ).forGetter(Var::op),
                StoryValues.LOOSE_CODEC.fieldOf("value").forGetter(Var::value)
        ).apply(instance, Var::new));

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.VAR.get();
        }

        @Override
        public boolean test(StoryContext context) {
            StoryScope resolved = scope.orElseGet(() -> StoryState.declaredScope(key));
            // An unset, undeclared variable reads as 0, false or "" to match the compared value.
            StoryValue actual = StoryState.get(context.player(), resolved, key).orElse(value.type().zero());
            return op.test(actual, value);
        }

        @Override
        public void collectReferences(References references) {
            references.flag(key);
        }
    }

    public enum Comparison {
        EQ("="),
        NE("!="),
        GT(">"),
        GE(">="),
        LT("<"),
        LE("<=");

        public static final Codec<Comparison> CODEC = Codec.STRING.comapFlatMap(Comparison::parse, comparison -> comparison.symbol);

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        /** Numbers support every operator; text and booleans only {@code =} and {@code !=}. */
        public boolean test(StoryValue actual, StoryValue expected) {
            if (actual instanceof StoryValue.Int(int left) && expected instanceof StoryValue.Int(int right)) {
                return switch (this) {
                    case EQ -> left == right;
                    case NE -> left != right;
                    case GT -> left > right;
                    case GE -> left >= right;
                    case LT -> left < right;
                    case LE -> left <= right;
                };
            }
            return switch (this) {
                case EQ -> actual.equals(expected);
                case NE -> !actual.equals(expected);
                default -> false;
            };
        }

        private static DataResult<Comparison> parse(String symbol) {
            if (symbol.equals("==")) {
                return DataResult.success(EQ);
            }
            return Arrays.stream(values())
                    .filter(comparison -> comparison.symbol.equals(symbol))
                    .findFirst()
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown comparison '" + symbol + "', expected = != > >= < <="));
        }
    }
}
