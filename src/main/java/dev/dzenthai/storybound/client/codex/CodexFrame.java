package dev.dzenthai.storybound.client.codex;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dzenthai.storybound.client.UiPalette;
import dev.dzenthai.storybound.client.menu.JournalLayout;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Double scalloped book frame drawn strictly on the book texture's pixel grid ({@link JournalLayout#bookPixel})
 * using the book pattern colour ({@code theme.divider()}). Outer and inner 1-book-pixel lines curve inward in parallel
 * at all four corners, masking the photo cleanly to the inner silhouette.
 */
final class CodexFrame {
    /** Outer line (0) + 1 book pixel paper gap (1) + inner line (2); photo begins at offset 3. */
    private static final int PHOTO_INSET = 3;

    /**
     * Symmetrical 6x6 top-left double-scalloped corner on the book's pixel grid.
     * S = book pattern line ({@code theme.divider()}), P = page paper mask, '.' = transparent (shows page or photo).
     */
    private static final String[] CORNER_TL = {
            "...SSS",
            "...SPP",
            "..SPPS",
            "SSPPPS",
            "SPPPS.",
            "SPSS.."
    };

    private CodexFrame() {}

    /** Computes a balanced 16:9 frame height snapped to whole book pixels for the available detail width. */
    static int heightForWidth(JournalLayout layout, int width) {
        double pixel = layout.bookPixel();
        int bookW = Math.max(24, (int) Math.round(width / pixel));
        int bookH = Mth.clamp(Math.round(bookW * 9.0f / 16.0f), 46, 76);
        return Math.max(40, (int) Math.round(bookH * pixel));
    }

    /** Renders {@code image} aspect-cropped inside the double scalloped frame snapped to whole book pixels. */
    static void draw(GuiGraphics graphics, JournalLayout layout, CodexImages.Loaded image, int x, int y, int width, int height) {
        double pixel = layout.bookPixel();
        int left = (int) Math.round((x - layout.x()) / pixel);
        int top = (int) Math.round((y - layout.y()) / pixel);
        int right = (int) Math.round((x + width - layout.x()) / pixel);
        int bottom = (int) Math.round((y + height - layout.y()) / pixel);
        if (right - left < 16 || bottom - top < 16) {
            return;
        }

        // Photo fills the exact snapped book-pixel inner box; corner masks trim its 4 corners to the inner scallop.
        int ix = layout.x() + (int) Math.round((left + PHOTO_INSET) * pixel);
        int iy = layout.y() + (int) Math.round((top + PHOTO_INSET) * pixel);
        int ix2 = layout.x() + (int) Math.round((right - PHOTO_INSET) * pixel);
        int iy2 = layout.y() + (int) Math.round((bottom - PHOTO_INSET) * pixel);
        int iw = Math.max(1, ix2 - ix);
        int ih = Math.max(1, iy2 - iy);

        RenderSystem.enableBlend();
        graphics.fill(ix, iy, ix + iw, iy + ih, UiPalette.opaque(UiPalette.PAPER_SHADE));

        float targetAspect = (float) iw / ih;
        float texAspect = (float) image.width() / image.height();
        float u = 0f;
        float v = 0f;
        int regionW = image.width();
        int regionH = image.height();
        if (texAspect > targetAspect) {
            regionW = Math.max(1, Math.round(image.height() * targetAspect));
            u = (image.width() - regionW) / 2.0f;
        } else if (texAspect < targetAspect) {
            regionH = Math.max(1, Math.round(image.width() / targetAspect));
            v = (image.height() - regionH) / 2.0f;
        }
        graphics.blit(image.texture(), ix, iy, iw, ih, u, v, regionW, regionH, image.width(), image.height());

        // Draw both parallel lines and the 4 scalloped corners strictly on the book's pixel grid.
        graphics.pose().pushPose();
        graphics.pose().translate(layout.x(), layout.y(), 0);
        graphics.pose().scale((float) pixel, (float) pixel, 1);

        int stamp = MenuThemes.INSTANCE.theme().divider();
        int paper = UiPalette.opaque(UiPalette.PAPER);

        graphics.drawManaged(() -> {
            // Straight outer (offset 0) and inner (offset 2) rails between the 6x6 corners.
            int hFrom = left + 6;
            int hTo = right - 6;
            if (hTo > hFrom) {
                graphics.fill(hFrom, top, hTo, top + 1, stamp);
                graphics.fill(hFrom, top + 2, hTo, top + 3, stamp);
                graphics.fill(hFrom, bottom - 3, hTo, bottom - 2, stamp);
                graphics.fill(hFrom, bottom - 1, hTo, bottom, stamp);
            }

            int vFrom = top + 6;
            int vTo = bottom - 6;
            if (vTo > vFrom) {
                graphics.fill(left, vFrom, left + 1, vTo, stamp);
                graphics.fill(left + 2, vFrom, left + 3, vTo, stamp);
                graphics.fill(right - 3, vFrom, right - 2, vTo, stamp);
                graphics.fill(right - 1, vFrom, right, vTo, stamp);
            }

            // Four mirrored 6x6 scalloped corners (also masking the photo corners with page paper).
            paintCorner(graphics, left, top, false, false, stamp, paper);
            paintCorner(graphics, right - 6, top, true, false, stamp, paper);
            paintCorner(graphics, left, bottom - 6, false, true, stamp, paper);
            paintCorner(graphics, right - 6, bottom - 6, true, true, stamp, paper);
        });

        graphics.pose().popPose();
    }

    private static void paintCorner(GuiGraphics graphics, int ox, int oy, boolean flipX, boolean flipY, int stamp, int paper) {
        for (int sy = 0; sy < 6; sy++) {
            String row = CORNER_TL[sy];
            for (int sx = 0; sx < 6; sx++) {
                char c = row.charAt(sx);
                if (c == '.') {
                    continue;
                }
                int dx = flipX ? 5 - sx : sx;
                int dy = flipY ? 5 - sy : sy;
                graphics.fill(ox + dx, oy + dy, ox + dx + 1, oy + dy + 1, c == 'S' ? stamp : paper);
            }
        }
    }
}
