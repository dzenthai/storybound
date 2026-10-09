package dev.dzenthai.storybound.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.EventTargetType;
import dev.latvian.mods.kubejs.event.TargetedEventHandler;
import net.minecraft.resources.ResourceLocation;

/** The {@code StoryboundEvents} group available to server scripts. */
public interface StoryboundJSEvents {
    EventGroup GROUP = EventGroup.of("StoryboundEvents");

    /** {@code StoryboundEvents.flagChanged('mypack:met_elder', e => ...)}; the id is optional. */
    TargetedEventHandler<ResourceLocation> FLAG_CHANGED = GROUP.server("flagChanged", () -> FlagChangedKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    /** {@code StoryboundEvents.customTrigger('mypack:bell_rung', e => ...)}. */
    TargetedEventHandler<ResourceLocation> CUSTOM_TRIGGER = GROUP.server("customTrigger", () -> CustomTriggerKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    EventHandler PARTY_JOINED = GROUP.server("partyJoined", () -> PartyKubeEvent.class);
    EventHandler PARTY_LEFT = GROUP.server("partyLeft", () -> PartyKubeEvent.class);

    /** Posted after server scripts load: {@code e.add('mypack:is_night', ctx => ctx.level.isNight())}. */
    EventHandler REGISTER_CONDITIONS = GROUP.server("registerConditions", () -> RegisterConditionsKubeEvent.class);

    /** Posted after server scripts load: {@code e.add('mypack:pay_bribe', ctx => ...)}. */
    EventHandler REGISTER_ACTIONS = GROUP.server("registerActions", () -> RegisterActionsKubeEvent.class);
}
