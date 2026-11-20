package dev.dzenthai.storybound.client.sound;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The story's music on the client: one track at a time, looping, on the Music volume; a new one fades in as the old
 * fades out ({@link #FADE_TICKS}), and vanilla's own music keeps quiet while a story track plays or fades.
 * <ul>
 * <li>A track the game stopped by itself (another dimension, sounds or resource packs reloaded) starts again, fading
 * in; one that does not start (no such sound) is tried once a second, the game warning of it once.</li>
 * <li>A screen with music of its own (the credits, the title screen) has it: the track fades out and comes back after.</li>
 * <li>Once a track is over, vanilla's music waits its own pause before its next song, as after one of its own; music
 * that replaces at once (the dragon's) does not wait.</li>
 * <li>Back to a track still fading out, it fades in again from where it is rather than from its start.</li>
 * <li>While the player is in a dialogue the story's music is down to {@link #DUCKED} (its own track too), so the
 * words are heard over it; it goes down and back up over {@link #DUCK_TICKS}.</li>
 * </ul>
 */
public final class ClientStoryMusic {
    static final int FADE_TICKS = 50;
    /** The music's volume during a dialogue, of its own. */
    static final float DUCKED = 0.4f;
    static final int DUCK_TICKS = 10;
    /** How long a track that is not playing is given before it is started again. */
    private static final int RETRY_TICKS = 20;

    private static @Nullable Track current;
    private static final List<Track> fading = new ArrayList<>();
    /** The track the server names while a screen's own music has the floor; played once the screen is gone. */
    private static @Nullable ResourceLocation waiting;
    private static int ticks;
    /** Ticks the current track has not been heard; it is started again at {@link #RETRY_TICKS}. */
    private static int silent;
    /** After a track ends: vanilla's pause is still to be drawn ({@code -1}), or the tick it ends at; 0 for none. */
    private static long restUntil;
    /** The volume the dialogue leaves the music: 1 out of one, {@link #DUCKED} in one, sliding between. */
    private static float duck = 1.0f;

    private ClientStoryMusic() {}

    /** The track the server names now; empty: none, vanilla's music comes back after its pause. */
    public static void play(Optional<ResourceLocation> track) {
        if (screenMusic()) {
            if (current != null) {
                current.fadeOut();
                fading.add(current);
                current = null;
            }
            waiting = track.orElse(null);
            return;
        }
        if (current != null && track.isPresent() && current.getLocation().equals(track.get())) {
            return;
        }
        if (current != null) {
            current.fadeOut();
            fading.add(current);
            current = null;
            if (track.isEmpty()) {
                restUntil = -1;
            }
        }
        if (track.isPresent()) {
            restUntil = 0;
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.getMusicManager().stopPlaying();
            Track back = fading.stream().filter(fade -> fade.getLocation().equals(track.get()) && minecraft.getSoundManager().isActive(fade))
                    .findFirst().orElse(null);
            if (back != null) {
                fading.remove(back);
                back.fadeIn();
                current = back;
            } else {
                start(track.get());
            }
        }
    }

    private static void start(ResourceLocation track) {
        current = new Track(SoundEvent.createVariableRangeEvent(track));
        silent = 0;
        Minecraft.getInstance().getSoundManager().play(current);
    }

    public static void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        if (current != null) {
            minecraft.getSoundManager().stop(current);
        }
        fading.forEach(minecraft.getSoundManager()::stop);
        fading.clear();
        current = null;
        waiting = null;
        restUntil = 0;
        duck = 1.0f;
    }

    /** Every tick, as vanilla picks its music: keeps the track playing, and vanilla quiet while it should be. */
    public static void onSelectMusic(SelectMusicEvent event) {
        ticks++;
        float target = dev.dzenthai.storybound.client.dialogue.ClientDialogue.isActive() ? DUCKED : 1.0f;
        float step = (1.0f - DUCKED) / DUCK_TICKS;
        duck = duck < target ? Math.min(target, duck + step) : Math.max(target, duck - step);
        Minecraft minecraft = Minecraft.getInstance();
        fading.removeIf(fade -> fade.isStopped() || !minecraft.getSoundManager().isActive(fade));
        if (screenMusic()) {
            if (current != null) {
                waiting = current.getLocation();
                current.fadeOut();
                fading.add(current);
                current = null;
            }
            // The screen's music plays, ours fading under it.
            return;
        }
        if (waiting != null) {
            ResourceLocation back = waiting;
            waiting = null;
            play(Optional.of(back));
        }
        if (current != null) {
            if (minecraft.getSoundManager().isActive(current)) {
                silent = 0;
            } else if (++silent >= RETRY_TICKS) {
                start(current.getLocation());
            }
        }
        if (current != null || !fading.isEmpty()) {
            event.setMusic(null);
            return;
        }
        Music vanilla = event.getOriginalMusic();
        if (vanilla == null) {
            return;
        }
        // Music that replaces at once and never waits (the dragon's, the credits'): no pause before it.
        boolean urgent = vanilla.replaceCurrentMusic() && vanilla.getMaxDelay() == 0;
        if (restUntil == -1) {
            restUntil = urgent ? 0 : ticks + Mth.nextInt(RandomSource.create(), vanilla.getMinDelay(), Math.max(vanilla.getMinDelay(), vanilla.getMaxDelay()));
        }
        if (restUntil > 0) {
            if (ticks < restUntil && !urgent) {
                event.setMusic(null);
            } else {
                restUntil = 0;
            }
        }
    }

    /** A screen showing music of its own: the credits, the title screen. */
    private static boolean screenMusic() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.screen != null && minecraft.screen.getBackgroundMusic() != null;
    }

    /** A track that fades in at start and out when told. */
    private static final class Track extends AbstractTickableSoundInstance {
        private int age;
        private int fadeOutAge = -1;
        /** How far it has faded in or out, before the dialogue's ducking. */
        private float level;

        Track(SoundEvent event) {
            super(event, SoundSource.MUSIC, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.0f;
        }

        void fadeOut() {
            fadeOutAge = age;
        }

        /** Back from fading out: up again from the volume it has reached. */
        void fadeIn() {
            age = Math.round(level * FADE_TICKS);
            fadeOutAge = -1;
        }

        @Override
        public void tick() {
            age++;
            float in = Math.min(1.0f, age / (float) FADE_TICKS);
            if (fadeOutAge >= 0) {
                float out = 1.0f - (age - fadeOutAge) / (float) FADE_TICKS;
                level = Math.max(0.0f, Math.min(in, out));
                if (out <= 0.0f) {
                    stop();
                }
            } else {
                level = in;
            }
            volume = level * duck;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
