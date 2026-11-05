package dev.dzenthai.storybound.client;

/**
 * The author's palette for what the code draws beside their art ({@code aseprite/palette/}; October 31, 2026,
 * before the same day an 18-colour sheet): the scroll arrows,
 * the observation HUD, the documents' and the book's rules and text, the bookmarks, the menu theme's defaults. Five
 * sheets: the paper and wood ramp down to the ink, the dark tones (a brown bridge from the ink to black, burgundy,
 * neutral blacks), gold, iron to steel, and four colours of four tones each (light, base, shadow, dark). Every colour
 * stands for a material Minecraft has (wood, paper, leather, charcoal, gold, iron, the gems), so the mod fits vanilla
 * and any pack. Colours are RGB without alpha.
 */
public final class UiPalette {
    /** Paper and wood, light to dark, down to the ink: text, outlines and shadows. */
    public static final int PAPER = 0xF5DFB2;
    public static final int PAPER_SHADE = 0xE6C68C;
    public static final int TAN_LIGHT = 0xCDA269;
    public static final int BROWN_LIGHT = 0xB78957;
    public static final int TAN = 0xAF7846;
    public static final int BROWN = 0x90613D;
    public static final int DARK_BROWN = 0x865332;
    public static final int DEEP_BROWN = 0x683F28;
    public static final int INK = 0x483020;

    /** Dark: the ink on to charcoal, burgundy leather, and the blacks. */
    public static final int SHADOW = 0x3A2A1C;
    public static final int SHADOW_DEEP = 0x2A1E15;
    public static final int CHARCOAL = 0x1A140F;
    public static final int BURGUNDY = 0x571E25;
    public static final int BURGUNDY_DARK = 0x361B1B;
    public static final int BURGUNDY_DEEP = 0x231616;
    public static final int SOOT = 0x2C2C24;
    public static final int NIGHT = 0x140C1C;
    public static final int BLACK = 0x0C0C0C;

    /** Gold, glint to dark ochre. */
    public static final int GOLD_GLINT = 0xFFF4C4;
    public static final int GOLD_LIGHT = 0xFFE08A;
    public static final int GOLD = 0xE1B34D;
    public static final int OCHRE = 0xB08031;
    public static final int OCHRE_DARK = 0x775025;

    /** Iron to steel: neutral in the light, cooler in the shadow. */
    public static final int IRON_GLINT = 0xEEEEEA;
    public static final int IRON_LIGHT = 0xD4D4D2;
    public static final int IRON = 0xB2B4B6;
    public static final int IRON_DARK = 0x9498A0;
    public static final int STEEL = 0x767C88;
    public static final int STEEL_DARK = 0x5A6070;
    public static final int STEEL_DEEP = 0x444A58;

    /** Four colours, each light, base, shadow and dark (cloth, seals, gems). */
    public static final int RED_LIGHT = 0xD9785A;
    public static final int RED = 0xB33A2F;
    public static final int RED_SHADOW = 0x86232A;
    public static final int RED_DARK = 0x5E1A26;
    public static final int BLUE_LIGHT = 0x92AACC;
    public static final int BLUE = 0x6480AE;
    public static final int BLUE_SHADOW = 0x4A5690;
    public static final int BLUE_DARK = 0x333A66;
    public static final int GREEN_LIGHT = 0xA5B574;
    public static final int GREEN = 0x708B34;
    public static final int GREEN_SHADOW = 0x56702A;
    public static final int GREEN_DARK = 0x415418;
    public static final int PURPLE_LIGHT = 0xBE96D0;
    public static final int PURPLE = 0x8E64AE;
    public static final int PURPLE_SHADOW = 0x643C88;
    public static final int PURPLE_DARK = 0x3E2868;

    private UiPalette() {}

    /** The colour, opaque. */
    public static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }
}
