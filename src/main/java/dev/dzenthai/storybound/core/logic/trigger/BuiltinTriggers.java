package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.event.PartyEvent;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.TriggerType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BuiltinTriggers {
    public static final DeferredRegister<TriggerType<?, ?>> REGISTER = DeferredRegister.create(LogicRegistries.TRIGGER_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.FlagChanged, StoryValueChangedEvent>> FLAG_CHANGED =
            REGISTER.register("flag_changed", () -> new TriggerType<>(CoreTriggers.FlagChanged.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.EntityKilled, CoreTriggers.Kill>> ENTITY_KILLED =
            REGISTER.register("entity_killed", () -> new TriggerType<>(CoreTriggers.EntityKilled.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.EntitySeen, Entity>> ENTITY_SEEN =
            REGISTER.register("entity_seen", () -> new TriggerType<>(CoreTriggers.EntitySeen.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.AreaEntered, Unit>> AREA_ENTERED =
            REGISTER.register("area_entered", () -> new TriggerType<>(CoreTriggers.AreaEntered.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.StructureEntered, Unit>> STRUCTURE_ENTERED =
            REGISTER.register("structure_entered", () -> new TriggerType<>(CoreTriggers.StructureEntered.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.ItemObtained, ItemStack>> ITEM_OBTAINED =
            REGISTER.register("item_obtained", () -> new TriggerType<>(CoreTriggers.ItemObtained.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.EntityInteracted, Entity>> ENTITY_INTERACTED =
            REGISTER.register("entity_interacted", () -> new TriggerType<>(CoreTriggers.EntityInteracted.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.PartyJoined, PartyEvent.Joined>> PARTY_JOINED =
            REGISTER.register("party_joined", () -> new TriggerType<>(CoreTriggers.PartyJoined.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.PartyLeft, PartyEvent.Left>> PARTY_LEFT =
            REGISTER.register("party_left", () -> new TriggerType<>(CoreTriggers.PartyLeft.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CoreTriggers.Custom, ResourceLocation>> CUSTOM =
            REGISTER.register("custom", () -> new TriggerType<>(CoreTriggers.Custom.CODEC));

    private BuiltinTriggers() {}
}
