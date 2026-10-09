package dev.dzenthai.storybound.core.data;

import dev.dzenthai.storybound.network.DefinitionSync;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Data lifecycle: loading, cross-reference validation, sending to clients. */
public final class StoryboundDataEvents {
    private StoryboundDataEvents() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(StoryboundDataEvents::onAddReloadListeners);
        gameBus.addListener(StoryboundDataEvents::onTagsUpdated);
        gameBus.addListener(StoryboundDataEvents::onDatapackSync);
        gameBus.addListener(StoryboundDataEvents::onPlayerLoggedIn);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            event.addListener(loader(type, event.getRegistryAccess()));
        }
    }

    private static <T> DefinitionLoader<T> loader(DefinitionType<T> type, HolderLookup.Provider registries) {
        return new DefinitionLoader<>(type, registries);
    }

    // Fires after all reload listeners have run, both on world start and after /reload.
    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            ValidationService.publish(ValidationService.validate());
        }
    }

    // On login only to that player, after /reload to everyone.
    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(DefinitionSync::sendTo);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        ValidationReport report = ValidationService.current();
        if (event.getEntity() instanceof ServerPlayer player && player.hasPermissions(2) && report.hasErrors()) {
            player.sendSystemMessage(Component.translatable("storybound.validation.join_warning", report.errorCount()));
        }
    }
}
