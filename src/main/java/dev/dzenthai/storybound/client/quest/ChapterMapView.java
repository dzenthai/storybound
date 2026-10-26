package dev.dzenthai.storybound.client.quest;

import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuView;
import dev.dzenthai.storybound.module.quest.ChapterMap;
import dev.dzenthai.storybound.module.quest.DifficultyScale;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The Map tab: the chapters on the left, the selected chapter's quests where the author placed them, with arrows to
 * the quests that follow. Completed, active, failed, available and locked quests each have their colour and mark; a
 * secret quest shows "???" until it is available, an invisible one only once started. A started quest opens in the
 * Quests tab on click; a chapter too big for the panel is dragged around.
 */
public class ChapterMapView extends MenuView {
    private static final int ROW_HEIGHT = 14;
    private static final int NODE = 16;
    private static final int PAD = 18;
    /** Room under a node for its title. */
    private static final int LABEL = 12;

    /** Kept while the game runs, so the map opens where the player left it. */
    private static @Nullable ResourceLocation selectedChapter;
    private static double panX;
    private static double panY;
    private boolean dragging;

    /** Shown at all only when the pack has a chapter map. */
    public static boolean available() {
        return !ClientChapterMap.get().isEmpty();
    }

    private int listWidth() {
        if (screen.bookLayout() != null) return screen.bookLayout().indexWidth();
        return Math.max(90, width / 4);
    }

    private int canvasLeft() {
        if (screen.bookLayout() != null) return screen.bookLayout().detailLeft();
        return left + listWidth() + 8;
    }

    private @Nullable ChapterMap.Chapter chapter() {
        List<ChapterMap.Chapter> chapters = ClientChapterMap.get().chapters();
        if (chapters.isEmpty()) {
            return null;
        }
        return chapters.stream().filter(chapter -> chapter.id().equals(selectedChapter)).findFirst().orElseGet(chapters::getFirst);
    }

    /** Where each shown quest of the chapter is drawn: its centre on screen. */
    private Map<ResourceLocation, int[]> layout(ChapterMap.Chapter chapter) {
        List<ChapterMap.Node> shown = chapter.nodes().stream().filter(node -> ClientChapterMap.state(node.quest()) != ClientChapterMap.State.HIDDEN).toList();
        Map<ResourceLocation, int[]> out = new LinkedHashMap<>();
        if (shown.isEmpty()) {
            return out;
        }
        int minX = shown.stream().mapToInt(ChapterMap.Node::x).min().orElse(0);
        int maxX = shown.stream().mapToInt(ChapterMap.Node::x).max().orElse(0);
        int minY = shown.stream().mapToInt(ChapterMap.Node::y).min().orElse(0);
        int maxY = shown.stream().mapToInt(ChapterMap.Node::y).max().orElse(0);
        int canvasWidth = left + width - canvasLeft() - 2 * PAD;
        int canvasHeight = height - 2 * PAD - LABEL;
        // The editor's units are bigger than a quest's mark: shrink to fit, but never so far that the marks touch.
        double scale = Math.min(1, Math.min(canvasWidth / (double) Math.max(1, maxX - minX), canvasHeight / (double) Math.max(1, maxY - minY)));
        scale = Math.max(0.25, scale);
        for (ChapterMap.Node node : shown) {
            int x = canvasLeft() + PAD + (int) Math.round((node.x() - minX) * scale + panX);
            int y = top + PAD + (int) Math.round((node.y() - minY) * scale + panY);
            out.put(node.quest(), new int[]{x, y});
        }
        return out;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuTheme theme = theme();
        List<ChapterMap.Chapter> chapters = ClientChapterMap.get().chapters();
        if (chapters.isEmpty()) {
            Component empty = Component.translatable("storybound.map.empty");
            graphics.drawString(font, empty, left + (width - font.width(empty)) / 2, top + height / 2 - 4, theme.ink(), false);
            return;
        }
        ChapterMap.Chapter current = chapter();
        renderChapters(graphics, theme, chapters, current, mouseX, mouseY);
        if (screen.bookLayout() == null) graphics.fill(canvasLeft() - 4, top, canvasLeft() - 3, top + height, theme.divider());
        if (current == null) {
            return;
        }
        Map<ResourceLocation, int[]> layout = layout(current);
        graphics.enableScissor(canvasLeft(), top, left + width, top + height);
        for (ChapterMap.Link link : ClientChapterMap.get().links()) {
            int[] from = layout.get(link.from());
            int[] to = layout.get(link.to());
            if (from != null && to != null) {
                boolean done = ClientChapterMap.state(link.from()) == ClientChapterMap.State.COMPLETED;
                line(graphics, from[0], from[1], to[0], to[1], done ? theme.completed() : theme.divider());
            }
        }
        ResourceLocation hovered = null;
        for (Map.Entry<ResourceLocation, int[]> entry : layout.entrySet()) {
            int x = entry.getValue()[0];
            int y = entry.getValue()[1];
            ClientChapterMap.State state = ClientChapterMap.state(entry.getKey());
            boolean over = Math.abs(mouseX - x) <= NODE / 2 && Math.abs(mouseY - y) <= NODE / 2 && mouseX >= canvasLeft();
            if (over) {
                hovered = entry.getKey();
            }
            int color = color(theme, state);
            graphics.fill(x - NODE / 2 - 1, y - NODE / 2 - 1, x + NODE / 2 + 1, y + NODE / 2 + 1, over ? theme.title() : theme.divider());
            graphics.fill(x - NODE / 2, y - NODE / 2, x + NODE / 2, y + NODE / 2, color);
            String mark = mark(state);
            graphics.drawString(font, mark, x - font.width(mark) / 2, y - 4, 0xFFFFFFFF, false);
            Component title = title(entry.getKey(), state);
            String shortTitle = font.plainSubstrByWidth(title.getString(), 100);
            graphics.drawString(font, shortTitle, x - font.width(shortTitle) / 2, y + NODE / 2 + 2,
                    state == ClientChapterMap.State.LOCKED ? theme.muted() : theme.ink(), false);
        }
        graphics.disableScissor();
        if (hovered != null) {
            graphics.renderTooltip(font, tooltip(hovered), mouseX, mouseY);
        }
    }

