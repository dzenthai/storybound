package dev.dzenthai.storybound.client.waypoint;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.compat.xaero.XaeroCompat;
import net.neoforged.fml.ModList;

/**
 * Hooks up map mods that are installed. Xaero's Minimap is registered here; JourneyMap finds our plugin
 * ({@code compat.journeymap}) itself through its annotation. Compat classes are touched only when their mod is loaded.
 */
public final class MapIntegrations {
    private MapIntegrations() {}

    public static void register() {
        if (ModList.get().isLoaded("xaerominimap")) {
            try {
                XaeroCompat.register();
            } catch (RuntimeException | LinkageError e) {
                Storybound.LOGGER.error("Storybound: Xaero's Minimap integration failed to start and is disabled", e);
            }
        }
    }
}
