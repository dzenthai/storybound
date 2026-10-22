package dev.dzenthai.storybound.core.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import java.util.List;
import java.util.function.Function;

/** Codecs for the logic language. Lazy, because the type registries are filled after class loading. */
public final class LogicCodecs {
    private static final Codec<Condition> SINGLE_CONDITION = Codec.lazyInitialized(() ->
            LogicRegistries.typeCodec(LogicRegistries.CONDITION_TYPES, "condition")
                    .<Condition>dispatch("type", Condition::type, ConditionType::codec));

    private static final Codec<Action> SINGLE_ACTION = Codec.lazyInitialized(() ->
            LogicRegistries.typeCodec(LogicRegistries.ACTION_TYPES, "action")
                    .<Action>dispatch("type", Action::type, ActionType::codec));

    /** One action object. */
    public static final Codec<Action> ACTION = SINGLE_ACTION;

    /** A condition object, or an array of conditions that must all pass. */
    public static final Codec<Condition> CONDITION = Codec.either(SINGLE_CONDITION, SINGLE_CONDITION.listOf())
            .xmap(either -> either.map(Function.identity(), LogicConditions.All::new), condition -> Either.left(condition));

    /** An action object or an array of actions, run in order. */
    public static final Codec<List<Action>> ACTIONS = Codec.either(SINGLE_ACTION, SINGLE_ACTION.listOf())
            .xmap(either -> either.map(List::of, Function.identity()),
                    actions -> actions.size() == 1 ? Either.left(actions.getFirst()) : Either.right(actions));

    public static final Codec<Trigger<?>> TRIGGER = Codec.lazyInitialized(() ->
            LogicRegistries.typeCodec(LogicRegistries.TRIGGER_TYPES, "trigger")
                    .<Trigger<?>>dispatch("type", Trigger::type, TriggerType::codec));

    /**
     * Any JSON object, kept as written: the {@code args} of script conditions and actions. Read through any ops
     * (JSON from files, NBT when definitions are synced), so it survives every way a definition travels.
     */
    public static final Codec<JsonObject> JSON_OBJECT = Codec.PASSTHROUGH.comapFlatMap(dynamic -> {
        JsonElement json = dynamic.convert(JsonOps.INSTANCE).getValue();
        return json.isJsonObject() ? DataResult.success(json.getAsJsonObject()) : DataResult.error(() -> "Expected an object, got " + json);
    }, json -> new Dynamic<>(JsonOps.INSTANCE, json));

    private LogicCodecs() {}
}
