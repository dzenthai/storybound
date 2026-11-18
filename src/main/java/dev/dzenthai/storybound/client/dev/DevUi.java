package dev.dzenthai.storybound.client.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dzenthai.storybound.Storybound;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Layout, colours, texts and drawing shared by the dev mode screen and its views. */
final class DevUi {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static final int LEFT_PANEL_W = 148;
    static final int RIGHT_PANEL_W = 220;
    static final int TOP_BAR_H = 24;

    static final int BACKGROUND = 0xFF1A140F;
    static final int BAR = 0xFF2A1E15;
    static final int PANEL = 0xFF2A1E15;
    static final int PANEL_EDGE = 0xFF3A2A1C;
    static final int ROW_SELECTED = 0xFF483020;
    static final int ROW_HOVER = 0xFF3A2A1C;
    static final int CARD = 0xFF2A1E15;
    static final int CARD_EDGE = 0xFF483020;
    static final int DARK_TEXT = 0xFF1A140F;
    static final int ACCENT = 0xFFE1B34D;
    static final int TEXT = 0xFFF5DFB2;
    static final int LABEL = 0xFFCDA269;
    static final int MUTED = 0xFFB78957;
    static final int HINT = 0xFFAF7846;
    static final int DETAIL = 0xFF92AACC;
    static final int OK = 0xFFA5B574;
    static final int GOOD = 0xFFA5B574;
    static final int WARN = 0xFFFFE08A;
    static final int BAD = 0xFFD9785A;
    static final int ERROR = 0xFFD9785A;
    static final int LINK = 0xFF92AACC;
    static final int NEUTRAL = 0xFFB2B4B6;

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
        int color = selected ? WARN : hover ? TEXT : LABEL;
        graphics.drawString(font, font.plainSubstrByWidth(label, LEFT_PANEL_W - 16), 8, y + 5, color, false);
    }

    /** {@code color} laid over {@code under} at {@code amount} (0 to 1), opaque: for dimmed and glowing shapes. */
    static int blend(int color, int under, float amount) {
        int r = Math.round(((under >> 16) & 255) + (((color >> 16) & 255) - ((under >> 16) & 255)) * amount);
        int g = Math.round(((under >> 8) & 255) + (((color >> 8) & 255) - ((under >> 8) & 255)) * amount);
        int b = Math.round((under & 255) + ((color & 255) - (under & 255)) * amount);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** A cell of the canvas grid, in canvas units. */
    static final int GRID = 24;
    /** Where nodes snap to: a line of the grid or the middle of a cell (November 18, 2026; before, 8 against lines every 24). */
    static final int SNAP = GRID / 2;

    /** A canvas position on the snap grid. */
    static int snap(double value) {
        return (int) Math.round(value / SNAP) * SNAP;
    }

    /**
     * The canvas grid over the canvas area {@code minX..maxX}, {@code minY..maxY}, in canvas units, drawn in the
     * canvas's own coordinates (November 18, 2026): a line at {@code x} is filled just as a card's left edge at
     * {@code x} is, so at any zoom and pan a snapped card's frame lies on the lines. Before, the lines were placed in
     * screen pixels with a rounded step and pan, and drifted off the cards away from the middle. Every second line goes
     * while the cells would be under 10 pixels; at the farthest zooms a line is two units wide so it does not vanish.
     */
    static void grid(GuiGraphics graphics, double minX, double minY, double maxX, double maxY, float zoom, int color) {
        int step = GRID;
        while (step * zoom < 10) {
            step *= 2;
        }
        int width = zoom < 0.5f ? 2 : 1;
        int left = (int) Math.floor(minX);
        int top = (int) Math.floor(minY);
        int right = (int) Math.ceil(maxX);
        int bottom = (int) Math.ceil(maxY);
        for (int x = Math.floorDiv(left, step) * step; x <= right; x += step) {
            graphics.fill(x, top, x + width, bottom, color);
        }
        for (int y = Math.floorDiv(top, step) * step; y <= bottom; y += step) {
            graphics.fill(left, y, right, y + width, color);
        }
    }

    static void horizontal(GuiGraphics graphics, int x1, int x2, int y, int color) {
        graphics.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 2, color);
    }

    static void vertical(GuiGraphics graphics, int x, int y1, int y2, int color) {
        graphics.fill(x, Math.min(y1, y2), x + 2, Math.max(y1, y2) + 1, color);
    }

    /**
     * A frame one pixel wide inside the box (November 17, 2026: buttons and chapter slots drew theirs with
     * {@link #horizontal} and {@link #vertical}, two pixels wide for the canvas's lines, so the right and bottom edges
     * stood one pixel outside and everything in the box looked pushed to the left).
     */
    static void frame(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /** The round button on the inspector's edge that folds it away. */
    /**
     * The round button that hides the side panel, centred on the panel's edge ({@code cx}). Small enough to stay off
     * the panel's labels, which start 8 pixels in (October 30, 2026; before, a radius of 11, over them).
     */
    static void collapseButton(GuiGraphics graphics, int cx, int cy, int mouseX, int mouseY) {
        int r = COLLAPSE_R;
        boolean hover = onCollapseButton(mouseX, mouseY, cx, cy);
        int border = hover ? ACCENT : CARD_EDGE;
        int fill = hover ? ROW_HOVER : BACKGROUND;
        int icon = hover ? TEXT : MUTED;
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
