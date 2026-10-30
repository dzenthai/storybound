package dev.dzenthai.storybound.client.menu;

/** A fixed reading canvas, independent of Minecraft's HUD scale; {@code width} and {@code height} are its size. */
public record BookViewport(double scale, double x, double y, int width, int height) {
    /** The documents' canvas. */
    public static final int WIDTH = 640;
    public static final int HEIGHT = 400;
    /**
     * The Adventure Menu's canvas (October 30, 2026; before, the documents' 640 x 400): the book fills 624 x 416 of it,
     * 1.625 units per pixel of the 384 x 256 book instead of 1.5, so the text and everything on the pages is 7.7%
     * smaller beside the book, which stays as large on the screen.
     */
    public static final int MENU_WIDTH = 692;
    public static final int MENU_HEIGHT = 432;
    /**
     * How much of the window the book and the documents take at most (October 31, 2026; before, the book as much as
     * fitted, about 95% of the window's height, and the documents 94% of it): one rule for all of them, so the world
     * stays in sight around them.
     */
    public static final double FILL_HEIGHT = 0.8;
    public static final double FILL_WIDTH = 0.9;

    /** A viewport on the documents' canvas. */
    public BookViewport(double scale, double x, double y) {
        this(scale, x, y, WIDTH, HEIGHT);
    }

    public static BookViewport fit(int framebufferWidth, int framebufferHeight, double guiScale) {
        return fit(framebufferWidth, framebufferHeight, guiScale, WIDTH, HEIGHT);
    }

    /**
     * The documents' canvas for a sheet of {@code width × height} drawn at its middle, one texture pixel to a canvas
     * unit: each pixel of it is a whole number of window pixels (the largest with which the sheet takes at most
     * {@link #FILL_HEIGHT} of the window's height and {@link #FILL_WIDTH} of its width) and the sheet starts on a
     * window pixel (October 31, 2026; before, it filled 94% of the window by any factor, so the drawn pixels came out
     * unequal). Where that would be one window pixel to a texture pixel (a small window), the sheet fills that much by
     * any factor instead, so its text stays readable.
     */
    public static BookViewport document(int framebufferWidth, int framebufferHeight, double guiScale, int width, int height) {
        double fitting = Math.min(FILL_HEIGHT * framebufferHeight / height, FILL_WIDTH * framebufferWidth / width);
        double physicalScale = Math.floor(fitting) >= 2 ? Math.floor(fitting) : fitting;
        double sheetLeft = Math.floor((framebufferWidth - width * physicalScale) / 2);
        double sheetTop = Math.floor((framebufferHeight - height * physicalScale) / 2);
        // The sheet sits at the canvas' middle, as the screen places it: (WIDTH - width) / 2 whole units in.
        double canvasLeft = sheetLeft - (WIDTH - width) / 2 * physicalScale;
        double canvasTop = sheetTop - (HEIGHT - height) / 2 * physicalScale;
        return new BookViewport(physicalScale / guiScale, canvasLeft / guiScale, canvasTop / guiScale, WIDTH, HEIGHT);
    }

    private static BookViewport fit(int framebufferWidth, int framebufferHeight, double guiScale, int width, int height) {
        double physicalScale = 0.94 * Math.min(framebufferWidth / (double) width, framebufferHeight / (double) height);
        return new BookViewport(physicalScale / guiScale,
                (framebufferWidth - width * physicalScale) / (2 * guiScale),
                (framebufferHeight - height * physicalScale) / (2 * guiScale), width, height);
    }

    /**
     * The journal's canvas: like {@link #fit}, but sized so that the book texture ({@code textureWidth} pixels wide,
     * drawn over {@code book} on the canvas) shows each of its pixels as a whole ratio of window pixels (2, 1, 1/2...),
     * the largest with which the canvas fits the window and the book takes at most {@link #FILL_HEIGHT} of its height
     * and {@link #FILL_WIDTH} of its width (October 31, 2026; before, as large as the canvas fitted), and so that the
     * book starts on a window pixel. Stretched by
     * any other factor, neighbouring texture pixels come out a window pixel wider or narrower than each other, which
     * bends the drawn grid (October 29, 2026; before, the canvas filled 94% of the window by any factor). Text keeps
     * the canvas: its size follows the book's, as before.
     */
    public static BookViewport wholePixels(int framebufferWidth, int framebufferHeight, double guiScale, int textureWidth,
                                           JournalLayout book) {
        double perTexturePixel = 0;
        for (int times = 8; times >= 1 && perTexturePixel == 0; times--) {
            perTexturePixel = fits(framebufferWidth, framebufferHeight, times, textureWidth, book) ? times : 0;
        }
        for (int part = 2; part <= 16 && perTexturePixel == 0; part++) {
            perTexturePixel = fits(framebufferWidth, framebufferHeight, 1.0 / part, textureWidth, book) ? 1.0 / part : 0;
        }
        if (perTexturePixel == 0) {
            return fit(framebufferWidth, framebufferHeight, guiScale, MENU_WIDTH, MENU_HEIGHT);
        }
        double physicalScale = perTexturePixel * textureWidth / book.width();
        double bookLeft = Math.floor((framebufferWidth - book.width() * physicalScale) / 2);
        double bookTop = Math.floor((framebufferHeight - book.height() * physicalScale) / 2);
        return new BookViewport(physicalScale / guiScale, (bookLeft - book.x() * physicalScale) / guiScale,
                (bookTop - book.y() * physicalScale) / guiScale, MENU_WIDTH, MENU_HEIGHT);
    }

    private static boolean fits(int framebufferWidth, int framebufferHeight, double perTexturePixel, int textureWidth, JournalLayout book) {
        double physicalScale = perTexturePixel * textureWidth / book.width();
        return MENU_WIDTH * physicalScale <= framebufferWidth && MENU_HEIGHT * physicalScale <= framebufferHeight
                && book.width() * physicalScale <= FILL_WIDTH * framebufferWidth && book.height() * physicalScale <= FILL_HEIGHT * framebufferHeight;
    }

    public double toGuiX(double value) { return x + value * scale; }
    public double toGuiY(double value) { return y + value * scale; }
    public double toBookX(double value) { return (value - x) / scale; }
    public double toBookY(double value) { return (value - y) / scale; }
}
