package dev.dzenthai.storybound.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A button on the book's pages: paper with a stamped frame, the label in the book's font, an optional pin, and an
 * optional burst of sparks when what it does is ready (a reward to claim). Keeps vanilla's input, narration and
 * label clipping (November 4, 2026; before, {@code ArchiveChrome.ArchiveButton}, which also had a look for a menu
 * without the book).
 */
public final class BookButton extends Button {
    private boolean pin;
    /**
     * A unit of the label (and its pin) in the button's units: below 1 where the button stands in a book's pixels and
     * the text has units of its own, as under the leather book read as an item (November 5, 2026).
     */
    private float labelScale = 1;
    private java.util.function.BooleanSupplier readyEffect = () -> false;
    private long readySince = -1;
    /**
     * The menu's book, when the button stands on its pages: then its frame and sparks are drawn on the book's pixels,
     * as the page's rules and arrows (November 7, 2026; before, on the text's units, finer than the book's pixels at
     * 1920 × 1080: two kinds of pixel side by side). Null on a document, where the button stands in its pixels already.
     */
    private java.util.function.Supplier<JournalLayout> book;

    public BookButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    public BookButton withReadyEffect(java.util.function.BooleanSupplier ready) {
        readyEffect = ready;
        return this;
    }

    public BookButton withPin() {
        pin = true;
        return this;
    }

    public BookButton withLabelScale(float scale) {
        labelScale = scale;
        return this;
    }

    /** Stands on the menu book's pages: frame and sparks on its pixels. */
    public BookButton onBook(java.util.function.Supplier<JournalLayout> layout) {
        book = layout;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        int stamp = theme.divider();
        JournalLayout layout = book != null ? book.get() : null;
        if (layout != null) {
            frameOnBook(graphics, layout, theme, stamp);
        } else {
            fillBookFace(graphics, 0xFFE5CDA3);
            if (active && isHoveredOrFocused()) {
                fillBookFace(graphics, theme.hover());
            }
            // Two-pixel stepped corners match the book's printed ornament scale.
            graphics.fill(getX() + 3, getY(), getX() + width - 3, getY() + 2, stamp);
            graphics.fill(getX() + 3, getY() + height - 2, getX() + width - 3, getY() + height, stamp);
            graphics.fill(getX(), getY() + 3, getX() + 2, getY() + height - 3, stamp);
            graphics.fill(getX() + width - 2, getY() + 3, getX() + width, getY() + height - 3, stamp);
            graphics.fill(getX() + 2, getY() + 2, getX() + 4, getY() + 4, stamp);
            graphics.fill(getX() + width - 4, getY() + 2, getX() + width - 2, getY() + 4, stamp);
            graphics.fill(getX() + 2, getY() + height - 4, getX() + 4, getY() + height - 2, stamp);
            graphics.fill(getX() + width - 4, getY() + height - 4, getX() + width - 2, getY() + height - 2, stamp);
        }
        var font = Minecraft.getInstance().font;
        var label = JournalSkin.text(getMessage());
        // The label in its own units from the button's top left, whole ones, so its pixels stay whole.
        int labelWidth = (int) Math.floor(width / labelScale + 1e-4);
        int labelHeight = (int) Math.floor(height / labelScale + 1e-4);
        graphics.pose().pushPose();
        graphics.pose().translate(getX(), getY(), 0);
        graphics.pose().scale(labelScale, labelScale, 1);
        int inset = pin ? 13 : 0;
        var clipped = font.substrByWidth(label, Math.max(1, labelWidth - 8 - inset));
        int labelX = (labelWidth - font.width(clipped) - inset) / 2;
        if (pin) {
            JournalSkin.pin(graphics, labelX, labelHeight / 2 - 4, stamp);
        }
        graphics.drawString(font, net.minecraft.locale.Language.getInstance().getVisualOrder(clipped),
                labelX + inset, (labelHeight - 7) / 2,
                active ? theme.ink() : theme.muted(), false);
        graphics.pose().popPose();
        if (active && readyEffect.getAsBoolean()) {
            long now = net.minecraft.Util.getMillis();
            if (readySince < 0) readySince = now;
            long elapsed = now - readySince;
            // Stars, each in a lane of its own above the button (November 7, 2026; before, neighbours drifted towards each
            // other and stuck together, and every round rose the same, in a row): 5 over a wide button, 4 or 3 over a
            // narrow one; they set off evenly through the round, the largest in the middle, a size smaller in narrow
            // lanes; each drifts away from the middle, so neighbours never close in, and each round starts from another
            // place in its lane and rises at another pace. Checked by a simulation of widths 36 to 90: none touch.
            int count = width >= 60 ? 5 : width >= 44 ? 4 : 3;
            int[] sizes = SPARK_SIZES[count - 3];
            float lane = width / (float) (count + 1);
            float middle = (count - 1) / 2f;
            for (int i = 0; i < count; i++) {
                long age = elapsed - i * (SPARK_ROUND / count);
                if (age < 0) continue;
                long round = age / SPARK_ROUND;
                float phase = (age % SPARK_ROUND) / (float) SPARK_LIFE;
                if (phase >= 1) continue;
                float jitter = jitter(round, i);
                int alpha = (int) (225 * Math.min(1, phase / 0.16f) * Math.pow(1 - phase, 0.6));
                float drift = (float) Math.sin(phase * Math.PI) * Math.min(3f, lane * 0.15f) * Math.signum(i - middle);
                float px = getX() + lane * (i + 1) + jitter * lane * 0.25f + drift;
                float py = getY() - 3 - phase * (11 + (jitter + 0.5f) * 5);
                int radius = phase > 0.84f ? 1 : sizes[i] - (lane < 13 ? 1 : 0);
                graphics.pose().pushPose();
                if (layout != null) {
                    // On the book's pixels, a whole one at a time; a size smaller, as its pixel is larger than a unit.
                    double pixel = layout.bookPixel();
                    graphics.pose().translate((float) (layout.x() + Math.round((px - layout.x()) / pixel) * pixel),
                            (float) (layout.y() + Math.round((py - layout.y()) / pixel) * pixel), 0);
                    graphics.pose().scale((float) pixel, (float) pixel, 1);
                    radius = Math.max(1, radius - 1);
                } else {
                    graphics.pose().translate(px, py, 0);
                }
                JournalSkin.rewardSpark(graphics, radius, alpha);
                graphics.pose().popPose();
            }
        } else {
            readySince = -1;
        }
    }

