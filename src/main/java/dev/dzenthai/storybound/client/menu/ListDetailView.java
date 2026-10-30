package dev.dzenthai.storybound.client.menu;

import dev.dzenthai.storybound.client.document.ScrollArrows;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
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
    /**
     * Where the list (in rows) and the detail (in units) show now, following {@link #listScroll} and {@link #detailScroll}
     * smoothly, as the documents do (October 31, 2026; before, in jumps).
     */
    private double shownList;
    private double shownDetail;
    private long lastFrame;
    private float frame;
    /** The scroll arrows' fade, and the room they take over and under the list and the detail (0 when it all fits). */
    private float listUp;
    private float listDown;
    private float detailUp;
    private float detailDown;
    private int listRoom;
    private int listMax;
    private int detailMax;
    private int detailRoom;
    private @Nullable Component rowTooltip;

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

    /** The same metric is used for drawing, scrolling and row hit testing. */
    protected int rowHeight() {
        if (screen.bookLayout() != null) return 22;
        return MenuThemes.INSTANCE.archiveChrome() ? 20 : ROW_HEIGHT;
    }

    protected final @Nullable E selected() {
        return selected;
    }

    /** A decoration may use spare space, but never displace or obscure the entry. */
    protected void renderDetailRemainder(GuiGraphics graphics, int x, int y, int width, int height) {}

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
            shownDetail = 0;
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
        shownDetail = 0;
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
        if (screen.bookLayout() == null && MenuThemes.INSTANCE.archiveChrome()) {
            graphics.fill(left - 5, top - 5, left + listWidth() + 1, top + height + 5, 0x30000000);
            graphics.fill(left + listWidth() + 5, top - 5, left + listWidth() + 7, top + height + 5, 0x55000000);
        }
        if (screen.bookLayout() == null) {
            graphics.fill(left + listWidth() + 4, top, left + listWidth() + 5, top + height, theme.divider());
        }
        if (rows.isEmpty()) {
            Component empty = JournalSkin.text(emptyText());
            int emptyY = top;
            for (var line : font.split(empty, detailWidth())) {
                graphics.drawString(font, line, detailX(), emptyY, theme.ink(), false);
                emptyY += 13;
            }
            return;
        }
        renderList(graphics, theme, mouseX, mouseY);
        renderDetail(graphics, mouseX, mouseY, partialTick);
        if (rowTooltip != null) graphics.renderTooltip(font, rowTooltip, mouseX, mouseY);
    }

    private void renderList(GuiGraphics graphics, MenuTheme theme, int mouseX, int mouseY) {
        rowTooltip = null;
        long now = Util.getMillis();
        frame = lastFrame == 0 ? 0 : Math.min(100, now - lastFrame);
        lastFrame = now;
        int rowHeight = rowHeight();
        // A list longer than the page: arrows in the middle over and under it, in the room they take (before October
        // 31, 2026, a scrollbar at its right).
        listRoom = screen.bookLayout() != null && rows.size() * rowHeight > height ? arrowRoom() : 0;
        int viewTop = top + listRoom;
        int viewHeight = height - 2 * listRoom;
        int visible = Math.max(1, viewHeight / rowHeight);
        int maxScroll = Math.max(0, rows.size() - visible);
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));
        listMax = maxScroll;
        shownList = ease(shownList, listScroll);
        graphics.enableScissor(left, viewTop, left + listWidth(), viewTop + viewHeight);
        int first = (int) Math.floor(shownList);
        for (int i = Math.max(0, first); i < rows.size() && i <= first + visible; i++) {
            Row<E> row = rows.get(i);
            int y = viewTop + (int) Math.round((i - shownList) * rowHeight);
            boolean hovered = mouseX >= left && mouseX < left + listWidth() && mouseY >= Math.max(y, viewTop) && mouseY < Math.min(y + rowHeight, viewTop + viewHeight);
            renderListRow(graphics, theme, row, y, row.entry() != null && row.entry() == selected, hovered);
        }
        graphics.disableScissor();
        if (listRoom > 0) {
            listUp = fade(listUp, listScroll > 0);
            listDown = fade(listDown, listScroll < maxScroll);
            drawArrows(graphics, left + listWidth() / 2.0, top, top + height, listUp, listDown, mouseX, mouseY);
        }
    }

    /** The room an arrow takes over or under a text, in the menu's units. */
    private int arrowRoom() {
        return (int) Math.ceil(ScrollArrows.room() * screen.bookLayout().bookPixel());
    }

    private double ease(double shown, int target) {
        double next = shown + (target - shown) * (1 - Math.exp(-frame / 70.0));
        return Math.abs(target - next) < 0.01 ? target : next;
    }

    private float fade(float alpha, boolean shown) {
        return Mth.clamp(alpha + (shown ? 1 : -1) * frame / 150f, 0, 1);
    }

    private void drawArrows(GuiGraphics graphics, double centerX, int top, int bottom, float up, float down, int mouseX, int mouseY) {
        double pixel = screen.bookLayout().bookPixel();
        double downTop = bottom - ScrollArrows.HEIGHT * pixel;
        ScrollArrows.draw(graphics, centerX, top, pixel, true, up, ScrollArrows.contains(centerX, top, pixel, mouseX, mouseY));
        ScrollArrows.draw(graphics, centerX, downTop, pixel, false, down, ScrollArrows.contains(centerX, downTop, pixel, mouseX, mouseY));
    }

    /** -1 for the upward arrow, 1 for the downward one, 0 for neither (or one that is hidden). */
    private int arrowAt(double centerX, int top, int bottom, boolean canUp, boolean canDown, double mouseX, double mouseY) {
        double pixel = screen.bookLayout().bookPixel();
        if (canUp && ScrollArrows.contains(centerX, top, pixel, mouseX, mouseY)) {
            return -1;
        }
        return canDown && ScrollArrows.contains(centerX, bottom - ScrollArrows.HEIGHT * pixel, pixel, mouseX, mouseY) ? 1 : 0;
    }

    /** Presentation hook; row selection and data handling remain in the common view. */
    protected void renderListRow(GuiGraphics graphics, MenuTheme theme, Row<E> row, int y, boolean isSelected, boolean hovered) {
        int textY = y + Math.max(1, (rowHeight() - (screen.bookLayout() != null ? 7 : font.lineHeight)) / 2);
        if (screen.bookLayout() != null) {
            Component text = row.entry() == null ? JournalSkin.heading(row.text()) : JournalSkin.text(row.text());
            int inset = row.entry() == null ? 0 : entryTextInset(row.entry());
            int available = Math.max(1, listWidth() - inset - 3);
            if (hovered && font.width(text) > available) rowTooltip = text;
            if (row.entry() == null) {
                // As long and thick as the title's rule over the list, on the book's pixels (before, 3 shorter).
                JournalSkin.bookRule(graphics, screen.bookLayout(), left, y + rowHeight() - 2, listWidth(), theme.divider());
            } else {
                if (hovered) graphics.fill(left, y, left + listWidth(), y + rowHeight(), theme.hover());
                if (isSelected) {
                    graphics.fill(left, y + 1, left + listWidth(), y + rowHeight() - 1, theme.selection());
                }
                renderEntryMarker(graphics, row.entry(), left + 1, textY, isSelected);
            }
            int color = row.entry() == null ? theme.accent() : row.color() != 0 ? row.color() : theme.ink();
            graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(text, available)), left + inset, textY, color, false);
            return;
        }
        if (row.entry() == null) {
            graphics.drawString(font, row.text().copy().withStyle(ChatFormatting.BOLD), left, textY, row.color() != 0 ? row.color() : theme.accent(), false);
            if (MenuThemes.INSTANCE.archiveChrome()) {
                graphics.fill(left, y + rowHeight() - 2, left + listWidth() - 4, y + rowHeight() - 1, 0x405E5145);
            }
            return;
        }
        if (isSelected || hovered) {
            graphics.fill(left, y, left + listWidth(), y + rowHeight(), isSelected ? theme.selection() : theme.hover());
            if (isSelected && MenuThemes.INSTANCE.archiveChrome()) {
                graphics.fill(left, y + 3, left + 1, y + rowHeight() - 3, theme.accent());
            }
        }
        if (isUnseen(row.entry())) {
            graphics.drawString(font, "•", left + 1, textY, theme.unseen(), false);
        }
        String text = font.plainSubstrByWidth(row.text().getString(), listWidth() - 8);
        graphics.drawString(font, text, left + 6, textY, row.color() != 0 ? row.color() : theme.ink(), false);
    }

    protected int entryTextInset(E entry) {
        return 11;
    }

    protected void renderEntryMarker(GuiGraphics graphics, E entry, int x, int y, boolean selected) {
        if (selected) {
            graphics.fill(x + 1, y, x + 3, y + 7, 0xFF65883D);
            graphics.fill(x + 3, y + 2, x + 5, y + 5, 0xFF65883D);
        } else if (isUnseen(entry)) {
            JournalSkin.star(graphics, x, y, theme().unseen());
        }
    }

    private void renderDetail(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = detailX();
        int bottom = detailBottom();
        int top = detailTop();
        int contentHeight = detail.stream().mapToInt(Line::height).sum();
        detailRoom = screen.bookLayout() != null && contentHeight > bottom - top ? arrowRoom() : 0;
        int viewTop = top + detailRoom;
        int viewBottom = bottom - detailRoom;
        int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        detailScroll = Math.max(0, Math.min(detailScroll, maxScroll));
        detailMax = maxScroll;
        shownDetail = ease(shownDetail, detailScroll);
        graphics.enableScissor(x, viewTop, x + detailWidth(), viewBottom);
        int y = viewTop - (int) Math.round(shownDetail);
        for (Line line : detail) {
            if (line.block() != null && y + line.height() > viewTop && y < viewBottom) {
                line.block().render(graphics, x, y, detailWidth(), mouseX, mouseY, partialTick);
            } else if (line.text() != null && y + line.height() > viewTop && y < viewBottom) {
                if (line.click() != null && mouseX >= x && mouseX < x + detailWidth() && mouseY >= y && mouseY < y + line.height()) {
                    graphics.fill(x - 2, y - 1, x + detailWidth(), y + line.height() - 1, theme().hover());
                }
                if (line.prefix() != null) {
                    graphics.drawString(font, JournalSkin.text(Component.literal(line.prefix())), x, y, line.color(), false);
                }
                graphics.drawString(font, line.text(), x + line.indent(), y, line.color(), false);
            }
            y += line.height();
        }
        if (contentHeight < bottom - top) {
            renderDetailRemainder(graphics, x, top + contentHeight + 6, detailWidth(), bottom - top - contentHeight - 6);
        }
        graphics.disableScissor();
        if (detailRoom > 0) {
            detailUp = fade(detailUp, detailScroll > 0);
            detailDown = fade(detailDown, detailScroll < maxScroll);
            drawArrows(graphics, x + detailWidth() / 2.0, top, bottom, detailUp, detailDown, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The arrows first: they stand over and under the text they scroll.
        int listArrow = listRoom > 0 ? arrowAt(left + listWidth() / 2.0, top, top + height, listScroll > 0, listScroll < listMax, mouseX, mouseY) : 0;
        int detailArrow = detailRoom > 0 ? arrowAt(detailX() + detailWidth() / 2.0, detailTop(), detailBottom(), detailScroll > 0, detailScroll < detailMax, mouseX, mouseY) : 0;
        if (listArrow != 0 || detailArrow != 0) {
            if (listArrow != 0) {
                listScroll += listArrow * 2;
            } else {
                detailScroll += detailArrow * 3 * LINE_HEIGHT;
            }
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, 0.25f));
            return true;
        }
        if (mouseX >= detailX() && mouseX < detailX() + detailWidth() && mouseY >= detailTop() + detailRoom && mouseY < detailBottom() - detailRoom) {
            int y = detailTop() + detailRoom - (int) Math.round(shownDetail);
            for (Line line : detail) {
                if (line.click() != null && mouseY >= y && mouseY < y + line.height()) {
                    line.click().run();
                    return true;
                }
                y += line.height();
            }
        }
        if (mouseX >= left && mouseX < left + listWidth() && mouseY >= top + listRoom && mouseY < top + height - listRoom) {
            int index = (int) Math.floor((mouseY - top - listRoom) / rowHeight() + shownList);
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
        if (screen.bookLayout() != null) return screen.bookLayout().indexWidth();
        return Math.max(110, width * 3 / 8);
    }

    protected int detailX() {
        if (screen.bookLayout() != null) return screen.bookLayout().detailLeft();
        return left + listWidth() + 10;
    }

    protected int detailWidth() {
        return left + width - detailX();
    }

    protected int detailTop() {
        return screen.bookLayout() == null ? top : screen.bookLayout().y() + screen.bookLayout().height() * 17 / 100;
    }

    protected int detailBottom() {
        return top + height - buttonRowHeight();
    }

    /** Bottom-right corner of the panel, for buttons. */
    protected int buttonY() {
        return top + height - buttonRowHeight() + 4;
    }

    protected int buttonRowHeight() {
        return BUTTON_ROW;
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
        private final int lineHeight;
        private final List<Line> lines = new ArrayList<>();

        Detail(Font font, int width) {
            this.font = font;
            this.width = width;
            this.lineHeight = MenuThemes.INSTANCE.journalBook() ? 13 : MenuThemes.INSTANCE.archiveChrome() ? 13 : LINE_HEIGHT;
        }

        public Detail text(Component text, int color) {
            for (FormattedCharSequence line : font.split(JournalSkin.text(text), width)) {
                lines.add(new Line(null, line, 0, color, lineHeight));
            }
            return this;
        }

        /** One reward row, with a live item icon and a single written quantity. */
        public Detail reward(net.minecraft.world.item.ItemStack stack, int color) {
            Component label = JournalSkin.text(Component.literal(stack.getCount() + " × ").append(stack.getHoverName()));
            var wrapped = font.split(label, Math.max(1, width - 23));
            return block(Math.max(20, wrapped.size() * lineHeight + 4), (graphics, x, y, width, mouseX, mouseY, partialTick) -> {
                graphics.renderItem(stack, x, y);
                for (int i = 0; i < wrapped.size(); i++) {
                    graphics.drawString(font, wrapped.get(i), x + 23, y + 4 + i * lineHeight, color, false);
                }
            });
        }

        public Detail experience(Component text, int color) {
            var wrapped = font.split(JournalSkin.text(text), Math.max(1, width - 23));
            return block(Math.max(20, wrapped.size() * lineHeight + 4), (graphics, x, y, width, mouseX, mouseY, partialTick) -> {
                JournalSkin.experienceOrb(graphics, x, y);
                for (int i = 0; i < wrapped.size(); i++) graphics.drawString(font, wrapped.get(i), x + 23, y + 4 + i * lineHeight, color, false);
            });
        }

        /** A line with a prefix (a bullet, a checkbox) and the text wrapped beside it. */
        public Detail item(String prefix, Component text, int color) {
            int indent = font.width(JournalSkin.text(Component.literal(prefix + " ")));
            List<FormattedCharSequence> wrapped = font.split(JournalSkin.text(text), Math.max(1, width - indent));
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new Line(i == 0 ? prefix : null, wrapped.get(i), indent, color, lineHeight));
            }
            return this;
        }

        /** An {@link #item} the player can click (a choice); it is highlighted under the mouse. */
        public Detail clickable(String prefix, Component text, int color, Runnable click) {
            int indent = font.width(JournalSkin.text(Component.literal(prefix + " ")));
            List<FormattedCharSequence> wrapped = font.split(JournalSkin.text(text), Math.max(1, width - indent));
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new Line(i == 0 ? prefix : null, wrapped.get(i), indent, color, lineHeight, null, click));
            }
            return this;
        }

        /** Stable-size reward choices share their visual bounds with the click target. */
        public Detail rewardChoice(net.minecraft.world.item.ItemStack stack, boolean selected, int color, @Nullable Runnable click) {
            var label = JournalSkin.text(Component.literal(stack.getCount() + " × ").append(stack.getHoverName()));
            var wrapped = font.split(label, Math.max(1, width - 38));
            int rowHeight = Math.max(26, wrapped.size() * lineHeight + 8);
            Block draw = (graphics, x, y, width, mouseX, mouseY, partialTick) -> {
                var theme = MenuThemes.INSTANCE.theme();
                boolean hovered = click != null && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + rowHeight;
                if (selected || hovered) graphics.fill(x, y + 1, x + width, y + rowHeight - 1, selected ? theme.selection() : theme.hover());
                int cy = y + (rowHeight - 10) / 2;
                int ink = theme.divider();
                graphics.fill(x + 2, cy, x + 12, cy + 2, ink);
                graphics.fill(x + 2, cy + 8, x + 12, cy + 10, ink);
                graphics.fill(x + 2, cy + 2, x + 4, cy + 8, ink);
                graphics.fill(x + 10, cy + 2, x + 12, cy + 8, ink);
                if (selected) graphics.fill(x + 5, cy + 3, x + 9, cy + 7, theme.completed());
                graphics.renderItem(stack, x + 17, y + (rowHeight - 16) / 2);
                int textY = y + (rowHeight - (wrapped.size() - 1) * lineHeight - 7) / 2;
                for (int i = 0; i < wrapped.size(); i++) graphics.drawString(font, wrapped.get(i), x + 38, textY + i * lineHeight, color, false);
            };
            lines.add(new Line(null, null, 0, color, rowHeight, draw, click));
            return this;
        }

        /** Text under an {@link #item} with the same prefix, aligned with that item's text. */
        public Detail note(String prefix, Component text, int color) {
            int indent = font.width(JournalSkin.text(Component.literal(prefix + " ")));
            for (FormattedCharSequence line : font.split(JournalSkin.text(text), Math.max(1, width - indent))) {
                lines.add(new Line(null, line, indent, color, lineHeight));
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

        /** Leave room for ascenders above the font baseline inside the scissor. */
        public Detail heading(Component text, int color) {
            if (!MenuThemes.INSTANCE.journalBook()) return text(text, color);
            gap(3);
            for (FormattedCharSequence line : font.split(JournalSkin.heading(text), Math.max(1, width / 2))) {
                block(22, (graphics, x, y, width, mouseX, mouseY, partialTick) -> {
                    graphics.pose().pushPose();
                    graphics.pose().translate(x, y, 0);
                    graphics.pose().scale(2, 2, 1);
                    graphics.drawString(font, line, 0, 0, color, false);
                    graphics.pose().popPose();
                });
            }
            return this;
        }
    }
}
