package dev.dzenthai.storybound.client.menu;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A line to write on in the book: Minecraft's text field, typed into as usual, but drawn as ink on the page, without
 * its frame and without the drop shadow that doubled the letters on the paper; a thin blinking stroke for the cursor.
 * For short texts that fit the line (it does not scroll).
 */
public final class BookEditBox extends EditBox {
    private final Font font;
    private final int ink;
    private @Nullable Component placeholder;
    private int placeholderColor;

    public BookEditBox(Font font, int x, int y, int width, Component label, int ink) {
        super(font, x, y, width, 10, label);
        this.font = font;
        this.ink = ink;
        setBordered(false);
        setTextColor(ink);
    }

    /** What the line shows while empty and not being written on. */
    public BookEditBox placeholder(Component text, int color) {
        placeholder = text;
        placeholderColor = color;
        return this;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }
        String value = getValue();
        int x = getX();
        int y = getY();
        if (value.isEmpty() && !isFocused() && placeholder != null) {
            graphics.drawString(font, placeholder, x, y, placeholderColor, false);
            return;
        }
        graphics.drawString(font, JournalSkin.text(Component.literal(value)), x, y, ink, false);
        if (isFocused() && Util.getMillis() / 500 % 2 == 0) {
            int cursor = x + font.width(value.substring(0, Math.min(getCursorPosition(), value.length())));
            graphics.fill(cursor, y - 1, cursor + 1, y + 9, ink);
        }
    }
}
