package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.action.BuiltinActions;
import dev.dzenthai.storybound.core.logic.condition.BuiltinConditions;
import dev.dzenthai.storybound.core.logic.trigger.BuiltinTriggers;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

/** Registries of condition, action and trigger types. Other mods add their types through these keys. */
public final class LogicRegistries {
    public static final ResourceKey<Registry<ConditionType<?>>> CONDITION_TYPE_KEY = ResourceKey.createRegistryKey(Storybound.id("condition_type"));
    public static final ResourceKey<Registry<ActionType<?>>> ACTION_TYPE_KEY = ResourceKey.createRegistryKey(Storybound.id("action_type"));
    public static final ResourceKey<Registry<TriggerType<?, ?>>> TRIGGER_TYPE_KEY = ResourceKey.createRegistryKey(Storybound.id("trigger_type"));

    public static final Registry<ConditionType<?>> CONDITION_TYPES = new RegistryBuilder<>(CONDITION_TYPE_KEY).create();
    public static final Registry<ActionType<?>> ACTION_TYPES = new RegistryBuilder<>(ACTION_TYPE_KEY).create();
    public static final Registry<TriggerType<?, ?>> TRIGGER_TYPES = new RegistryBuilder<>(TRIGGER_TYPE_KEY).create();

    private LogicRegistries() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(NewRegistryEvent.class, event -> {
            event.register(CONDITION_TYPES);
            event.register(ACTION_TYPES);
            event.register(TRIGGER_TYPES);
        });
        BuiltinConditions.REGISTER.register(modBus);
        BuiltinActions.REGISTER.register(modBus);
        BuiltinTriggers.REGISTER.register(modBus);
    }

    /** Type ids default to our namespace, so modpack JSON may write {@code "flag"} for {@code "storybound:flag"}. */
    static <T> Codec<T> typeCodec(Registry<T> registry, String kind) {
        return Codec.STRING.flatXmap(name -> {
            ResourceLocation id = ResourceLocation.tryParse(name.indexOf(':') >= 0 ? name : Storybound.MOD_ID + ":" + name);
            if (id == null) {
                return DataResult.error(() -> "Invalid " + kind + " type id: " + name);
            }
            T type = registry.get(id);
            return type != null ? DataResult.success(type) : DataResult.error(() -> "Unknown " + kind + " type: " + id);
        }, type -> {
            ResourceLocation id = registry.getKey(type);
            return id != null ? DataResult.success(id.toString()) : DataResult.error(() -> "Unregistered " + kind + " type: " + type);
        });
    }
}
