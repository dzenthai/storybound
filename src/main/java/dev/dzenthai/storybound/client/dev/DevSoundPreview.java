package dev.dzenthai.storybound.client.dev;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Single-instance audio preview for the dev mode: at most one music track, voice-over or sound plays at a time, once
 * without looping; pressing a preview button while its sound plays stops it, and starting another stops the previous
 * one first.
 */
public final class DevSoundPreview {
    private static final Component PLAY_LABEL = Component.literal("▶");
    private static final Component STOP_LABEL = Component.literal("⏹").withColor(DevUi.ACCENT);

    private static @Nullable SoundInstance current;
    private static @Nullable ResourceLocation currentId;

    private DevSoundPreview() {}

    /** Whether any preview sound or music track is currently playing. */
    public static boolean isActive() {
        if (current == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSoundManager() == null || !minecraft.getSoundManager().isActive(current)) {
            current = null;
            currentId = null;
            return false;
        }
        return true;
    }

    /** Whether the given sound id is the one currently playing. */
    static boolean isPlaying(@Nullable String rawId) {
        if (!isActive() || rawId == null || rawId.isBlank() || currentId == null) {
            return false;
        }
        ResourceLocation parsed = parseId(rawId);
        return parsed != null && currentId.equals(parsed);
    }

    /** Stops any active preview playback immediately. */
    public static void stop() {
        if (current != null) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getSoundManager() != null) {
                minecraft.getSoundManager().stop(current);
            }
            current = null;
            currentId = null;
        }
    }

    /** Plays the sound once (stopping any previous preview), or stops it if this exact sound is already playing. */
    static void toggle(@Nullable String rawId) {
        if (isPlaying(rawId)) {
            stop();
            return;
        }
        stop();
        ResourceLocation id = parseId(rawId);
        if (id == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSoundManager() == null || minecraft.getSoundManager().getSoundEvent(id) == null) {
            return;
        }
        minecraft.getMusicManager().stopPlaying();
        SoundInstance instance = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), 1.0f, 1.0f);
        current = instance;
        currentId = id;
        minecraft.getSoundManager().play(instance);
    }

    /** A button that plays the supplied sound id once, turns into Stop while it plays, and stops it on a second click. */
    static Button button(Supplier<String> sound, int x, int y, int width, int height) {
        return new PreviewButton(sound, x, y, width, height);
    }

    private static @Nullable ResourceLocation parseId(@Nullable String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(rawId.trim());
        return id != null && !id.getPath().isEmpty() ? id : null;
    }

    private static final class PreviewButton extends Button {
        private final Supplier<String> sound;
        private final Tooltip playTip;
        private final Tooltip stopTip;
        private boolean showingStop;

        PreviewButton(Supplier<String> sound, int x, int y, int width, int height) {
            super(x, y, width, height, PLAY_LABEL, b -> toggle(sound.get()), DEFAULT_NARRATION);
            this.sound = sound;
            this.playTip = Tooltip.create(DevUi.text("sound.play"));
            this.stopTip = Tooltip.create(DevUi.text("sound.stop"));
            setTooltip(playTip);
            sync();
        }

        private void sync() {
            String raw = sound.get();
            boolean playing = isPlaying(raw);
            this.active = playing || parseId(raw) != null;
            if (playing != showingStop) {
                showingStop = playing;
                setMessage(playing ? STOP_LABEL : PLAY_LABEL);
                setTooltip(playing ? stopTip : playTip);
            }
        }

        @Override
        public Component getMessage() {
            sync();
            return super.getMessage();
        }

        @Override
        public @Nullable Tooltip getTooltip() {
            sync();
            return super.getTooltip();
        }
    }
}

