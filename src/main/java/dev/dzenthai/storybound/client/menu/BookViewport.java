package dev.dzenthai.storybound.client.menu;

/** A fixed reading canvas, independent of Minecraft's HUD scale. */
public record BookViewport(double scale, double x, double y) {
    public static final int WIDTH = 640;
    public static final int HEIGHT = 400;

    public static BookViewport fit(int framebufferWidth, int framebufferHeight, double guiScale) {
        double physicalScale = 0.94 * Math.min(framebufferWidth / (double) WIDTH, framebufferHeight / (double) HEIGHT);
        return new BookViewport(physicalScale / guiScale,
                (framebufferWidth - WIDTH * physicalScale) / (2 * guiScale),
                (framebufferHeight - HEIGHT * physicalScale) / (2 * guiScale));
    }

    /**
     * The journal's canvas: like {@link #fit}, but sized so that the book texture ({@code textureWidth} pixels wide,
     * drawn over {@code book} on the canvas) shows each of its pixels as a whole ratio of window pixels (2, 1, 1/2...),
     * the largest with which the canvas fits the window, and so that the book starts on a window pixel. Stretched by
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
            return fit(framebufferWidth, framebufferHeight, guiScale);
        }
        double physicalScale = perTexturePixel * textureWidth / book.width();
        double bookLeft = Math.floor((framebufferWidth - book.width() * physicalScale) / 2);
        double bookTop = Math.floor((framebufferHeight - book.height() * physicalScale) / 2);
        return new BookViewport(physicalScale / guiScale, (bookLeft - book.x() * physicalScale) / guiScale,
                (bookTop - book.y() * physicalScale) / guiScale);
    }

    private static boolean fits(int framebufferWidth, int framebufferHeight, double perTexturePixel, int textureWidth, JournalLayout book) {
        double physicalScale = perTexturePixel * textureWidth / book.width();
        return WIDTH * physicalScale <= framebufferWidth && HEIGHT * physicalScale <= framebufferHeight;
    }

    public double toGuiX(double value) { return x + value * scale; }
    public double toGuiY(double value) { return y + value * scale; }
    public double toBookX(double value) { return (value - x) / scale; }
    public double toBookY(double value) { return (value - y) / scale; }
}
