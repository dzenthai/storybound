package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.UiPalette;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Warm paper with a pixel brass frame and fixed-size separator artwork. */
final class DialogueChrome {
    static final int INK = UiPalette.opaque(UiPalette.INK);
    static final int MUTED = UiPalette.opaque(UiPalette.BROWN);
    static final int RULE = UiPalette.opaque(UiPalette.TAN);
    static final int GOLD = UiPalette.opaque(UiPalette.GOLD);
    static final int GOLD_LIGHT = UiPalette.opaque(UiPalette.GOLD_LIGHT);
    static final int OCHRE = UiPalette.opaque(UiPalette.OCHRE);
    static final int OCHRE_DARK = UiPalette.opaque(UiPalette.OCHRE_DARK);
    static final int PAPER_TEXT = UiPalette.opaque(UiPalette.PAPER);
    static final int REPLY_WHITE = UiPalette.opaque(UiPalette.IRON_GLINT);
    static final int REPLY_LOCKED = (0x88 << 24) | UiPalette.TAN_LIGHT;
    static final int PORTRAIT_SIZE = 32;
    private static final ResourceLocation PAPER = Storybound.id("textures/gui/dialogue/parchment.png");
    private static final ResourceLocation FRAME = Storybound.id("textures/gui/dialogue/frame.png");
    private static final ResourceLocation ORNAMENTS = Storybound.id("textures/gui/dialogue/ornaments.png");
    private static final ResourceLocation NEBULA = Storybound.id("textures/gui/dialogue/nebula.png");

    private DialogueChrome() {}

    static void box(GuiGraphics graphics, int x, int y, int width, int height, int background) {
        graphics.fill(x, y, x + width, y + height, background);
    }

    static boolean hasPortrait(Minecraft minecraft, ClientDialogue.Line line) {
        if (line.speaker().portrait().isPresent()) {
            return true;
        }
        return minecraft.level != null && line.speaker().entityId() >= 0
                && minecraft.level.getEntity(line.speaker().entityId()) instanceof LivingEntity;
    }

    static void renderFrontPortrait(Minecraft minecraft, GuiGraphics graphics, ClientDialogue.Line line, int x, int y, int size) {
        ResourceLocation texture = line.speaker().portrait().orElse(null);
        if (texture != null) {
            graphics.blit(texture, x, y, size, size, 0, 0, 64, 64, 64, 64);
            return;
        }
        Entity entity = minecraft.level != null ? minecraft.level.getEntity(line.speaker().entityId()) : null;
        if (entity instanceof LivingEntity living) {
            float scale = living.getScale();
            float yOffset = (living.getEyeHeight() - living.getBbHeight() / 2f) / scale - 0.04f;
            InventoryScreen.renderEntityInInventoryFollowsAngle(graphics, x, y, x + size, y + size,
                    Math.round(size * 1.15f), yOffset, 0f, 0f, living);
        }
    }

    static void choiceMarker(GuiGraphics graphics, int x, int y, int alpha) {
        int gold = (alpha << 24) | UiPalette.GOLD;
        int goldLight = (alpha << 24) | UiPalette.GOLD_LIGHT;
        int ochre = (alpha << 24) | UiPalette.OCHRE;
        int shadow = (alpha << 24) | UiPalette.SHADOW;
        graphics.fill(x + 1, y, x + 2, y + 1, gold);
        graphics.fill(x, y + 1, x + 1, y + 2, gold);
        graphics.fill(x + 1, y + 1, x + 2, y + 2, goldLight);
        graphics.fill(x + 2, y + 1, x + 3, y + 2, ochre);
        graphics.fill(x + 1, y + 2, x + 2, y + 3, ochre);
        graphics.fill(x + 2, y + 2, x + 3, y + 3, shadow);
    }

    static void renderChosen(GuiGraphics graphics, Font font, ClientDialogue.Chosen chosen, long now, int fallbackX, int fallbackY) {
        float fade = chosen.alpha(now);
        if (fade <= 0.03f) {
            return;
        }
        int alpha = Math.max(4, Math.min(255, Math.round(fade * 255f)));
        int x = chosen.x() > 0 ? chosen.x() : fallbackX;
        int y = chosen.y() > 0 ? chosen.y() : fallbackY;
        int width = Math.max(120, chosen.width());
        List<FormattedCharSequence> lines = font.split(chosen.text(), width - 22);
        int lineHeight = 11;
        int color = (alpha << 24) | UiPalette.GOLD;
        choiceMarker(graphics, x - 5, y + 2, alpha);
        graphics.drawString(font, chosen.number() + ".", x, y, color, true);
        int rowY = y;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, x + 16, rowY, color, true);
            rowY += lineHeight;
        }
    }

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
