package dev.dzenthai.storybound.client.menu;

/** Geometry follows the blank book texture; all measurements are GUI pixels, not window pixels. */
public record JournalLayout(int x, int y, int width, int height) {
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
    }

    public static JournalLayout fit(int screenWidth, int screenHeight) {
        int width = Math.max(1, Math.min(720, Math.min(screenWidth - 48, (screenHeight - 16) * 3 / 2)));
        int height = Math.max(1, width * 2 / 3);
        return new JournalLayout((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
    }

    public Rect content() {
        int left = x + width * 10 / 100;
        int top = y + Math.max(height * 26 / 100, height * 17 / 100 + 28);
        return new Rect(left, top, x + width * 88 / 100 - left, y + height * 81 / 100 - top);
    }

    public int indexWidth() { return x + width * 44 / 100 - content().x(); }
    public int detailLeft() { return x + width * 54 / 100; }
    public int titleY() { return y + height * 17 / 100; }
    public boolean compact() { return width < 400; }
    public int pageEdge() { return x + width * 1420 / 1536; }

    public Rect bookmark(int index, int count) {
        int top = y + height * 14 / 100;
        int bottom = y + height * 80 / 100;
        int slot = Math.max(1, Math.min(height * 10 / 100, (bottom - top) / Math.max(1, count)));
        int tabHeight = Math.max(1, slot - 4);
        return new Rect(pageEdge(), top + slot * index,
                Math.max(14, width * 7 / 100 + 4), tabHeight);
    }
}
