package dev.dzenthai.storybound.core.logic.action;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.StoryValues;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Writes to Story State. An omitted scope means the flag's declared scope. */
public final class StateActions {
    private StateActions() {}

    private static StoryScope resolve(Optional<StoryScope> scope, ResourceLocation key) {
        return scope.orElseGet(() -> StoryState.declaredScope(key));
    }

    /** {@code { "type": "set_flag", "key": "mypack:met_elder" }}; {@code value} defaults to true. */
    public record SetFlag(ResourceLocation key, Optional<StoryScope> scope, boolean value) implements Action {
        public static final MapCodec<SetFlag> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("key").forGetter(SetFlag::key),
                StoryScope.CODEC.optionalFieldOf("scope").forGetter(SetFlag::scope),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(SetFlag::value)
        ).apply(instance, SetFlag::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.SET_FLAG.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            StoryState.set(context.player(), resolve(scope, key), key, new StoryValue.Bool(value));
        }

        @Override
        public void collectReferences(References references) {
            references.flag(key);
        }
    }

    public record ClearFlag(ResourceLocation key, Optional<StoryScope> scope) implements Action {
        public static final MapCodec<ClearFlag> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("key").forGetter(ClearFlag::key),
                StoryScope.CODEC.optionalFieldOf("scope").forGetter(ClearFlag::scope)
        ).apply(instance, ClearFlag::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.CLEAR_FLAG.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            StoryState.clear(context.player(), resolve(scope, key), key);
        }

        @Override
        public void collectReferences(References references) {
            references.flag(key);
        }
    }

    /** {@code { "type": "set_var", "key": "mypack:road", "value": "north" }}. */
    public record SetVar(ResourceLocation key, Optional<StoryScope> scope, StoryValue value) implements Action {
        public static final MapCodec<SetVar> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("key").forGetter(SetVar::key),
                StoryScope.CODEC.optionalFieldOf("scope").forGetter(SetVar::scope),
                StoryValues.LOOSE_CODEC.fieldOf("value").forGetter(SetVar::value)
        ).apply(instance, SetVar::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.SET_VAR.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            StoryState.set(context.player(), resolve(scope, key), key, value);
        }

        @Override
        public void collectReferences(References references) {
            references.flag(key);
        }
    }

    /** {@code { "type": "add_var", "key": "mypack:reputation", "amount": -5 }}. */
    public record AddVar(ResourceLocation key, Optional<StoryScope> scope, int amount) implements Action {
        public static final MapCodec<AddVar> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("key").forGetter(AddVar::key),
                StoryScope.CODEC.optionalFieldOf("scope").forGetter(AddVar::scope),
                Codec.INT.fieldOf("amount").forGetter(AddVar::amount)
        ).apply(instance, AddVar::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.ADD_VAR.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            StoryState.add(context.player(), resolve(scope, key), key, amount);
        }

        @Override
        public void collectReferences(References references) {
            references.flag(key);
        }
    }
}
