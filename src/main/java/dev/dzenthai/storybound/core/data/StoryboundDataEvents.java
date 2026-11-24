package dev.dzenthai.storybound.core.data;

import dev.dzenthai.storybound.core.dev.DevModeServer;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import dev.dzenthai.storybound.network.DefinitionSync;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
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
        // HIGHEST: the definitions are read again with the tags bound before any module rebuilds from them.
        gameBus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, StoryboundDataEvents::onTagsBound);
        gameBus.addListener(StoryboundDataEvents::onTagsUpdated);
        // HIGH: before the headless validation (LOWEST), which validates on its own.
        gameBus.addListener(net.neoforged.bus.api.EventPriority.HIGH, StoryboundDataEvents::onServerStarted);
        gameBus.addListener(StoryboundDataEvents::onServerAboutToStart);
        gameBus.addListener(StoryboundDataEvents::onDatapackSync);
        gameBus.addListener(StoryboundDataEvents::onPlayerLoggedIn);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            event.addListener(loader(type, event.getRegistryAccess()));
        }
        // The story folder's translations and long texts, read again on every data load.
        event.addListener((ResourceManagerReloadListener) resourceManager -> ConfigTexts.reload());
    }

    private static <T> DefinitionLoader<T> loader(DefinitionType<T> type, HolderLookup.Provider registries) {
        return new DefinitionLoader<>(type, registries);
    }

    /** A data load came before the worlds were made: validated once they are, as a place's checks need them. */
    private static boolean pending;

    /** The tags are bound now: the definitions are read again, so those naming a tag load too. */
    private static void onTagsBound(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            DefinitionLoader.reparse(event.getRegistryAccess());
        }
    }

    // Fires after all reload listeners have run, both on world start and after /reload.
    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null || !server.getAllLevels().iterator().hasNext()) {
                // On world start: the dimensions are not made yet, and "the Nether has no plains" could not be said.
                pending = true;
                return;
            }
            ValidationService.publish(ValidationService.validate());
        }
    }

    /**
     * The world's story ({@link WorldStory}): a world's first data load comes before its server, when the world's rule
     * cannot be read, and leaves the story out; with the rule on, the definitions and texts load again now, before the
     * worlds are made and anyone joins (only the story's JSON, not a whole {@code /reload}).
     */
    private static void onServerAboutToStart(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        if (!WorldStory.on()) {
            return;
        }
        net.minecraft.server.MinecraftServer server = event.getServer();
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            loader(type, server.registryAccess()).loadNow(server.getResourceManager());
        }
        ConfigTexts.reload();
        DevModeServer.rebuild();
    }

    private static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (pending) {
            pending = false;
            ValidationService.publish(ValidationService.validate());
        }
    }

    // On login only to that player, after /reload to everyone.
    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(player -> {
            DefinitionSync.sendTo(player);
            if (player.hasPermissions(2)) {
                DevModeServer.sendSnapshot(player);
            }
        });
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        ValidationReport report = ValidationService.current();
        if (event.getEntity() instanceof ServerPlayer player && player.hasPermissions(2) && report.hasErrors()) {
            player.sendSystemMessage(Component.translatable("storybound.validation.join_warning", report.errorCount()));
        }
    }
}
