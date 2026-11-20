package dev.dzenthai.storybound.client.sound;

import dev.dzenthai.storybound.Storybound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

/**
 * The interface's own sounds: {@code storybound:<name>} of the mod's {@code sounds.json} (vanilla sounds by default).
 * A story replaces one by putting {@code <name>.ogg} right in its {@code sounds/} folder ({@link StorySoundFiles}), a
 * resource pack by its own {@code sounds.json}.
 */
public final class UiSounds {
    private UiSounds() {}

    public static void play(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(Storybound.id(name)), 1.0f, 1.0f));
        }
    }
}
