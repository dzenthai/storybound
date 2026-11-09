package dev.dzenthai.storybound.client.menu;

import dev.dzenthai.storybound.client.UiPalette;
import net.minecraft.client.gui.GuiGraphics;

/** Original tonal pixel emblems in 13 by 13 cells, printed on colored cloth. */
final class BookmarkPixels {
    /**
     * The emblems, 13 by 13 on the cloth's pixels: quests' star, the journal's quill, the Codex's crossed swords, the
     * bosses' skull, the map's compass; h light, b body, s shadow (October 31, 2026; before, 16 by 16, a size larger).
     */
    private static final int ICON = 13;
    private static final String[][] ICONS = {
            {
                    "......h......", 
                    "......h......", 
                    ".....hhb.....", 
                    ".....hbb.....",
                    ".h..hhbbb..b.", 
                    "..hhhhbbbbb..", 
                    ".hhhhhbbbbbb.", 
                    "..hhhbbbbbb..",
                    ".b..bbbbs..b.", 
                    ".....bbs.....", 
                    ".....bss.....", 
                    "......s......",
                    "......s......"
            },
            {
                    ".............", 
                    ".........hhh.", 
                    "........hhhb.", 
                    ".......hhbb..",
                    "......hhbb...", 
                    "......hbb....", 
                    ".....bhb.....", 
                    "..hhhhbb.....",
                    "..bbbbb......", 
                    "...bbb.......", 
                    ".hhbbbbbb....", 
                    ".hbbbbbsb....",
                    "..ssssss....."
            },
            {
                    "hhh.......hhh", 
                    "hhbb.....bbhh", 
                    ".hhbb...bbhh.", 
                    "..hhbb.bbhb..",
                    "...hhbbbhb...", 
                    "....hhbhb....", 
                    ".....hhb.....", 
                    "..b.bbhbb.b..",
                    "..bbbh.hbbb..", 
                    "...bb...bb...", 
                    "..bsb...bsb..", 
                    ".bb.......bb.",
                    ".ss.......ss."
            },
            {
                    ".hb.sssss.hb.",
                    "hb.sshhhss.hb",
                    "bbsshhhhhsbbs",
                    ".sbshhhhhsbs.",
                    "..s..bbb..s..",
                    "..b.h.b.h.b..",
                    "..sbbbsbbbs..",
                    "....bbbbb....",
                    "..s.bsbsb.s..",
                    "..b.......b..",
                    "..bbsbsbsbb..",
                    "...bbbbbbb...",
                    "....bbbbb...."
            },
            {
                    "......h......", 
                    ".....hbh.....", 
                    ".....hbh.....", 
                    "....hbbbh....",
                    "..hhbbbbbhh..", 
                    ".hbbbbbbbbbh.", 
                    "hbbbbbhbbbbbh", 
                    ".hbbbbbbbbbh.",
                    "..hhbbbbbhh..", 
                    "....hbbbh....", 
                    ".....hbh.....", 
                    ".....hbh.....",
                    "......h......"
            }
    };
    /**
     * The emblems in a pale tint of their cloth's colour (light, body, shadow), the author's:
     * the one exception to the palette, which dropped these pale tints
     * (October 31, 2026; the same day, paper-coloured thread on every cloth for a while). The map's cloth turned ochre
     * the same day, so its emblem takes the gold sheet's light tones instead of its old pale blue.
     */
    private static final int[][] INKS = {
            {0xFFF0C3A5, 0xFFE3A58D, 0xFFC98270},
            {0xFFC6DFDC, 0xFFA6CAD0, 0xFF82AAB8},
            {0xFFE0E5B1, 0xFFC7D391, 0xFFA5B574},
            {0xFFE7CFE1, 0xFFCEAECC, 0xFFB28EAF},
            {UiPalette.opaque(UiPalette.GOLD_GLINT), UiPalette.opaque(UiPalette.GOLD_LIGHT), UiPalette.opaque(UiPalette.GOLD)}
    };
    /**
     * Outline, cloth shadow, cloth, seam, from the palette's ramps: quests red, the journal blue, the Codex green, the
     * bosses purple, the map ochre (October 31, 2026; before, colours of their own, the map a second blue).
     */
    private static final int[][] COLORS = {
            cloth(UiPalette.RED_DARK, UiPalette.RED_SHADOW, UiPalette.RED, UiPalette.RED_LIGHT),
            cloth(UiPalette.BLUE_DARK, UiPalette.BLUE_SHADOW, UiPalette.BLUE, UiPalette.BLUE_LIGHT),
            cloth(UiPalette.GREEN_DARK, UiPalette.GREEN_SHADOW, UiPalette.GREEN, UiPalette.GREEN_LIGHT),
            cloth(UiPalette.PURPLE_DARK, UiPalette.PURPLE_SHADOW, UiPalette.PURPLE, UiPalette.PURPLE_LIGHT),
            cloth(UiPalette.INK, UiPalette.OCHRE_DARK, UiPalette.OCHRE, UiPalette.GOLD)
    };

