package dev.dzenthai.storybound.client.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * The dev mode's button (November 17, 2026; before, Minecraft's stone button, which the author found heavy): flat, the
 * dark of the palette with a thin edge, gold on hover, its glyph drawn as an icon ({@link DevIcons}). Every button the
 * editor adds becomes one ({@link DevModeScreen#add}): it presses the original and reads its words, its tooltip and
 * whether it is active every frame, so code that changes the original later still works.
 */
final class DevButton extends Button {
    private final Button original;

    DevButton(Button original) {
        super(original.getX(), original.getY(), original.getWidth(), original.getHeight(), original.getMessage(),
                button -> original.onPress(), DEFAULT_NARRATION);
        this.original = original;
        setTooltip(original.getTooltip());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        active = original.active;
        visible = original.visible;
        if (!getMessage().equals(original.getMessage())) {
            setMessage(original.getMessage());
        }
        if (getTooltip() != original.getTooltip()) {
            setTooltip(original.getTooltip());
        }
        boolean hover = active && isHoveredOrFocused();
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        graphics.fill(x, y, x + w, y + h, !active ? DevUi.PANEL : hover ? DevUi.ROW_HOVER : DevUi.CARD);
        DevUi.frame(graphics, x, y, w, h, hover ? DevUi.ACCENT : DevUi.CARD_EDGE);

        Font font = Minecraft.getInstance().font;
        Component message = getMessage();
        TextColor own = message.getStyle().getColor();
        int textColor = !active ? DevUi.MUTED : own != null ? 0xFF000000 | own.getValue() : hover ? DevUi.WARN : DevUi.TEXT;
        int iconColor = !active ? DevUi.MUTED : own != null ? textColor : hover ? DevUi.ACCENT : DevUi.LABEL;
        String label = message.getString();
        DevIcons.Split split = DevIcons.split(label);
        int room = w - 6;
        String text = split.text();
        int iconW = split.icon() == null ? 0 : DevIcons.SIZE + (text.isEmpty() ? 0 : DevIcons.GAP);
        // Words too long are cut with "…"; an icon alone is never cut, and no "…" is added to no words (November 19,
        // 2026: a 12-wide ✕ got a "…" after its icon, which ran out of the button).
        if (!text.isEmpty() && font.width(text) > room - iconW) {
            int fit = room - iconW - font.width("…");
            text = fit > 0 ? font.plainSubstrByWidth(text, fit) + "…" : "";
            if (text.isEmpty() && split.icon() != null) {
                iconW = DevIcons.SIZE;
            }
        }
        int contentW = iconW + font.width(text);
        int left = x + (w - contentW) / 2;
        // The icon in the middle of the height; the letters (7 high above their line) centred on it.
        int iconY = y + (h - DevIcons.SIZE) / 2;
        int textY = split.icon() != null ? iconY + 1 : y + (h - 7) / 2;
        if (split.icon() != null) {
            DevIcons.draw(graphics, split.icon(), left, iconY, iconColor);
        }
        graphics.drawString(font, text, left + iconW, textY, textColor, false);
    }

    /**
     * A width for a button of the label that keeps a 9-pixel icon exactly in the middle: odd, at least 19, the label
     * with room around it.
     */
    static int oddWidth(Font font, Component label) {
        int w = Math.max(19, DevIcons.width(font, label.getString()) + 10);
        return w % 2 == 0 ? w + 1 : w;
    }
}
