package dev.dzenthai.storybound.client.menu;

/** Limits bold coverage correction to text emitted by the book canvas. */
public final class BookTextRendering {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private BookTextRendering() {}

    static void begin() { DEPTH.set(DEPTH.get() + 1); }
    static void end() { DEPTH.set(DEPTH.get() - 1); }
    public static boolean active() { return DEPTH.get() > 0; }
}
