package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.client.dev.DevModeScreen.MenuItem;
import dev.dzenthai.storybound.core.story.StoryLinks.Edge;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The story editor's search and problems (November 16, 2026; the same day they were sections of a left panel the
 * author found heavy): a window over the map, opened from the top bar's 🔍 (or Ctrl+F) and ⚠. <b>Search</b> lists every
 * definition by its title or id, greyed when nothing uses it, red with a problem; a click closes the window and shows
 * the node where it is (opening a chapter that has it), a right click offers what can be done with it, "+ New" makes a
 * node of any kind. <b>Problems</b> lists the validator's lines; a click goes to the node one names.
 */
final class StoryFinder {
    private static final int MAX_WIDTH = 380;
    private static final int MAX_HEIGHT = 300;
    private static final int ROW_H = 15;
    private static final int HEAD_H = 46;

    enum Mode { SEARCH, PROBLEMS }

    /** A definition in the search, with how many links it has (none: nothing uses it). */
    private record Row(NodeKey key, String title, int links, boolean hasError) {}

    /** A line of the validator and the node it is about, when it names one. */
    private record Problem(String text, boolean error, @Nullable NodeKey key) {}

    private final StoryView view;
    private boolean open;
    private Mode mode = Mode.SEARCH;
    private String search = "";
    private int scroll;
    private List<Row> rows = List.of();

    StoryFinder(StoryView view) {
        this.view = view;
    }

    boolean isOpen() {
        return open;
    }

    void open(Mode next) {
        open = true;
        mode = next;
        scroll = 0;
        rebuild();
    }

    void close() {
        open = false;
    }

