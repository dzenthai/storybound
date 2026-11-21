package dev.dzenthai.storybound.client;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The screen fade on the client ({@code ScreenFade}): rises from the alpha it has now to full over {@code in}, holds
 * for {@code stay}, clears over {@code out}, in real time so it stays smooth. A GUI layer over the world and the HUD,
 * under the titles and the dialogue subtitles. Cleared at once on death and on leaving the world.
 */
public final class ScreenFadeOverlay {
    private static float from;
    private static long start = -1;
    private static long inMs;
    private static long stayMs;
    private static long outMs;
    private static int color;

    private ScreenFadeOverlay() {}

    public static void start(float in, float stay, float out, int rgb) {
        from = alpha(Util.getMillis());
        start = Util.getMillis();
        inMs = Math.round(in * 1000);
        stayMs = Math.round(stay * 1000);
        outMs = Math.round(out * 1000);
        color = rgb & 0x00FFFFFF;
    }

    public static void clear() {
        start = -1;
        from = 0;
    }

    /** How opaque the fade is at that moment, 0 to 1. */
    static float alpha(long now) {
        if (start < 0) {
            return 0;
        }
        long t = now - start;
        if (t < inMs) {
            return from + (1 - from) * t / (float) inMs;
        }
        t -= inMs;
        if (t < stayMs) {
            return 1;
        }
        t -= stayMs;
        if (t < outMs) {
            return 1 - t / (float) outMs;
        }
        start = -1;
        return 0;
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.isDeadOrDying()) {
            clear();
        }
        float alpha = alpha(Util.getMillis());
        if (alpha <= 0) {
            return;
        }
        int a = Math.min(255, Math.round(alpha * 255));
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), (a << 24) | color);
    }
}
