package dev.dzenthai.storybound.core.logic.action;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BuiltinActions {
    public static final DeferredRegister<ActionType<?>> REGISTER = DeferredRegister.create(LogicRegistries.ACTION_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ActionType<?>, ActionType<StateActions.SetFlag>> SET_FLAG =
            REGISTER.register("set_flag", () -> new ActionType<>(StateActions.SetFlag.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<StateActions.ClearFlag>> CLEAR_FLAG =
            REGISTER.register("clear_flag", () -> new ActionType<>(StateActions.ClearFlag.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<StateActions.SetVar>> SET_VAR =
            REGISTER.register("set_var", () -> new ActionType<>(StateActions.SetVar.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<StateActions.AddVar>> ADD_VAR =
            REGISTER.register("add_var", () -> new ActionType<>(StateActions.AddVar.CODEC));

    public static final DeferredHolder<ActionType<?>, ActionType<WorldActions.GiveItem>> GIVE_ITEM =
            REGISTER.register("give_item", () -> new ActionType<>(WorldActions.GiveItem.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<WorldActions.TakeItem>> TAKE_ITEM =
            REGISTER.register("take_item", () -> new ActionType<>(WorldActions.TakeItem.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<WorldActions.PlaySound>> PLAY_SOUND =
            REGISTER.register("play_sound", () -> new ActionType<>(WorldActions.PlaySound.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<WorldActions.RunCommand>> RUN_COMMAND =
            REGISTER.register("run_command", () -> new ActionType<>(WorldActions.RunCommand.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<WorldActions.Script>> KUBEJS =
            REGISTER.register("kubejs", () -> new ActionType<>(WorldActions.Script.CODEC));

    private BuiltinActions() {}
}
