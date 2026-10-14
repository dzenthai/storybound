package dev.dzenthai.storybound.core.logic;

import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.trigger.TriggerEvents;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

public final class LogicEvents {
    private LogicEvents() {}

    public static void register(IEventBus gameBus) {
        // HIGH: subscriptions must be in place before anything reacts to the finished reload.
        gameBus.addListener(EventPriority.HIGH, LogicEvents::onTagsUpdated);
        TriggerEvents.register(gameBus);
        LogicOwners.register(CoreDefinitions.EVENTS, StoryEvents::collectReferences);
        ValidationService.registerValidator(StoryEvents::validate);
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            StoryEvents.rebuild();
        }
    }
}
