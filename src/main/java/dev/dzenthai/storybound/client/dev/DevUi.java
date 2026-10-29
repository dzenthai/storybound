package dev.dzenthai.storybound.client.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dzenthai.storybound.Storybound;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Layout, colours, texts and drawing shared by the dev mode screen and its views. */
final class DevUi {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static final int LEFT_PANEL_W = 148;
    static final int RIGHT_PANEL_W = 220;
    static final int TOP_BAR_H = 24;

    static final int BACKGROUND = 0xFF141619;
    static final int BAR = 0xFF1E2229;
    static final int PANEL = 0xFF1B1E24;
    static final int PANEL_EDGE = 0xFF343A46;
    static final int ROW_SELECTED = 0xFF2D3748;
    static final int ROW_HOVER = 0xFF252A34;
    static final int CARD = 0xFF21252B;
    static final int CARD_EDGE = 0xFF37474F;
    static final int DARK_TEXT = 0xFF111317;
    static final int ACCENT = 0xFFE8C66A;
    static final int TEXT = 0xFFE0E6ED;
    static final int LABEL = 0xFFB0B8C4;
    static final int MUTED = 0xFF90A4AE;
    static final int HINT = 0xFF8C96A6;
    static final int DETAIL = 0xFF80CBC4;
    static final int OK = 0xFFA8E6A3;
    static final int GOOD = 0xFF66BB6A;
    static final int WARN = 0xFFFFD54F;
    static final int BAD = 0xFFFF8A80;
    static final int ERROR = 0xFFFF5252;
    static final int LINK = 0xFF69F0AE;
    static final int NEUTRAL = 0xFFB0BEC5;

    private DevUi() {}

    /** A dev mode text: the key {@code storybound.dev.<key>}. */
    static Component text(String key, Object... args) {
        return Component.translatable("storybound.dev." + key, args);
    }

    static String str(String key, Object... args) {
        return text(key, args).getString();
    }

    static String yesNo(boolean value) {
        return str(value ? "yes" : "no");
    }

    static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    /** A type id or a short one ({@code quest} is {@code storybound:quest}); null when it is not an id. */
    static @Nullable ResourceLocation normalizeType(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? ResourceLocation.tryParse(lower) : Storybound.id(lower);
    }

    /** Our namespace left out, as authors usually write ids. */
    static String shortId(String raw) {
        return raw.startsWith("storybound:") ? raw.substring("storybound:".length()) : raw;
    }

    /** {@code trial_chambers} → {@code Trial Chambers}, for registry entries without a translation. */
    static String humanize(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("[_/]")) {
            if (!part.isEmpty()) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.isEmpty() ? path : out.toString();
    }

    static void sidePanel(GuiGraphics graphics, int left, int right, int bottom, boolean edgeOnRight) {
        graphics.fill(left, TOP_BAR_H, right, bottom, PANEL);
        int edge = edgeOnRight ? right - 1 : left;
        graphics.fill(edge, TOP_BAR_H, edge + 1, bottom, PANEL_EDGE);
    }

    /** A clickable row of a side panel's list. */
    static void listRow(GuiGraphics graphics, Font font, String label, int y, boolean selected, boolean hover) {
        int background = selected ? ROW_SELECTED : hover ? ROW_HOVER : 0;
        if (background != 0) {
            graphics.fill(4, y, LEFT_PANEL_W - 4, y + 18, background);
        }
        int color = selected ? WARN : hover ? 0xFFFFFFFF : LABEL;
        graphics.drawString(font, font.plainSubstrByWidth(label, LEFT_PANEL_W - 16), 8, y + 5, color, false);
    }

    /** The canvas grid, moving with the pan and scaled with the zoom. */
    static void grid(GuiGraphics graphics, int left, int top, int right, int bottom, double panX, double panY, float zoom, int color) {
        int step = Math.max(12, Math.round(24 * zoom));
        int offsetX = ((int) Math.round(panX) % step + step) % step;
        int offsetY = ((int) Math.round(panY) % step + step) % step;
        for (int x = left + offsetX; x < right; x += step) {
            graphics.fill(x, top, x + 1, bottom, color);
        }
        for (int y = top + offsetY; y < bottom; y += step) {
            graphics.fill(left, y, right, y + 1, color);
        }
    }

