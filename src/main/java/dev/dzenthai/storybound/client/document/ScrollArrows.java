package dev.dzenthai.storybound.client.document;

import dev.dzenthai.storybound.client.UiPalette;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * The arrows that scroll a long text, in documents and in the Adventure Menu (October 31, 2026, after Stardew Valley's;
 * before, scrollbars): a pixel arrow in the mod's colours, the ornaments' browns in an ink outline with a small light
 * on it and an ink shadow down and to the left. It sits in the middle above and below the text, bobs a pixel towards
 * where it scrolls and brightens under the mouse; it slides in from its edge, solid, as there is text that way. It lies
 * over the text's edge on a band of the paper's colour that fades the lines out there ({@link #veil}), so the text keeps
 * its place whether the arrows show or not (October 31, 2026; before, the same day, room was kept for them over and
 * under the text, which moved its first line down).
 */
public final class ScrollArrows {
    public static final int WIDTH = 10;
    public static final int HEIGHT = 11;
    /** b outline, c shade, d brown, e its light, f the glint; lit from above, so the glint is on the upper part either way. */
    private static final String[] UP = {
            "....bb....",
            "...bcdb...",
            "..bcdfdb..",
            ".bcdddedb.",
            "bccdddeeeb",
            "bbbdddebbb",
            "..bdddeb..",
            "..bdddeb..",
            "..bdddeb..",
            "..bcdedb..",
            "...bbbb..."};
    private static final String[] DOWN = {
            "...bbbb...",
            "..bcdfdb..",
            "..bdddeb..",
            "..bdddeb..",
            "..bdddeb..",
            "bbbdddebbb",
            "bccdddeeeb",
            ".bcdddedb.",
            "..bcdedb..",
            "...bcdb...",
            "....bb...."};
    /** The palette's ink, the ornaments' browns and the paper for the glint; under the mouse the browns go a step lighter. */
    private static final int OUTLINE = UiPalette.INK;
    private static final int SHADE = UiPalette.DARK_BROWN;
    private static final int BROWN = UiPalette.TAN;
    private static final int LIGHT = UiPalette.TAN_LIGHT;
    private static final int GLINT = UiPalette.PAPER;
    private static final int BROWN_HOVER = UiPalette.TAN_LIGHT;
    private static final int LIGHT_HOVER = UiPalette.PAPER_SHADE;
    /** How far over the text the band and the arrow are drawn. */
    private static final float LAYER = 50;
    /** One bob out and back, in milliseconds. */
    private static final double BOB_MS = 1100;

    private ScrollArrows() {}

    /**
     * Draws an arrow, its middle at {@code centerX} and its top at {@code top}, {@code pixel} units to a pixel of it (1 on
     * a document, a book pixel in the menu).
     *
     * @param alpha 0 to 1, as it fades in and out
     */
    public static void draw(GuiGraphics graphics, double centerX, double top, double pixel, boolean up, float alpha, boolean hover) {
        if (alpha <= 0.02f) {
            return;
        }
        // A pixel towards where it scrolls and back, a whole pixel at a time, as the art's pixels stay whole (October 31,
        // 2026; before, the same day, smoothly by parts of a pixel, which put it between the screen's pixels).
        double phase = (Util.getMillis() % (long) BOB_MS) / BOB_MS;
        int bob = hover ? 0 : (int) Math.round(0.5 - 0.5 * Math.cos(phase * 2 * Math.PI)) * (up ? -1 : 1);
        // It comes in from its edge rather than through transparency (it stays solid): 3 pixels out while hidden, a
        // pixel at a time.
        float shown = alpha * alpha * (3 - 2 * alpha);
        int slide = Math.round((1 - shown) * 3) * (up ? -1 : 1);
        // The text is drawn when the frame's batches end, after everything else: drawn now and the arrow put over it,
        // or the lines showed through the arrow.
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate((float) (centerX - WIDTH / 2.0 * pixel), (float) (top + (bob + slide) * pixel), LAYER + 1);
        graphics.pose().scale((float) pixel, (float) pixel, 1);
        String[] rows = up ? UP : DOWN;
        int a = 0xFF << 24;
        int shadow = (75 << 24) | OUTLINE;
        for (int row = 0; row < HEIGHT; row++) {
            for (int col = 0; col < WIDTH; col++) {
                if (rows[row].charAt(col) != '.') {
                    graphics.fill(col - 1, row + 1, col, row + 2, shadow);
                }
            }
        }
        for (int row = 0; row < HEIGHT; row++) {
            for (int col = 0; col < WIDTH; col++) {
                int color = switch (rows[row].charAt(col)) {
                    case 'b' -> OUTLINE;
                    case 'c' -> SHADE;
                    case 'd' -> hover ? BROWN_HOVER : BROWN;
                    case 'e' -> hover ? LIGHT_HOVER : LIGHT;
                    case 'f' -> GLINT;
                    default -> -1;
                };
                if (color >= 0) {
                    graphics.fill(col, row, col + 1, row + 1, a | color);
                }
            }
        }
        graphics.pose().popPose();
    }

    /** When the last click sounded: the wheel turned fast gives one click a notch, not a rattle. */
    private static long lastClick;

    /**
     * The soft click of scrolling: an arrow clicked, and the wheel or a key when the text moved (October 31, 2026; before,
     * the arrows only).
     */
    public static void click() {
        long now = Util.getMillis();
        if (now - lastClick < 50) {
            return;
        }
        lastClick = now;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, 0.25f));
    }

    /** Whether a point is on the arrow, with a pixel of it to spare around. */
    public static boolean contains(double centerX, double top, double pixel, double x, double y) {
        double left = centerX - WIDTH / 2.0 * pixel;
        return x >= left - 2 * pixel && x < left + (WIDTH + 2) * pixel && y >= top - 2 * pixel && y < top + (HEIGHT + 2) * pixel;
    }

    /** The height of the band an arrow lies on, in pixels of it. */
    public static int room() {
        return HEIGHT + 3;
    }

    /**
     * The band of the paper's colour at the text's top ({@code top} true) or bottom edge, fading the lines out towards
     * the edge, under an arrow; {@code pixel} units to a pixel of the arrow.
     */
    public static void veil(GuiGraphics graphics, int left, int right, int edge, double pixel, boolean top, int paper, float alpha) {
        if (alpha <= 0.02f) {
            return;
        }
        int height = (int) Math.ceil(room() * pixel);
        int opaque = (Math.round(alpha * 0xF0) << 24) | (paper & 0xFFFFFF);
        int clear = paper & 0xFFFFFF;
        // Over the text, which is otherwise drawn last (see draw).
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, LAYER);
        if (top) {
            graphics.fillGradient(left, edge, right, edge + height, opaque, clear);
        } else {
            graphics.fillGradient(left, edge - height, right, edge, clear, opaque);
        }
        graphics.pose().popPose();
    }
}
