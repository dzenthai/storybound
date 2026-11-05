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

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        fillBookFace(graphics, 0xFFE5CDA3);
        if (active && isHoveredOrFocused()) {
            fillBookFace(graphics, theme.hover());
        }
        int stamp = theme.divider();
        // Two-pixel stepped corners match the book's printed ornament scale.
        graphics.fill(getX() + 3, getY(), getX() + width - 3, getY() + 2, stamp);
        graphics.fill(getX() + 3, getY() + height - 2, getX() + width - 3, getY() + height, stamp);
        graphics.fill(getX(), getY() + 3, getX() + 2, getY() + height - 3, stamp);
        graphics.fill(getX() + width - 2, getY() + 3, getX() + width, getY() + height - 3, stamp);
        graphics.fill(getX() + 2, getY() + 2, getX() + 4, getY() + 4, stamp);
        graphics.fill(getX() + width - 4, getY() + 2, getX() + width - 2, getY() + 4, stamp);
        graphics.fill(getX() + 2, getY() + height - 4, getX() + 4, getY() + height - 2, stamp);
        graphics.fill(getX() + width - 4, getY() + height - 4, getX() + width - 2, getY() + height - 2, stamp);
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
            int py = labelHeight / 2 - 4;
            graphics.fill(labelX + 4, py, labelX + 8, py + 4, stamp);
            graphics.fill(labelX + 2, py + 3, labelX + 6, py + 5, stamp);
            graphics.fill(labelX + 1, py + 5, labelX + 3, py + 7, stamp);
            graphics.fill(labelX, py + 7, labelX + 1, py + 8, stamp);
        }
        graphics.drawString(font, net.minecraft.locale.Language.getInstance().getVisualOrder(clipped),
                labelX + inset, (labelHeight - 7) / 2,
                active ? theme.ink() : theme.muted(), false);
        graphics.pose().popPose();
        if (active && readyEffect.getAsBoolean()) {
            long now = net.minecraft.Util.getMillis();
            if (readySince < 0) readySince = now;
            long elapsed = now - readySince;
            for (int i = 0; i < 5; i++) {
                long age = elapsed - i * 260L;
                if (age < 0) continue;
                float phase = (age % 1800) / 1250f;
                if (phase >= 1) continue;
                int alpha = (int) (225 * Math.min(1, phase / 0.16f) * Math.pow(1 - phase, 0.6));
                float drift = (float) Math.sin(phase * Math.PI) * (i % 2 == 0 ? -4 : 4);
                float px = getX() + width * (i + 1) / 6f + drift;
                float py = getY() - 3 - phase * 13;
                int radius = phase > 0.84f ? 1 : i == 2 ? 4 : i % 2 == 0 ? 2 : 3;
                graphics.pose().pushPose();
                graphics.pose().translate(px, py, 0);
                JournalSkin.rewardSpark(graphics, radius, alpha);
                graphics.pose().popPose();
            }
        } else {
            readySince = -1;
        }
    }

    private void fillBookFace(GuiGraphics graphics, int color) {
        graphics.fill(getX() + 3, getY() + 1, getX() + width - 3, getY() + height - 1, color);
        graphics.fill(getX() + 1, getY() + 3, getX() + width - 1, getY() + height - 3, color);
    }
}
