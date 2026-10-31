package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.UiPalette;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Warm paper with a pixel brass frame and fixed-size separator artwork. */
final class DialogueChrome {
    static final int INK = UiPalette.opaque(UiPalette.INK);
    static final int MUTED = 0xFF79634B;
    static final int RULE = UiPalette.opaque(UiPalette.TAN);
    private static final ResourceLocation PAPER = Storybound.id("textures/gui/dialogue/parchment.png");
    private static final ResourceLocation FRAME = Storybound.id("textures/gui/dialogue/frame.png");
    private static final ResourceLocation ORNAMENTS = Storybound.id("textures/gui/dialogue/ornaments.png");
    private static final ResourceLocation NEBULA = Storybound.id("textures/gui/dialogue/nebula.png");

    private DialogueChrome() {}

    static void nebula(GuiGraphics graphics, int x, int y, int width, int height, long elapsedMillis) {
        // The atlas contains the approved preview cels, in row-major order at 20 fps.
        int frame = (int) (Math.max(0, elapsedMillis) / 50 % 64);
        graphics.blit(NEBULA, x - 32, y - 36, width + 64, height + 54,
                (frame % 8) * 320, (frame / 8) * 128, 320, 128, 2560, 1024);
    }

    static void paper(GuiGraphics graphics, int x, int y, int width, int height) {
        // Sample only the plain paper, excluding the former edge and corner ornaments.
        graphics.blit(PAPER, x + 3, y + 3, width - 6, height - 6,
                140, 140, 1781, 315, 2061, 595);
        int corner = height >= 80 ? 12 : 8;
        int sourceX = height >= 80 ? 0 : 48;
        int[] source = {0, corner, 32 - corner, 32};
        int[] dx = {x, x + corner, x + width - corner, x + width};
        int[] dy = {y, y + corner, y + height - corner, y + height};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (row == 1 && col == 1) continue;
                graphics.blit(FRAME, dx[col], dy[row], dx[col + 1] - dx[col], dy[row + 1] - dy[row],
                        sourceX + source[col], source[row], source[col + 1] - source[col], source[row + 1] - source[row], 80, 32);
            }
        }
        if (height >= 80) {
            graphics.blit(FRAME, x + width / 2 - 4, y + 1, 8, 6, 32, 0, 8, 6, 80, 32);
            graphics.blit(FRAME, x + width / 2 - 4, y + height - 7, 8, 6, 32, 8, 8, 6, 80, 32);
            graphics.blit(FRAME, x + 1, y + height / 2 - 4, 6, 8, 32, 16, 6, 8, 80, 32);
            graphics.blit(FRAME, x + width - 7, y + height / 2 - 4, 6, 8, 40, 16, 6, 8, 80, 32);
        }
    }

    static void rule(GuiGraphics graphics, int x, int y, int width) {
        int center = x + width / 2;
        int motifWidth = width >= 160 ? 96 : 16;
        int motifSource = width >= 160 ? 0 : 40;
        int left = center - motifWidth / 2;
        int right = left + motifWidth;
        int cap = width >= 64 ? 16 : 0;
        if (x + cap < left) graphics.fill(x + cap, y, left, y + 1, RULE);
        if (right < x + width - cap) graphics.fill(right, y, x + width - cap, y + 1, RULE);
        graphics.blit(ORNAMENTS, left, y - 8, motifWidth, 16, motifSource, 0, motifWidth, 16, 128, 16);
        if (cap > 0) {
            graphics.blit(ORNAMENTS, x, y - 8, 16, 16, 112, 0, 16, 16, 128, 16);
            graphics.blit(ORNAMENTS, x + width - 16, y - 8, 16, 16, 128, 0, -16, 16, 128, 16);
        }
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
