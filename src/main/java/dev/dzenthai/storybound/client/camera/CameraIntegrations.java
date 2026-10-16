package dev.dzenthai.storybound.client.camera;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.compat.epicfight.EpicFightCompat;
import dev.dzenthai.storybound.compat.shouldersurfing.ShoulderSurfingCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.neoforged.fml.ModList;

/**
 * Other camera mods, paused while the dialogue camera frames a shot and restored afterwards. Compat classes are
 * touched only when their mod is loaded; one that throws is dropped for the rest of the game session.
 */
public final class CameraIntegrations {
    private static final List<Integration> INTEGRATIONS = new ArrayList<>();

    private CameraIntegrations() {}

    /** Something another mod does with the camera or the player's view that must stop during a dialogue shot. */
    public interface Integration {
        String name();

        void pause();

        void resume();
    }

    public static void register() {
        add("shouldersurfing", () -> new ShoulderSurfingCompat());
        add("epicfight", () -> new EpicFightCompat());
    }

    private static void add(String modId, Supplier<Integration> factory) {
        if (!ModList.get().isLoaded(modId)) {
            return;
        }
        try {
            INTEGRATIONS.add(factory.get());
        } catch (RuntimeException | LinkageError e) {
            Storybound.LOGGER.error("Storybound: the {} camera integration failed to start and is disabled", modId, e);
        }
    }

    /** A mod from the client's {@code camera.disableWith} list is installed: the dialogue camera stays off. */
    public static boolean blocked() {
        ModList mods = ModList.get();
        for (String modId : StoryboundClientConfig.INSTANCE.cameraDisableWith.get()) {
            if (mods.isLoaded(modId)) {
                return true;
            }
        }
        return false;
    }

    static void pause() {
        run(true);
    }

    static void resume() {
        run(false);
    }

    private static void run(boolean pause) {
        INTEGRATIONS.removeIf(integration -> {
            try {
                if (pause) {
                    integration.pause();
                } else {
                    integration.resume();
                }
                return false;
            } catch (RuntimeException | LinkageError e) {
                Storybound.LOGGER.error("Storybound: the {} camera integration failed and is disabled", integration.name(), e);
                return true;
            }
        });
    }
}