    private void renderChapters(GuiGraphics graphics, MenuTheme theme, List<ChapterMap.Chapter> chapters, @Nullable ChapterMap.Chapter current,
                                int mouseX, int mouseY) {
        int y = top;
        for (ChapterMap.Chapter chapter : chapters) {
            boolean selected = chapter == current;
            boolean hovered = mouseX >= left && mouseX < left + listWidth() && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (selected || hovered) {
                graphics.fill(left, y, left + listWidth(), y + ROW_HEIGHT, selected ? theme.selection() : theme.hover());
            }
            int textX = left + 4;
            if (chapter.icon().isPresent()) {
                ItemStack icon = new ItemStack(BuiltInRegistries.ITEM.get(chapter.icon().get()));
                graphics.pose().pushPose();
                graphics.pose().translate(left + 2, y + 1, 0);
                graphics.pose().scale(0.75f, 0.75f, 1);
                graphics.renderItem(icon, 0, 0);
                graphics.pose().popPose();
                textX += 12;
            }
            long done = chapter.nodes().stream().filter(node -> ClientChapterMap.state(node.quest()) == ClientChapterMap.State.COMPLETED).count();
            long shown = chapter.nodes().stream().filter(node -> ClientChapterMap.state(node.quest()) != ClientChapterMap.State.HIDDEN).count();
            String count = done + "/" + shown;
            String title = font.plainSubstrByWidth(chapter.title().asComponent().getString(), listWidth() - (textX - left) - font.width(count) - 6);
            graphics.drawString(font, title, textX, y + 3, theme.ink(), false);
            graphics.drawString(font, count, left + listWidth() - font.width(count) - 2, y + 3, done == shown && shown > 0 ? theme.completed() : theme.muted(), false);
            y += ROW_HEIGHT;
        }
    }

