package dev.dzenthai.storybound.core.state;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

public final class StateEvents {
    private StateEvents() {}

    public static void register(IEventBus gameBus) {
        // LOW: the client must receive flag declarations before their values.
        gameBus.addListener(EventPriority.LOW, StateEvents::onDatapackSync);
        StoryMigrations.register(gameBus);
    }

    // On login only to that player, after /reload to everyone: the set of sync flags may have changed.
    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(StateSync::sendFull);
    }
}