    /**
     * The frame on the book's pixels: the button's edges snapped to them, a line one book pixel thick (as the page's
     * frame) with a pixel step at each corner, the paper face inside it.
     */
    private void frameOnBook(GuiGraphics graphics, JournalLayout layout, MenuTheme theme, int stamp) {
        double pixel = layout.bookPixel();
        int left = (int) Math.round((getX() - layout.x()) / pixel);
        int top = (int) Math.round((getY() - layout.y()) / pixel);
        int right = (int) Math.round((getX() + width - layout.x()) / pixel);
        int bottom = (int) Math.round((getY() + height - layout.y()) / pixel);
        graphics.pose().pushPose();
        graphics.pose().translate(layout.x(), layout.y(), 0);
        graphics.pose().scale((float) pixel, (float) pixel, 1);
        int face = active && isHoveredOrFocused() ? theme.hover() : 0xFFE5CDA3;
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, 0xFFE5CDA3);
        if (face != 0xFFE5CDA3) {
            graphics.fill(left + 1, top + 1, right - 1, bottom - 1, face);
        }
        graphics.fill(left + 2, top, right - 2, top + 1, stamp);
        graphics.fill(left + 2, bottom - 1, right - 2, bottom, stamp);
        graphics.fill(left, top + 2, left + 1, bottom - 2, stamp);
        graphics.fill(right - 1, top + 2, right, bottom - 2, stamp);
        graphics.fill(left + 1, top + 1, left + 2, top + 2, stamp);
        graphics.fill(right - 2, top + 1, right - 1, top + 2, stamp);
        graphics.fill(left + 1, bottom - 2, left + 2, bottom - 1, stamp);
        graphics.fill(right - 2, bottom - 2, right - 1, bottom - 1, stamp);
        graphics.pose().popPose();
    }

    private static final long SPARK_ROUND = 1800;
    private static final long SPARK_LIFE = 1250;
    /** For 3, 4 and 5 stars: small to large to small along the button, two large ones never side by side. */
    private static final int[][] SPARK_SIZES = {{2, 3, 2}, {2, 3, 3, 2}, {2, 3, 4, 3, 2}};

    /** From -0.5 to 0.5, the same for a star in a round, another in the next. */
    private static float jitter(long round, int star) {
        long seed = round * 0x9E3779B97F4A7C15L + star * 0xBF58476D1CE4E5B9L;
        seed ^= seed >>> 31;
        seed *= 0x94D049BB133111EBL;
        seed ^= seed >>> 29;
        return ((seed >>> 40) & 0xFF) / 255f - 0.5f;
    }

    private void fillBookFace(GuiGraphics graphics, int color) {
        graphics.fill(getX() + 3, getY() + 1, getX() + width - 3, getY() + height - 1, color);
        graphics.fill(getX() + 1, getY() + 3, getX() + width - 1, getY() + height - 3, color);
    }
}
