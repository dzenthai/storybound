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

    public double toGuiX(double value) { return x + value * scale; }
    public double toGuiY(double value) { return y + value * scale; }
    public double toBookX(double value) { return (value - x) / scale; }
    public double toBookY(double value) { return (value - y) / scale; }
}