    private void rebuild() {
        Map<NodeKey, Set<NodeKey>> partners = new HashMap<>();
        for (Edge edge : ClientDevStore.collectEdges()) {
            partners.computeIfAbsent(edge.from(), key -> new HashSet<>()).add(edge.to());
            partners.computeIfAbsent(edge.to(), key -> new HashSet<>()).add(edge.from());
        }
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<Row> list = new ArrayList<>();
        for (ResourceLocation type : StoryView.KINDS) {
            List<Row> ofType = new ArrayList<>();
            ClientDevStore.byType(type).forEach((id, raw) -> {
                NodeKey key = new NodeKey(type, id);
                String title = StoryView.displayTitle(key, ClientDevStore.parseObject(raw));
                if (!needle.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(needle) && !id.toString().contains(needle)) {
                    return;
                }
                ofType.add(new Row(key, title, partners.getOrDefault(key, Set.of()).size(),
                        !ClientDevStore.issuesFor(type, id).isEmpty() || StoryView.scriptBroken(key)));
            });
            ofType.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));
            list.addAll(ofType);
        }
        rows = list;
    }

    private static List<Problem> problems() {
        List<Problem> list = new ArrayList<>();
        for (String line : ClientDevStore.issues()) {
            list.add(new Problem(line, line.startsWith("[ERROR]"), nodeOf(line)));
        }
        return list;
    }

    /** The node a validator line is about ("[WARNING] storybound:quest storybound:wolves: …"), or null. */
    static @Nullable NodeKey nodeOf(String line) {
        String rest = line.startsWith("[") && line.indexOf("] ") > 0 ? line.substring(line.indexOf("] ") + 2) : line;
        int colon = rest.indexOf(": ");
        if (colon < 0) {
            return null;
        }
        String[] parts = rest.substring(0, colon).split(" ");
        if (parts.length < 2) {
            return null;
        }
        ResourceLocation type = ResourceLocation.tryParse(parts[0]);
        ResourceLocation id = ResourceLocation.tryParse(parts[1]);
        return type != null && id != null ? new NodeKey(type, id) : null;
    }

    /** "Wolf hunt: Unknown stage den" rather than the validator's whole line (which shows on hover). */
    private static String shortProblem(Problem problem) {
        String text = problem.text();
        int colon = text.indexOf(": ");
        String message = colon >= 0 ? text.substring(colon + 2) : text;
        return problem.key() == null ? message : StoryView.title(problem.key()) + ": " + message;
    }

    // --- layout

    private int width(DevModeScreen screen) {
        return Math.min(MAX_WIDTH, screen.width - 40);
    }

    private int height(DevModeScreen screen) {
        return Math.min(MAX_HEIGHT, screen.height - 40);
    }

    private int left(DevModeScreen screen) {
        return (screen.width - width(screen)) / 2;
    }

    private int top(DevModeScreen screen) {
        return (screen.height - height(screen)) / 2;
    }

    private int listTop(DevModeScreen screen) {
        return top(screen) + (mode == Mode.SEARCH ? HEAD_H : 24);
    }

    private int visibleRows(DevModeScreen screen) {
        return Math.max(1, (top(screen) + height(screen) - 6 - listTop(screen)) / ROW_H);
    }

    private int count() {
        return mode == Mode.SEARCH ? rows.size() : ClientDevStore.issues().size();
    }

    // --- widgets and drawing

    void addWidgets(DevModeScreen screen) {
        int left = left(screen);
        int top = top(screen);
        int w = width(screen);
        screen.add(Button.builder(Component.literal("✕"), b -> escape(screen)).bounds(left + w - 20, top + 4, 16, 14).build());
        if (mode != Mode.SEARCH) {
            return;
        }
        int newW = screen.font().width(DevUi.text("finder.new")) + 12;
        EditBox box = new EditBox(screen.font(), left + 8, top + 22, w - 16 - newW - 4, 16, DevUi.text("library.search"));
        box.setHint(DevUi.text("library.search"));
        box.setValue(search);
        box.setResponder(value -> {
            search = value;
            scroll = 0;
            rebuild();
        });
        screen.add(box);
        screen.focus(box);
        int newX = left + w - 8 - newW;
        screen.add(Button.builder(DevUi.text("finder.new"), b -> {
            close();
            screen.openMenu(newX, top + 40, view.templateMenu(screen, null, false));
            screen.rebuild();
        }).bounds(newX, top + 22, newW, 16).build());
    }

    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        int left = left(screen);
        int top = top(screen);
        int w = width(screen);
        int h = height(screen);
        graphics.fill(0, 0, screen.width, screen.height, 0x88000000);
        graphics.fill(left - 1, top - 1, left + w + 1, top + h + 1, DevUi.PANEL_EDGE);
        graphics.fill(left, top, left + w, top + h, DevUi.PANEL);
        List<Problem> problems = mode == Mode.PROBLEMS ? problems() : List.of();
        Component title = mode == Mode.SEARCH ? DevUi.text("finder.search") : DevUi.text("finder.problems", problems.size());
        graphics.drawString(font, title, left + 8, top + 8, DevUi.ACCENT, false);
        int listTop = listTop(screen);
        int visible = visibleRows(screen);
        scroll = Mth.clamp(scroll, 0, Math.max(0, count() - visible));
        if (count() == 0) {
            Component empty = mode == Mode.SEARCH ? DevUi.text("library.empty") : DevUi.text("finder.problems_none");
            graphics.drawString(font, empty, left + 8, listTop + 4, mode == Mode.SEARCH ? DevUi.MUTED : DevUi.OK, false);
            return;
        }
        for (int i = scroll; i < Math.min(count(), scroll + visible); i++) {
            int y = listTop + (i - scroll) * ROW_H;
            boolean hover = DevUi.inside(mouseX, mouseY, left + 4, y, w - 8, ROW_H);
            if (mode == Mode.SEARCH) {
                Row row = rows.get(i);
                if (hover) {
                    graphics.fill(left + 4, y, left + w - 4, y + ROW_H, DevUi.ROW_HOVER);
                }
                int color = row.hasError() ? DevUi.ERROR : row.links() == 0 ? DevUi.HINT : DevUi.LABEL;
                String note = row.links() == 0 ? DevUi.str("search.unused") : DevUi.str("kind." + row.key().type().getPath());
                int noteW = font.width(note);
                String icon = DevIcons.split(StoryView.glyph(row.key().type())).icon();
                int textX = left + 8;
                if (icon != null) {
                    DevIcons.draw(graphics, icon, textX, y + 2, hover ? DevUi.ACCENT : color);
                    textX += DevIcons.SIZE + DevIcons.GAP;
                }
                screen.drawCut(graphics, row.title(), textX, y + 4, left + w - 16 - noteW - textX, color);
                graphics.drawString(font, note, left + w - 10 - noteW, y + 4, DevUi.MUTED, false);
            } else {
                Problem problem = problems.get(i);
                if (hover && problem.key() != null) {
                    graphics.fill(left + 4, y, left + w - 4, y + ROW_H, DevUi.ROW_HOVER);
                }
                screen.drawCut(graphics, "⚠ " + shortProblem(problem), left + 8, y + 4, w - 16, problem.error() ? DevUi.ERROR : DevUi.WARN);
            }
        }
    }

    // --- input

    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button) {
        int left = left(screen);
        int top = top(screen);
        if (!DevUi.inside(mouseX, mouseY, left, top, width(screen), height(screen))) {
            // A click beside the window closes it, as menus close.
            escape(screen);
            return true;
        }
        int listTop = listTop(screen);
        if (mouseY < listTop) {
            return true;
        }
        int index = scroll + (int) ((mouseY - listTop) / ROW_H);
        if (index < 0 || index >= Math.min(count(), scroll + visibleRows(screen))) {
            return true;
        }
        NodeKey key = mode == Mode.SEARCH ? rows.get(index).key() : problems().get(index).key();
        if (key == null) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && mode == Mode.SEARCH) {
            screen.openMenu((int) mouseX, (int) mouseY, menu(screen, key));
            return true;
        }
        close();
        view.revealAnywhere(screen, key);
        return true;
    }

    private List<MenuItem> menu(DevModeScreen screen, NodeKey key) {
        List<MenuItem> items = new ArrayList<>();
        String path = key.id().getPath();
        Runnable done = () -> {
            close();
            screen.rebuild();
        };
        if (StoryView.spine(key)) {
            items.add(MenuItem.of(Component.literal("📌 ").append(DevUi.text("finder.to_map")), () -> {
                done.run();
                view.putOnMap(screen, key);
            }));
        }
        if (key.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            items.add(MenuItem.of(Component.literal("⤢ ").append(DevUi.text("menu.open_dialogue")), () -> {
                done.run();
                screen.openDialogue(key.id());
            }));
        }
        if (key.type().equals(ClientDevStore.TYPE_QUEST)) {
            items.add(MenuItem.of(Component.literal("🔎 ").append(DevUi.text("menu.inside_quest")), () -> {
                done.run();
                screen.openQuest(key.id());
            }));
        }
        items.add(MenuItem.of(Component.literal("🕸 ").append(DevUi.text("menu.show_links")), () -> {
            done.run();
            view.reveal(screen, StoryView.Tab.ALL, key);
        }));
        items.add(MenuItem.of(Component.literal("📋 ").append(DevUi.text("menu.duplicate", path)), () -> {
            done.run();
            view.duplicateFromList(screen, key);
        }));
        items.add(MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("menu.delete", path)), () -> {
            view.select(screen, key);
            view.deleteSelected(screen);
            rebuild();
            screen.rebuild();
        }));
        return items;
    }

    void scroll(double amount) {
        scroll = Math.max(0, scroll - (int) Math.signum(amount) * 2);
    }

    /** ESC, ✕ or a click beside it: closed, nothing changed. */
    boolean escape(DevModeScreen screen) {
        close();
        screen.rebuild();
        return true;
    }
}
