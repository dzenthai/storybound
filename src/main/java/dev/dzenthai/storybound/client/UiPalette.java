package dev.dzenthai.storybound.client;

/**
 * The author's palette for what the code draws beside their art ({@code ui-kit/assets/pallete.png}, October 31, 2026):
 * the scroll arrows, the observation HUD, the documents' and the book's rules and text, the menu theme's defaults.
 * Each hue in a lighter and a darker tone, as the sheet's two rows; the paper and the ink close it. Colours are RGB
 * without alpha.
 */
public final class UiPalette {
    /** The pages' paper and its shade. */
    public static final int PAPER = 0xF5DFB2;
    public static final int PAPER_SHADE = 0xE6C68C;
    /** The ornaments' brown, its light, and the darker browns under them. */
    public static final int TAN_LIGHT = 0xCDA269;
    public static final int TAN = 0xAF7846;
    public static final int BROWN_LIGHT = 0xB78957;
    public static final int BROWN = 0x90613D;
    public static final int DARK_BROWN = 0x865332;
    /** Text, outlines and shadows. */
    public static final int INK = 0x483020;
    public static final int RED = 0xB3453D;
    public static final int RED_DARK = 0x76212A;
    public static final int GREEN = 0x708B34;
    public static final int GREEN_DARK = 0x415418;
    public static final int PURPLE = 0x825080;
    public static final int PURPLE_DARK = 0x4F2B4E;
    public static final int GOLD_LIGHT = 0xFFE08A;
    public static final int GOLD = 0xE1B34D;
    public static final int OCHRE = 0xB08031;
    public static final int OCHRE_DARK = 0x775025;

    private UiPalette() {}

    /** The colour, opaque. */
    public static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }
}