    private static int[] cloth(int outline, int shadow, int cloth, int seam) {
        return new int[]{UiPalette.opaque(outline), UiPalette.opaque(shadow), UiPalette.opaque(cloth), UiPalette.opaque(seam)};
    }

    private BookmarkPixels() {}

    /** Open cloth root runs into the paper; only its horizontal seams remain visible. */
    static void drawPageJoin(GuiGraphics graphics, int index, int left, int top, int right, int height) {
        int[] colors = COLORS[index];
        for (int row = 0; row < height; row++) {
            int color = row < 2 || row >= height - 2 ? colors[0]
                    : row == 3 || row == height - 4 ? colors[3]
                    : row > height - 6 ? colors[1] : colors[2];
            graphics.fill(left, top + row, right, top + row + 1, color);
            ditherRow(graphics, colors[1], left, right, top + row, row, height, left + 4, left + 4);
        }
    }

    static void draw(GuiGraphics graphics, int index, int width, int height, float pageJoin) {
        int[] colors = COLORS[index];
        for (int y = 0; y < height; y++) {
            int notch = Math.max(0, Math.min(y, height - 1 - y) - height / 5);
            int right = width - notch;
            graphics.fill(0, y, right, y + 1, colors[0]);
            if (y > 1 && y < height - 2) {
                graphics.fill(2, y, right - 3, y + 1, colors[y > height - 6 ? 1 : 2]);
                graphics.fill(right - 4, y, right - 3, y + 1, colors[y < height / 2 ? 3 : 1]);
                if (y == 3 || y == height - 4) graphics.fill(4, y, right - 4, y + 1, colors[3]);
                int rootEnd = Math.round(6 * (1 - pageJoin));
                int fadeEnd = Math.round(10 * (1 - pageJoin));
                ditherRow(graphics, colors[1], 3, right - 4, y, y, height, rootEnd, fadeEnd);
            }
        }
        int iconX = iconLeft(width, height);
        int iconY = (height - ICON) / 2;
        for (int row = 0; row < ICON; row++) {
            for (int col = 0; col < ICON; col++) {
                char pixel = ICONS[index][row].charAt(col);
                if (pixel == '.') continue;
                int color = INKS[index][pixel == 'h' ? 0 : pixel == 'b' ? 1 : 2];
                graphics.fill(iconX + col, iconY + row, iconX + col + 1, iconY + row + 1, color);
            }
        }
    }

    /**
     * Where the emblem starts, fixed on the cloth so it travels with it: in the middle between the page's edge and the
     * fork's apex, the page's edge taken halfway between a ribbon at rest (6 pixels in) and one slid out (3 in), so it
     * sits near the middle of what shows either way (October 31, 2026; before, the middle of a stretch from 6 in, which
     * put it against the page on the ribbons at rest; for a moment the same day, recentred as the ribbon slid, which
     * made it twitch).
     */
    static int iconLeft(int width, int height) {
        int notch = Math.max(0, (height - 1) / 2 - height / 5);
        return (9 + 2 * (width - notch - ICON)) / 4;
    }

    /** Material coordinates stay continuous across the moving ribbon and its open page root. */
    private static void ditherRow(GuiGraphics graphics, int color, int left, int right, int drawY, int row, int height,
                                  int rootEnd, int fadeEnd) {
        if (row <= 4 || row >= height - 4) return;
        for (int x = left; x < right; x++) {
            boolean root = x < rootEnd && ((x + row) & 1) == 0;
            boolean fade = x >= rootEnd && x < fadeEnd && ((x + row * 2) & 3) == 0;
            boolean hem = row >= height - 8 && ((x + row) & 1) == 0;
            if (root || fade || hem) graphics.fill(x, drawY, x + 1, drawY + 1, color);
        }
    }
}
