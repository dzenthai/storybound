package dev.dzenthai.storybound.client.menu;

import net.minecraft.client.gui.GuiGraphics;

/** Original tonal pixel emblems in 16 by 16 cells, printed on colored cloth. */
final class BookmarkPixels {
    private static final String[][] ICONS = {
            {
                    ".......hh.......", ".......hh.......", "......hhhb......", "......hhhb......",
                    "..h...hbbh...h..", "...h.hhbbbh.h...", "..hhhhhhhhbbbb..", "hhhhhhbbbbbbbbbb",
                    "hhhhhhbbbbbbbbbb", "..hhhbbbbbbbbb..", "...b.bhbbbs.b...", "..b...hbbh...b..",
                    "......bssb......", "......bssb......", ".......ss.......", ".......ss......."
            }, {
                    "................", "...........hhh..", ".........hhhhh..", "........hhhhhb..",
                    ".......hhhbhb...", ".......hhbhbb...", "........bhbb....", ".......bhbb.....",
                    "...hhhhhbb......", "...bbbbb........", "....bbb.........", "..hhbbbbbb......",
                    "..hbbbbbbb......", "..hbbbbbsb......", "...ssssss.......", "................"
            }, {
                    "................", ".hhh........hhh.", ".hhhb......bhhh.", ".hhbbb....bbbhh.",
                    "..hhbbb..bbbhb..", "...hhbbbbbhbb...", "....hhbbbhbb....", ".....hhbhbb.....",
                    "......hhbb......", "...b.bbhhbb.b...", "...bbbh..hbbb...", "....bb....bb....",
                    "...bbsb..bsbb...", "..bb........bb..", "..ss........ss..", "................"
            }, {
                    "................", ".....hhhhhh.....", "...hhhhhhhhh....", "..hhhhhhhhbbb...",
                    "..hhhbbbbbbbb...", "..hhbbbbbbbbb...", "..bb...bb...b...", "..bb...bb...b...",
                    "..bbb.bbbb.bb...", "...bbbb..bbb....", "....bbb..bb.....", "....bbbbbbb.....",
                    "....bb.b.bb.....", "....ss.s.ss.....", "................", "................"
            }, {
                    ".......hh.......", "......hbbh......", "......hbbh......", ".....hbbbbh.....",
                    ".....hbbbbh.....", "...hhbbbbbbhh...", ".hhbbbbbbbbbbhh.", "hbbbbbbhhbbbbbbh",
                    "hbbbbbbhhbbbbbbh", ".hhbbbbbbbbbbhh.", "...hhbbbbbbhh...", ".....hbbbbh.....",
                    ".....hbbbbh.....", "......hbbh......", "......hbbh......", ".......hh......."
            }
    };
    private static final int[][] INKS = {
            {0xFFF0C3A5, 0xFFE3A58D, 0xFFC98270},
            {0xFFC6DFDC, 0xFFA6CAD0, 0xFF82AAB8},
            {0xFFE0E5B1, 0xFFC7D391, 0xFFA5B574},
            {0xFFE7CFE1, 0xFFCEAECC, 0xFFB28EAF},
            {0xFFB9E0EF, 0xFF83BEDA, 0xFF427DA4}
    };
    // Outline, cloth shadow, cloth, seam.
    private static final int[][] COLORS = {
            {0xFF61332F, 0xFF873C36, 0xFFB3453D, 0xFFCD7356},
            {0xFF283D48, 0xFF31566C, 0xFF307395, 0xFF649DAE},
            {0xFF39432C, 0xFF53652E, 0xFF708B34, 0xFF9EB45F},
            {0xFF443142, 0xFF5E405D, 0xFF825080, 0xFFAA79A2},
            {0xFF193A55, 0xFF23587C, 0xFF307FA9, 0xFF64AFCC}
    };

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
        int iconY = (height - 16) / 2;
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                char pixel = ICONS[index][row].charAt(col);
                if (pixel == '.') continue;
                int color = INKS[index][pixel == 'h' ? 0 : pixel == 'b' ? 1 : 2];
                graphics.fill(iconX + col, iconY + row, iconX + col + 1, iconY + row + 1, color);
            }
        }
    }

    static int iconLeft(int width, int height) {
        int notch = Math.max(0, (height - 1) / 2 - height / 5);
        return 6 + (width - notch - 3 - 6 - 16) / 2;
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
