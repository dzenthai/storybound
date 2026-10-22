package dev.dzenthai.storybound.compat.cutscene;

import net.thewinnt.cutscenes.client.ClientCutsceneManager;

/** Client side: whether the player watches a cutscene now, so Storybound's HUD and markers step aside. */
public final class CutsceneClient {
    private CutsceneClient() {}

    public static boolean playing() {
        return Cutscenes.installed() && Running.now();
    }

    /** Kept apart: loaded (with Cutscene API's client class) only when the mod is installed. */
    private static final class Running {
        static boolean now() {
            return ClientCutsceneManager.runningCutscene != null;
        }
    }
}
