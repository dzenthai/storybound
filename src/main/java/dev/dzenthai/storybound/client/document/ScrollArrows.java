package dev.dzenthai.storybound.client.document;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The arrows that scroll a long text, in documents and in the Adventure Menu (October 31, 2026, after Stardew Valley's;
 * before, scrollbars): a pixel arrow in the mod's colours, the ornaments' browns in an ink outline with a small light
 * on it and an ink shadow down and to the left. It sits in the middle above and below the text, bobs a pixel towards
 * where it scrolls and brightens under the mouse; the screen fades it in and out as there is text that way.
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
    /** The ink, the ornaments' brown in three tones, and the paper's light for the glint. */
    private static final int OUTLINE = 0x463626;
    private static final int SHADE = 0x8C5B34;
    private static final int BROWN = 0xAD794B;
    private static final int LIGHT = 0xCF9C66;
    private static final int GLINT = 0xF3DDB0;
    private static final int BROWN_HOVER = 0xC08A57;
    private static final int LIGHT_HOVER = 0xDEB07A;
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
        // A pixel towards where it scrolls and back, smoothly: the pose moves by parts of a pixel.
        double phase = (Util.getMillis() % (long) BOB_MS) / BOB_MS;
        float bob = hover ? 0 : (float) ((0.5 - 0.5 * Math.cos(phase * 2 * Math.PI)) * (up ? -1 : 1));
        graphics.pose().pushPose();
        graphics.pose().translate((float) (centerX - WIDTH / 2.0 * pixel), (float) (top + bob * pixel), 0);
        graphics.pose().scale((float) pixel, (float) pixel, 1);
        String[] rows = up ? UP : DOWN;
        int a = Math.round(alpha * 255) << 24;
        int shadow = (Math.round(alpha * 75) << 24) | OUTLINE;
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

    /** Whether a point is on the arrow, with a pixel of it to spare around. */
    public static boolean contains(double centerX, double top, double pixel, double x, double y) {
        double left = centerX - WIDTH / 2.0 * pixel;
        return x >= left - 2 * pixel && x < left + (WIDTH + 2) * pixel && y >= top - 2 * pixel && y < top + (HEIGHT + 2) * pixel;
    }

    /** The room an arrow takes above or below a text, in pixels of it, with a gap to the text. */
    public static int room() {
        return HEIGHT + 3;
    }
}