    private List<FormattedCharSequence> tooltip(ResourceLocation quest) {
        ClientChapterMap.State state = ClientChapterMap.state(quest);
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(title(quest, state).copy().withStyle(ChatFormatting.BOLD).getVisualOrderText());
        lines.add(Component.translatable("storybound.map.state." + state.name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY).getVisualOrderText());
        QuestDefinition definition = QuestRegistries.QUESTS.clientStore().getOrNull(quest);
        if (definition != null && !ClientChapterMap.secret(quest, state)) {
            definition.difficulty().ifPresent(difficulty ->
                    lines.add(Component.translatable("storybound.quests.difficulty", DifficultyScale.current().render(difficulty)).getVisualOrderText()));
            definition.description().ifPresent(description -> lines.addAll(font.split(description.asComponent().copy().withStyle(ChatFormatting.GRAY), 200)));
        }
        if (opens(state)) {
            lines.add(Component.translatable("storybound.map.open").withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        }
        return lines;
    }

    private static Component title(ResourceLocation quest, ClientChapterMap.State state) {
        QuestDefinition definition = QuestRegistries.QUESTS.clientStore().getOrNull(quest);
        if (definition == null || ClientChapterMap.secret(quest, state)) {
            return Component.translatable("storybound.map.secret");
        }
        return definition.title().asComponent();
    }

    private static boolean opens(ClientChapterMap.State state) {
        return state == ClientChapterMap.State.COMPLETED || state == ClientChapterMap.State.ACTIVE || state == ClientChapterMap.State.FAILED;
    }

    private static int color(MenuTheme theme, ClientChapterMap.State state) {
        return switch (state) {
            case COMPLETED -> theme.completed();
            case ACTIVE -> theme.accent();
            case FAILED -> theme.failed();
            case AVAILABLE -> theme.ink();
            default -> theme.muted();
        };
    }

    private static String mark(ClientChapterMap.State state) {
        return switch (state) {
            case COMPLETED -> "✔";
            case ACTIVE -> "!";
            case FAILED -> "✘";
            case AVAILABLE -> "?";
            default -> "·";
        };
    }

    /** A one-pixel line, with a mark short of the end showing which way it goes. */
    private static void line(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= steps; i++) {
            float t = steps == 0 ? 0 : i / (float) steps;
            int x = Math.round(Mth.lerp(t, x0, x1));
            int y = Math.round(Mth.lerp(t, y0, y1));
            graphics.fill(x, y, x + 1, y + 1, color);
        }
        double length = Math.sqrt((double) (x1 - x0) * (x1 - x0) + (double) (y1 - y0) * (y1 - y0));
        if (length > NODE * 2) {
            double back = (NODE / 2.0 + 4) / length;
            int ax = (int) Math.round(x1 - (x1 - x0) * back);
            int ay = (int) Math.round(y1 - (y1 - y0) * back);
            graphics.fill(ax - 2, ay - 2, ax + 2, ay + 2, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<ChapterMap.Chapter> chapters = ClientChapterMap.get().chapters();
        if (mouseX >= left && mouseX < left + listWidth()) {
            int index = (int) ((mouseY - top) / ROW_HEIGHT);
            if (mouseY >= top && index >= 0 && index < chapters.size()) {
                if (!chapters.get(index).id().equals(selectedChapter)) {
                    selectedChapter = chapters.get(index).id();
                    panX = 0;
                    panY = 0;
                }
                return true;
            }
            return false;
        }
        ChapterMap.Chapter current = chapter();
        if (current == null || mouseX < canvasLeft()) {
            return false;
        }
        for (Map.Entry<ResourceLocation, int[]> entry : layout(current).entrySet()) {
            if (Math.abs(mouseX - entry.getValue()[0]) <= NODE / 2.0 && Math.abs(mouseY - entry.getValue()[1]) <= NODE / 2.0) {
                if (opens(ClientChapterMap.state(entry.getKey()))) {
                    QuestsView.show(screen, entry.getKey());
                }
                return true;
            }
        }
        dragging = button == 0;
        return dragging;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging) {
            return false;
        }
        panX += dragX;
        panY += dragY;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean was = dragging;
        dragging = false;
        return was;
    }
}
