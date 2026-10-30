package dev.dzenthai.storybound.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * The observation bar under the crosshair (October 30, 2026): what is being observed, a bar and its percent. The server
 * says how full it is a few times a second; the bar moves there smoothly. Looking away fades it; it goes when the server
 * hides it, or by itself a second after the server stopped telling (a lost packet, a world left).
 */
public final class ObservationHud {
    private static final int BAR_W = 82;
    private static final int BAR_H = 4;
    /** With nothing heard for this long, the bar goes by itself. */
    private static final long STALE_MS = 1000;

    private static @Nullable Component label;
    private static float target;
    private static float shown;
    private static boolean active;
    private static long heard;

    private ObservationHud() {}

    public static void update(@Nullable Component text, float progress, boolean looking) {
        if (text == null) {
            label = null;
            return;
        }
        if (label == null) {
            // A new bar starts where it is, not sliding up from an old one.
            shown = progress;
        }
        label = text;
        target = progress;
        active = looking;
        heard = System.currentTimeMillis();
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (label == null || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        if (System.currentTimeMillis() - heard > STALE_MS) {
            label = null;
            return;
        }
        // Eases towards what the server said: it updates five times a second.
        shown = Mth.lerp(Math.min(1f, delta.getRealtimeDeltaTicks() * 0.35f), shown, target);
        Font font = minecraft.font;
        int centerX = graphics.guiWidth() / 2;
        int top = graphics.guiHeight() / 2 + 14;
        int alpha = active ? 0xFF : 0x99;
        Component line = Component.empty().append(label).append(" · " + Math.round(shown * 100) + "%");
        graphics.drawString(font, line, centerX - font.width(line) / 2, top, (alpha << 24) | 0xF2E6CC, true);
        int barLeft = centerX - BAR_W / 2;
        int barTop = top + 11;
        graphics.fill(barLeft - 1, barTop - 1, barLeft + BAR_W + 1, barTop + BAR_H + 1, (alpha << 24) | 0x1A140E);
        graphics.fill(barLeft, barTop, barLeft + Math.round(BAR_W * Mth.clamp(shown, 0, 1)), barTop + BAR_H, (alpha << 24) | (active ? 0xE8C170 : 0x9C8A64));
    }
}