    /** A node on a canvas: a coloured header with the badge, an icon, the title and the id, and the link port on the right. */
    static void card(GuiGraphics graphics, Font font, int x, int y, int width, int height, int border, boolean thick, int color,
                     String badge, boolean missingTranslation, ItemStack icon, String title, String subtitle) {
        int pad = thick ? 2 : 1;
        graphics.fill(x - pad, y - pad, x + width + pad, y + height + pad, border);
        graphics.fill(x, y, x + width, y + height, CARD);
        graphics.fill(x, y, x + width, y + 12, color);
        graphics.drawString(font, font.plainSubstrByWidth(badge, width - 20), x + 4, y + 2, DARK_TEXT, false);
        if (missingTranslation) {
            graphics.fill(x + width - 14, y + 2, x + width - 6, y + 10, WARN);
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, -140);
        graphics.renderItem(icon, x + 4, y + 18);
        graphics.pose().popPose();
        graphics.drawString(font, font.plainSubstrByWidth(title, width - 28), x + 24, y + 17, 0xFFF5F5F5, false);
        graphics.drawString(font, font.plainSubstrByWidth(subtitle, width - 28), x + 24, y + 28, MUTED, false);
        int portX = x + width - 3;
        int portY = y + height / 2 - 3;
        graphics.fill(portX - 1, portY - 1, portX + 7, portY + 7, DARK_TEXT);
        graphics.fill(portX, portY, portX + 6, portY + 6, WARN);
    }

    /** Whether a canvas point is on a card's link port. */
    static boolean onPort(double x, double y, int cardX, int cardY, int width, int height) {
        int portX = cardX + width - 6;
        int portY = cardY + height / 2 - 6;
        return x >= portX && x <= portX + 12 && y >= portY && y <= portY + 12;
    }

    /** An arrow in three straight segments; parallel arrows get their own lane so they stay apart. */
    static void edge(GuiGraphics graphics, Font font, int x1, int y1, int x2, int y2, int color, String label, int index) {
        int lane = ((index % 5) - 2) * 5;
        int midX = x2 >= x1 ? Math.min(x1 + 24 + lane, (x1 + x2) / 2 + lane) : (x1 + x2) / 2 + lane;
        horizontal(graphics, x1, midX, y1, color);
        vertical(graphics, midX, y1, y2, color);
        horizontal(graphics, midX, x2, y2, color);
        graphics.fill(x2 - 5, y2 - 3, x2, y2 + 3, color);
        if (!label.isEmpty()) {
            int width = font.width(label);
            int labelX = midX - width / 2;
            int labelY = (y1 + y2) / 2 - 4 + ((index % 3) - 1) * 7;
            graphics.fill(labelX - 2, labelY - 1, labelX + width + 2, labelY + 9, 0xDD141619);
            graphics.drawString(font, label, labelX, labelY, color == WARN ? 0xFFFFE082 : NEUTRAL, false);
        }
    }

    static void horizontal(GuiGraphics graphics, int x1, int x2, int y, int color) {
        graphics.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 2, color);
    }

    static void vertical(GuiGraphics graphics, int x, int y1, int y2, int color) {
        graphics.fill(x, Math.min(y1, y2), x + 2, Math.max(y1, y2) + 1, color);
    }

    /** The round button on the inspector's edge that folds it away. */
    /**
     * The round button that hides the side panel, centred on the panel's edge ({@code cx}). Small enough to stay off
     * the panel's labels, which start 8 pixels in (October 30, 2026; before, a radius of 11, over them).
     */
    static void collapseButton(GuiGraphics graphics, int cx, int cy, int mouseX, int mouseY) {
        int r = COLLAPSE_R;
        boolean hover = onCollapseButton(mouseX, mouseY, cx, cy);
        int border = hover ? ACCENT : 0xFF3A4150;
        int fill = hover ? ROW_HOVER : 0xFF16191F;
        int icon = hover ? 0xFFFFFFFF : MUTED;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 150);
        disc(graphics, cx, cy, r, border);
        disc(graphics, cx, cy, r - 1, fill);
        // ">>" in two small chevrons.
        for (int i = -2; i <= 2; i++) {
            int offset = 2 - Math.abs(i);
            graphics.fill(cx - 3 + offset, cy + i, cx - 2 + offset, cy + i + 1, icon);
            graphics.fill(cx + offset, cy + i, cx + 1 + offset, cy + i + 1, icon);
        }
        graphics.pose().popPose();
    }

    static boolean onCollapseButton(double mouseX, double mouseY, int cx, int cy) {
        double dx = mouseX - cx;
        double dy = mouseY - cy;
        // A little larger than drawn, so a small button is still easy to hit.
        return dx * dx + dy * dy <= (COLLAPSE_R + 2) * (COLLAPSE_R + 2);
    }

    private static final int COLLAPSE_R = 7;

    private static void disc(GuiGraphics graphics, int cx, int cy, int r, int color) {
        for (int y = -r; y <= r; y++) {
            int half = (int) Math.floor(Math.sqrt(r * r - y * y));
            graphics.fill(cx - half, cy + y, cx + half + 1, cy + y + 1, color);
        }
    }
}
