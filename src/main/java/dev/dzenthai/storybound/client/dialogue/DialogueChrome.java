package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.Storybound;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Warm paper from the approved conversation concept, with fixed-size corner artwork. */
final class DialogueChrome {
    static final int INK = 0xFF49301D;
    static final int MUTED = 0xFF79634B;
    static final int RULE = 0xFFAC7848;
    private static final ResourceLocation PAPER = Storybound.id("textures/gui/dialogue/parchment.png");
    private static final ResourceLocation ORNAMENTS = Storybound.id("textures/gui/dialogue/ornaments.png");
    private static final ResourceLocation NEBULA = Storybound.id("textures/gui/dialogue/nebula.png");

    private DialogueChrome() {}

    static void nebula(GuiGraphics graphics, int x, int y, int width, int height, long elapsedMillis) {
        // The atlas contains the approved preview cels, in row-major order at 20 fps.
        int frame = (int) (Math.max(0, elapsedMillis) / 50 % 64);
        graphics.blit(NEBULA, x - 32, y - 36, width + 64, height + 54,
                (frame % 8) * 320, (frame / 8) * 128, 320, 128, 2560, 1024);
    }

    static void paper(GuiGraphics graphics, int x, int y, int width, int height, boolean compact) {
        int cornerX = compact ? 7 : 19;
        int cornerY = compact ? 7 : 19;
        int[] sx = {0, 140, 1921, 2061};
        int[] sy = {0, 140, 455, 595};
        int[] dx = {x, x + cornerX, x + width - cornerX, x + width};
        int[] dy = {y, y + cornerY, y + height - cornerY, y + height};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                graphics.blit(PAPER, dx[col], dy[row], dx[col + 1] - dx[col], dy[row + 1] - dy[row],
                        sx[col], sy[row], sx[col + 1] - sx[col], sy[row + 1] - sy[row], 2061, 595);
            }
        }
    }

    static void rule(GuiGraphics graphics, int x, int y, int width) {
        int center = x + width / 2;
        graphics.fill(x + 9, y, center - 25, y + 1, RULE);
        graphics.fill(center + 25, y, x + width - 9, y + 1, RULE);
        graphics.blit(ORNAMENTS, center - 25, y - 6, 50, 11, 0, 0, 152, 34, 200, 34);
        graphics.blit(ORNAMENTS, x, y - 4, 12, 8, 160, 0, 36, 24, 200, 34);
        graphics.blit(ORNAMENTS, x + width - 12, y - 4, 12, 8, 196, 0, -36, 24, 200, 34);
    }

    static void portrait(GuiGraphics graphics, int x, int y, int size) {
        graphics.fill(x - 3, y - 2, x + size + 3, y + size + 2, 0xFF694727);
        graphics.fill(x - 2, y - 3, x + size + 2, y + size + 3, 0xFF694727);
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFFC49658);
        graphics.fill(x, y, x + size, y + size, 0xFFA58250);
        for (int dx : new int[]{-2, size - 2}) {
            for (int dy : new int[]{-2, size - 2}) {
                graphics.fill(x + dx, y + dy, x + dx + 4, y + dy + 4, RULE);
                graphics.fill(x + dx + 1, y + dy + 1, x + dx + 3, y + dy + 3, 0xFFEBC88C);
            }
        }
    }

    static void arrow(GuiGraphics graphics, int x, int y, boolean down, int color) {
        for (int i = 0; i < 4; i++) {
            if (down) graphics.fill(x + i, y + i, x + 7 - i, y + i + 1, color);
            else graphics.fill(x + i, y + i, x + i + 1, y + 7 - i, color);
        }
    }

    static void lock(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 1, y + 4, x + 8, y + 10, MUTED);
        graphics.fill(x + 2, y + 1, x + 7, y + 5, MUTED);
        graphics.fill(x + 3, y + 2, x + 6, y + 4, 0xFFE7D2AA);
        graphics.fill(x + 4, y + 6, x + 5, y + 9, 0xFFE7D2AA);
    }

    static void scrollbar(GuiGraphics graphics, int x, int y, int height, int content, int offset) {
        if (content <= height) return;
        int thumb = Math.max(8, height * height / content);
        int top = y + Math.round((height - thumb) * offset / (float) (content - height));
        graphics.fill(x, y, x + 1, y + height, 0x50AC7848);
        graphics.fill(x, top, x + 2, top + thumb, RULE);
    }
}
