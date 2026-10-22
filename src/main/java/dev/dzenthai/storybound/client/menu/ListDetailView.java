package dev.dzenthai.storybound.client.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * The common tab layout: a list of entries grouped under headers on the left, the selected entry
 * on the right, and a row for buttons at the bottom right. Rows are rebuilt whenever
 * {@link #dataVersion()} changes; the selection is kept by {@link #key}.
 */
public abstract class ListDetailView<E> extends MenuView {
    protected static final int ROW_HEIGHT = 11;
    protected static final int LINE_HEIGHT = 10;
    protected static final int BUTTON_ROW = 22;

    private List<Row<E>> rows = List.of();
    private List<Line> detail = List.of();
    private @Nullable E selected;
    private int version = Integer.MIN_VALUE;
    private int listScroll;
    private int detailScroll;

    /** Changes whenever the data behind the rows may have changed. */
    protected abstract int dataVersion();

    protected abstract List<Row<E>> buildRows();

    /** A stable id of the entry; also its "seen" key. */
    protected abstract String key(E entry);

    protected abstract void buildDetail(E entry, Detail detail);

    protected abstract Component emptyText();

    /** The entry to select when nothing is selected yet. */
    protected @Nullable E preferredSelection(List<E> entries) {
        return entries.isEmpty() ? null : entries.getFirst();
    }

    protected boolean isUnseen(E entry) {
        return false;
    }

    protected void markSeen(E entry) {}

    /** Called after the selection or the data changed; update buttons here. */
    protected void selectionChanged() {}

    protected final @Nullable E selected() {
        return selected;
    }

    @Override
    protected void init() {
        version = Integer.MIN_VALUE;
        refresh();
    }

    @Override
    public void tick() {
        if (dataVersion() != version) {
            refresh();
        }
    }

    private void refresh() {
        version = dataVersion();
        rows = buildRows();
        List<E> entries = rows.stream().map(Row::entry).filter(Objects::nonNull).toList();
        String selectedKey = selected != null ? key(selected) : null;
        selected = entries.stream().filter(entry -> key(entry).equals(selectedKey)).findFirst().orElse(null);
        if (selected == null) {
            selected = preferredSelection(entries);
            detailScroll = 0;
        }
        if (selected != null) {
            markSeen(selected);
        }
        rebuildDetail();
        selectionChanged();
    }

    private void select(E entry) {
        selected = entry;
        detailScroll = 0;
        markSeen(entry);
        rebuildDetail();
        selectionChanged();
    }

    /** Selects the entry with that key when the rows have it, e.g. when another tab opens this one on it. */
    protected final void selectKey(String key) {
        rows.stream().map(Row::entry).filter(Objects::nonNull).filter(entry -> key(entry).equals(key)).findFirst().ifPresent(this::select);
    }

    /** Builds the detail again after the view's own state changed (a choice), and updates the buttons. */
    protected final void refreshDetail() {
        rebuildDetail();
        selectionChanged();
    }

    private void rebuildDetail() {
        Detail builder = new Detail(font, detailWidth());
        if (selected != null) {
            buildDetail(selected, builder);
        }
        detail = builder.lines;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuTheme theme = theme();
        graphics.fill(left + listWidth() + 4, top, left + listWidth() + 5, top + height, theme.divider());
        if (rows.isEmpty()) {
            Component empty = emptyText();
            graphics.drawString(font, empty, left + (width - font.width(empty)) / 2, top + height / 2 - 4, theme.ink(), false);
            return;
        }
        renderList(graphics, theme, mouseX, mouseY);
        renderDetail(graphics, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphics graphics, MenuTheme theme, int mouseX, int mouseY) {
        int visible = height / ROW_HEIGHT;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rows.size() - visible)));
        graphics.enableScissor(left, top, left + listWidth(), top + height);
        for (int i = listScroll; i < rows.size() && i < listScroll + visible; i++) {
            Row<E> row = rows.get(i);
            int y = top + (i - listScroll) * ROW_HEIGHT;
            if (row.entry() == null) {
                graphics.drawString(font, row.text().copy().withStyle(ChatFormatting.BOLD), left, y + 1, row.color() != 0 ? row.color() : theme.accent(), false);
                continue;
            }
            boolean hovered = mouseX >= left && mouseX < left + listWidth() && mouseY >= y && mouseY < y + ROW_HEIGHT;
            boolean isSelected = row.entry() == selected;
            if (isSelected || hovered) {
                graphics.fill(left, y, left + listWidth(), y + ROW_HEIGHT, isSelected ? theme.selection() : theme.hover());
            }
            if (isUnseen(row.entry())) {
                graphics.drawString(font, "•", left + 1, y + 1, theme.unseen(), false);
            }
            String text = font.plainSubstrByWidth(row.text().getString(), listWidth() - 8);
            graphics.drawString(font, text, left + 6, y + 1, row.color() != 0 ? row.color() : theme.ink(), false);
        }
        graphics.disableScissor();
    }

    private void renderDetail(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = detailX();
        int bottom = top + height - BUTTON_ROW;
        int contentHeight = detail.stream().mapToInt(Line::height).sum();
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, contentHeight - (bottom - top))));
        graphics.enableScissor(x, top, x + detailWidth(), bottom);
        int y = top - detailScroll;
        for (Line line : detail) {
            if (line.block() != null && y + line.height() > top && y < bottom) {
                line.block().render(graphics, x, y, detailWidth(), mouseX, mouseY, partialTick);
            } else if (line.text() != null && y + line.height() > top && y < bottom) {
                if (line.click() != null && mouseX >= x && mouseX < x + detailWidth() && mouseY >= y && mouseY < y + line.height()) {
                    graphics.fill(x - 2, y - 1, x + detailWidth(), y + line.height() - 1, theme().hover());
                }
                if (line.prefix() != null) {
                    graphics.drawString(font, line.prefix(), x, y, line.color(), false);
                }
                graphics.drawString(font, line.text(), x + line.indent(), y, line.color(), false);
            }
            y += line.height();
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= detailX() && mouseX < detailX() + detailWidth() && mouseY >= top && mouseY < top + height - BUTTON_ROW) {
            int y = top - detailScroll;
            for (Line line : detail) {
                if (line.click() != null && mouseY >= y && mouseY < y + line.height()) {
                    line.click().run();
                    return true;
                }
                y += line.height();
            }
        }
        if (mouseX >= left && mouseX < left + listWidth() && mouseY >= top && mouseY < top + height) {
            int index = listScroll + (int) ((mouseY - top) / ROW_HEIGHT);
            if (index >= 0 && index < rows.size() && rows.get(index).entry() != null) {
                select(rows.get(index).entry());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < detailX()) {
            listScroll -= (int) Math.signum(scrollY) * 2;
        } else {
            detailScroll -= (int) Math.signum(scrollY) * 2 * LINE_HEIGHT;
        }
        return true;
    }

    protected int listWidth() {
        return Math.max(110, width * 3 / 8);
    }

    protected int detailX() {
        return left + listWidth() + 10;
    }

    protected int detailWidth() {
        return left + width - detailX();
    }

    /** Bottom-right corner of the panel, for buttons. */
    protected int buttonY() {
        return top + height - BUTTON_ROW + 4;
    }

    /**
     * A row of the list.
     *
     * @param entry null for a group header
     * @param color 0 for the theme's default
     */
    public record Row<E>(Component text, @Nullable E entry, int color) {
        public static <E> Row<E> header(Component text) {
            return new Row<>(text, null, 0);
        }

        public static <E> Row<E> entry(Component text, E entry) {
            return new Row<>(text, entry, 0);
        }
    }

    /** @param click what clicking the line does (a choice); null for text */
    private record Line(@Nullable String prefix, @Nullable FormattedCharSequence text, int indent, int color, int height, @Nullable Block block,
                        @Nullable Runnable click) {
        Line(@Nullable String prefix, @Nullable FormattedCharSequence text, int indent, int color, int height) {
            this(prefix, text, indent, color, height, null, null);
        }
    }

    /** Something drawn in the detail pane besides text: a portrait, item icons, an image. */
    @FunctionalInterface
    public interface Block {
        void render(GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY, float partialTick);
    }

    /** Collects the wrapped lines of the detail pane. */
    public static final class Detail {
        private final Font font;
        private final int width;
        private final List<Line> lines = new ArrayList<>();

        Detail(Font font, int width) {
            this.font = font;
            this.width = width;
        }

        public Detail text(Component text, int color) {
            for (FormattedCharSequence line : font.split(text, width)) {
                lines.add(new Line(null, line, 0, color, LINE_HEIGHT));
            }
            return this;
        }

        /** A line with a prefix (a bullet, a checkbox) and the text wrapped beside it. */
        public Detail item(String prefix, Component text, int color) {
            int indent = font.width(prefix + " ");
            List<FormattedCharSequence> wrapped = font.split(text, width - indent);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new Line(i == 0 ? prefix : null, wrapped.get(i), indent, color, LINE_HEIGHT));
            }
            return this;
        }

        /** An {@link #item} the player can click (a choice); it is highlighted under the mouse. */
        public Detail clickable(String prefix, Component text, int color, Runnable click) {
            int indent = font.width(prefix + " ");
            List<FormattedCharSequence> wrapped = font.split(text, width - indent);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new Line(i == 0 ? prefix : null, wrapped.get(i), indent, color, LINE_HEIGHT, null, click));
            }
            return this;
        }

        /** Text under an {@link #item} with the same prefix, aligned with that item's text. */
        public Detail note(String prefix, Component text, int color) {
            int indent = font.width(prefix + " ");
            for (FormattedCharSequence line : font.split(text, width - indent)) {
                lines.add(new Line(null, line, indent, color, LINE_HEIGHT));
            }
            return this;
        }

        /** An already wrapped line, e.g. from {@code .sbt} layout. */
        public Detail line(FormattedCharSequence text, int color, int height) {
            lines.add(new Line(null, text, 0, color, height));
            return this;
        }

        public Detail block(int height, Block block) {
            lines.add(new Line(null, null, 0, 0, height, block, null));
            return this;
        }

        public int width() {
            return width;
        }

        public Font font() {
            return font;
        }

        public Detail gap(int pixels) {
            lines.add(new Line(null, null, 0, 0, pixels));
            return this;
        }
    }
}
