package dev.dzenthai.storybound.client.scene;

import dev.dzenthai.storybound.client.camera.SceneCamera;
import dev.dzenthai.storybound.network.ScenePayloads;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The scene the player watches, on the client: its shots and its clock (client ticks since it started, so the camera
 * flies smoothly between them), the line in subtitles. While it plays the scene's screen is open: it takes the keys and
 * the mouse (the pause menu still opens; the scene goes on after it), draws the black bars and the line. The server
 * says when it is over; one that is never ended (a lost packet) ends by itself a few seconds after its last shot.
 */
public final class ClientScene {
    /** Ticks past the last shot before a scene the server never ended is let go. */
    private static final int OVERRUN = 100;

    private static int session = -1;
    private static boolean letterbox;
    private static List<ScenePayloads.Shot> shots = List.of();
    private static int total;
    private static int tick;
    private static @Nullable Component speaker;
    private static @Nullable Component line;
    private static int lineUntil;
    /** A talk of the scene goes on: the scene's camera, screen and clock wait, the dialogue has the screen. */
    private static boolean paused;
    private static @Nullable Component caption;
    private static int captionPosition;
    private static boolean captionBlack;
    private static int captionFrom;
    private static int captionUntil;
    /** The editor to open again once the scene is over, when the author watched it from there. */
    private static net.minecraft.client.gui.screens.@Nullable Screen returnTo;

    private ClientScene() {}

    public static boolean playing() {
        return session >= 0;
    }

    /** Whether the scene has the camera now: it plays and no talk of it goes on. */
    public static boolean framing() {
        return session >= 0 && !paused;
    }

    /** A talk of the scene begins ({@code on}) or is over: the dialogue takes the screen, then gives it back. */
    public static void pause(int id, boolean on) {
        if (id != session || paused == on) {
            return;
        }
        paused = on;
        Minecraft minecraft = Minecraft.getInstance();
        if (on) {
            SceneCamera.end();
            if (minecraft.screen instanceof SceneScreen) {
                minecraft.setScreen(null);
            }
        } else {
            SceneCamera.begin();
            open();
        }
    }

    public static void caption(int id, Component text, int position, boolean black, int ticks) {
        if (id != session) {
            return;
        }
        caption = text;
        captionPosition = position;
        captionBlack = black;
        captionFrom = tick;
        captionUntil = tick + ticks;
    }

    public static void play(int id, boolean bars, List<ScenePayloads.Shot> planned) {
        if (planned.isEmpty()) {
            return;
        }
        dev.dzenthai.storybound.client.dev.DevSoundPreview.stop();
        boolean was = playing();
        session = id;
        letterbox = bars;
        shots = List.copyOf(planned);
        ScenePayloads.Shot last = shots.getLast();
        total = last.start() + last.ticks();
        tick = 0;
        line = null;
        speaker = null;
        caption = null;
        paused = false;
        if (!was) {
            SceneCamera.begin();
        }
        open();
    }

    public static void line(int id, @Nullable Component who, Component text, int ticks) {
        if (id != session) {
            return;
        }
        speaker = who;
        line = text;
        lineUntil = tick + ticks;
    }

    public static void end(int id) {
        if (id == session) {
            stop();
        }
    }

    /** Space on the scene's screen: asks the server to skip the rest of the scene. */
    public static void skip() {
        if (framing()) {
            dev.dzenthai.storybound.client.ScreenFadeOverlay.clear();
            PacketDistributor.sendToServer(new ScenePayloads.Skip(session));
        }
    }

    /** Every client tick: the clock, the line's time, the screen kept open. */
    public static void tick() {
        if (!playing()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            stop();
            return;
        }
        if (paused) {
            // A talk of the scene: its clock waits, the dialogue has the screen.
            return;
        }
        if (!minecraft.isPaused()) {
            tick++;
        }
        if (line != null && tick >= lineUntil) {
            line = null;
            speaker = null;
        }
        if (caption != null && tick >= captionUntil) {
            caption = null;
        }
        if (tick > total + OVERRUN) {
            stop();
            return;
        }
        if (minecraft.screen == null) {
            open();
        }
    }

    private static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof SceneScreen) && !(minecraft.screen instanceof PauseScreen)) {
            minecraft.setScreen(new SceneScreen());
        }
    }

    private static void stop() {
        session = -1;
        shots = List.of();
        line = null;
        speaker = null;
        caption = null;
        paused = false;
        SceneCamera.end();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof SceneScreen) {
            minecraft.setScreen(returnTo);
        }
        returnTo = null;
    }

    /** The author watches a scene from the editor: it comes back when the scene is over. */
    public static void returnTo(net.minecraft.client.gui.screens.Screen editor) {
        returnTo = editor;
    }

    /** Leaving a world: nothing of its scene stays. */
    public static void reset() {
        returnTo = null;
        if (playing()) {
            stop();
        }
    }

    public static List<ScenePayloads.Shot> shots() {
        return shots;
    }

    /** The scene's time now, in ticks with the frame's part of one. */
    public static double time(float partialTick) {
        return Math.min(total, tick + (Minecraft.getInstance().isPaused() ? 0 : partialTick));
    }

    static boolean letterbox() {
        return letterbox;
    }

    static @Nullable Component speaker() {
        return speaker;
    }

    static @Nullable Component line() {
        return line;
    }

    static @Nullable Component caption() {
        return caption;
    }

    static int captionPosition() {
        return captionPosition;
    }

    static boolean captionBlack() {
        return captionBlack;
    }

    /** How much of the caption shows (0 to 1): it fades in over its first ticks and out over its last. */
    static float captionAlpha(float partialTick) {
        double now = tick + partialTick;
        double fade = Math.min(15, (captionUntil - captionFrom) / 3.0);
        double in = (now - captionFrom) / fade;
        double out = (captionUntil - now) / fade;
        return (float) Math.max(0, Math.min(1, Math.min(in, out)));
    }
}
