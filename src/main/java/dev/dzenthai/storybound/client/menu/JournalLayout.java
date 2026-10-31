package dev.dzenthai.storybound.client.menu;

/**
 * Geometry follows the blank book texture; all measurements are GUI pixels, not window pixels. In the 384×256 book
 * (October 30, 2026) the pages are x 23–183 and 200–360, their frames' lines the pixels x 33 and 176, 207 and 350,
 * y 32–208, the small squares in the middle of the frames the pixels x 102–107 and 276–281: each page's middle is the
 * line between pixels, x 105 and 279. Halfway down, the frames' side squares stand 3 pixels into the pages (x 30–36
 * and 173–179, 204–210 and 347–353). The index (x 41 up to 169) and the details (x 215 up to 343), 128 book pixels
 * each, are centred on the pages' middles, so the title's rule and its diamond line up with the square, and keep 4
 * pixels from the side squares, their scrollbars (1–3 pixels to the right) clear of them (before, 10%–44% and
 * 56%–90% of the book, off the middles; then 38–171, a third of a pixel short of them; then 38 up to 172 and 212 up
 * to 346, from the frames' lines: the details passed 2 pixels from the left square, the scrollbar ran over the right
 * one).
 */
public record JournalLayout(int x, int y, int width, int height) {
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
    }

    public static JournalLayout fit(int screenWidth, int screenHeight) {
        // Up to 1536: the menu's canvas grows with the window so its text keeps whole pixels (BookViewport.textUnit).
        int width = Math.max(1, Math.min(1536, Math.min(screenWidth - 48, (screenHeight - 16) * 3 / 2)));
        int height = Math.max(1, width * 2 / 3);
        return new JournalLayout((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
    }

    /** The left page's column (the index) and the right page's (the details), in book pixels, up to the second. */
    public static final int LEFT_FROM = 41;
    public static final int LEFT_TO = 169;
    public static final int RIGHT_FROM = 215;
    public static final int RIGHT_TO = 343;

    /** A column of the book texture in GUI pixels: {@code px} of its pixels from its left. */
    private int atBookX(int px) {
        return x + width * px / 384;
    }

    /** How many GUI pixels a pixel of the book texture is: rules on the pages are drawn in the book's pixels. */
    public double bookPixel() {
        return width / 384.0;
    }

    public Rect content() {
        int left = atBookX(LEFT_FROM);
        int top = y + Math.max(height * 26 / 100, height * 17 / 100 + 28);
        return new Rect(left, top, atBookX(RIGHT_TO) - left, y + height * 79 / 100 - top);
    }

    public int indexWidth() { return atBookX(LEFT_TO) - content().x(); }
    public int detailLeft() { return atBookX(RIGHT_FROM); }
    public int titleY() { return y + height * 17 / 100; }
    public boolean compact() { return width < 400; }
    /** The dark paper fore-edge ends at texel 364, before the red leather at 365. */
    public int pageEdge() { return x + width * 364 / 384; }

    public float bookmarkPixelScale() { return width / 384.0f; }

    public Rect bookmark(int index, int count) {
        float pixel = bookmarkPixelScale();
        int slot = Math.max(1, Math.min(26, 168 / Math.max(1, count)));
        int top = y + Math.round((36 + slot * index) * pixel);
        int bottom = y + Math.round((36 + slot * (index + 1) - 2) * pixel);
        return new Rect(pageEdge(), top, Math.max(1, Math.round(32 * pixel)), Math.max(1, bottom - top));
    }
}
