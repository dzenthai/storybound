package dev.dzenthai.storybound.core.logic.condition;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.ConditionType;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BuiltinConditions {
    public static final DeferredRegister<ConditionType<?>> REGISTER = DeferredRegister.create(LogicRegistries.CONDITION_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ConditionType<?>, ConditionType<LogicConditions.All>> ALL =
            REGISTER.register("all", () -> new ConditionType<>(LogicConditions.All.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<LogicConditions.Any>> ANY =
            REGISTER.register("any", () -> new ConditionType<>(LogicConditions.Any.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<LogicConditions.Not>> NOT =
            REGISTER.register("not", () -> new ConditionType<>(LogicConditions.Not.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<LogicConditions.Chance>> CHANCE =
            REGISTER.register("chance", () -> new ConditionType<>(LogicConditions.Chance.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<LogicConditions.Script>> KUBEJS =
            REGISTER.register("kubejs", () -> new ConditionType<>(LogicConditions.Script.CODEC));

    public static final DeferredHolder<ConditionType<?>, ConditionType<StateConditions.Flag>> FLAG =
            REGISTER.register("flag", () -> new ConditionType<>(StateConditions.Flag.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<StateConditions.Var>> VAR =
            REGISTER.register("var", () -> new ConditionType<>(StateConditions.Var.CODEC));

    public static final DeferredHolder<ConditionType<?>, ConditionType<WorldConditions.HasItem>> HAS_ITEM =
            REGISTER.register("has_item", () -> new ConditionType<>(WorldConditions.HasItem.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<WorldConditions.Location>> LOCATION =
            REGISTER.register("location", () -> new ConditionType<>(WorldConditions.Location.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<WorldConditions.InParty>> IN_PARTY =
            REGISTER.register("in_party", () -> new ConditionType<>(WorldConditions.InParty.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<WorldConditions.Time>> TIME =
            REGISTER.register("time", () -> new ConditionType<>(WorldConditions.Time.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<WorldConditions.Weather>> WEATHER =
            REGISTER.register("weather", () -> new ConditionType<>(WorldConditions.Weather.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<ProgressConditions.Advancement>> ADVANCEMENT =
            REGISTER.register("advancement", () -> new ConditionType<>(ProgressConditions.Advancement.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<ProgressConditions.Level>> LEVEL =
            REGISTER.register("level", () -> new ConditionType<>(ProgressConditions.Level.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<ProgressConditions.Stat>> STAT =
            REGISTER.register("stat", () -> new ConditionType<>(ProgressConditions.Stat.CODEC));

    private BuiltinConditions() {}
}
