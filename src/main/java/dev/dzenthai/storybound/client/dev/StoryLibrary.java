package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.client.dev.ClientDevStore.Edge;
import dev.dzenthai.storybound.client.dev.ClientDevStore.NodeKey;
import dev.dzenthai.storybound.client.dev.DevModeScreen.MenuItem;
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
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The editor's "Library" tab: every definition as a list, by kind and with a search, for what is not one quest's own
 * (an NPC's small talk, a script several quests run, the Codex). The selected one opens in the story inspector, as on
 * the canvas; each row says how many links it has, so what nothing uses stands out.
 */
final class StoryLibrary {
    private static final int ROW_H = 24;
    private static final int LIST_TOP = DevUi.TOP_BAR_H + 30;
    private static final long DOUBLE_CLICK_MS = 350;

    record Row(NodeKey key, String title, String badge, int color, ItemStack icon, int links, boolean hasError, boolean missingTranslation) {}

    private final StoryView view;
    /** The kind shown; null shows all. */
    private @Nullable ResourceLocation kind;
    private String search = "";
    private int scroll;
    private List<Row> rows = List.of();
    private Map<ResourceLocation, Integer> counts = Map.of();
    private long lastClickTime;
    private @Nullable NodeKey lastClicked;

    StoryLibrary(StoryView view) {
        this.view = view;
    }

    /** Re-reads the list from the store. */
    void rebuild(List<Edge> edges) {
        Map<NodeKey, Set<NodeKey>> partners = new HashMap<>();
        for (Edge edge : edges) {
            partners.computeIfAbsent(edge.from(), key -> new HashSet<>()).add(edge.to());
            partners.computeIfAbsent(edge.to(), key -> new HashSet<>()).add(edge.from());
        }
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<Row> list = new ArrayList<>();
        Map<ResourceLocation, Integer> perKind = new HashMap<>();
        for (ResourceLocation type : StoryView.KINDS) {
            List<Row> ofType = new ArrayList<>();
            ClientDevStore.byType(type).forEach((id, raw) -> {
                NodeKey key = new NodeKey(type, id);
                JsonObject json = ClientDevStore.parseObject(raw);
                String title = StoryView.displayTitle(key, json);
                if (!needle.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(needle) && !id.toString().contains(needle)) {
                    return;
                }
                perKind.merge(type, 1, Integer::sum);
                if (kind == null || kind.equals(type)) {
                    ofType.add(new Row(key, title, StoryView.badge(type, json), StoryView.color(type, json), StoryView.icon(key, json),
                            partners.getOrDefault(key, Set.of()).size(),
                            !ClientDevStore.issuesFor(type, id).isEmpty() || StoryView.scriptBroken(key),
                            !ClientDevStore.isNodeFullyTranslated(key, json, ClientDevStore.activeLanguage())));
                }
            });
            ofType.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));
            list.addAll(ofType);
        }
        rows = list;
        counts = perKind;
    }

    // --- the kinds on the left panel

    /** The kinds, "everything" first, from {@code top}; each row 20 high. */
    void renderKinds(DevModeScreen screen, GuiGraphics graphics, int top, int mouseX, int mouseY) {
        Font font = screen.font();
        graphics.drawString(font, DevUi.text("library.kinds"), 8, top - 14, DevUi.ACCENT, false);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int y = top;
        DevUi.listRow(graphics, font, DevUi.str("library.all", total), y, kind == null, DevUi.inside(mouseX, mouseY, 4, y, DevUi.LEFT_PANEL_W - 8, 18));
        for (ResourceLocation type : StoryView.KINDS) {
            y += 20;
            String label = StoryView.glyph(type) + " " + DevUi.str("kind." + type.getPath()) + " (" + counts.getOrDefault(type, 0) + ")";
            DevUi.listRow(graphics, font, label, y, type.equals(kind), DevUi.inside(mouseX, mouseY, 4, y, DevUi.LEFT_PANEL_W - 8, 18));
        }
    }

    boolean clickKind(DevModeScreen screen, int row) {
        if (row < 0 || row > StoryView.KINDS.size()) {
            return false;
        }
        kind = row == 0 ? null : StoryView.KINDS.get(row - 1);
        scroll = 0;
        view.rebuildCards();
        screen.rebuild();
        return true;
    }

    // --- the list

    void addWidgets(DevModeScreen screen) {
        int left = DevUi.LEFT_PANEL_W + 8;
        int right = screen.canvasRight() - 8;
        List<NodeTemplates.Template> templates = StoryView.templatesFor(kind);
        int createW = templates.isEmpty() ? 0 : 90;
        EditBox box = new EditBox(screen.font(), left, DevUi.TOP_BAR_H + 6, Math.max(60, right - left - createW - (createW > 0 ? 4 : 0)), 18,
                DevUi.text("library.search"));
        box.setHint(DevUi.text("library.search"));
        box.setValue(search);
        box.setResponder(value -> {
            search = value;
            scroll = 0;
            view.rebuildCards();
        });
        screen.add(box);
        if (createW > 0) {
            int x = right - createW;
            screen.add(Button.builder(DevUi.text("library.create"), b -> screen.openMenu(x, DevUi.TOP_BAR_H + 26, view.templateMenu(screen, kind)))
                    .bounds(x, DevUi.TOP_BAR_H + 6, createW, 18).build());
        }
    }

    private int visibleRows(DevModeScreen screen) {
        return Math.max(1, (screen.height - LIST_TOP - 4) / ROW_H);
    }

    void scroll(DevModeScreen screen, double amount) {
        scroll = Mth.clamp(scroll - (int) Math.signum(amount), 0, Math.max(0, rows.size() - visibleRows(screen)));
    }

    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        int left = DevUi.LEFT_PANEL_W;
        int right = screen.canvasRight();
        graphics.fill(left, DevUi.TOP_BAR_H, right, screen.height, 0xFF1C2026);
        if (rows.isEmpty()) {
            graphics.drawString(font, DevUi.text("library.empty"), left + 12, LIST_TOP + 8, DevUi.MUTED, false);
            return;
        }
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - visibleRows(screen)));
        int end = Math.min(rows.size(), scroll + visibleRows(screen));
        for (int i = scroll; i < end; i++) {
            Row row = rows.get(i);
            int y = LIST_TOP + (i - scroll) * ROW_H;
            boolean selected = view.selection.contains(row.key()) || row.key().equals(view.selected);
            boolean hover = DevUi.inside(mouseX, mouseY, left + 4, y, right - left - 8, ROW_H - 2);
            graphics.fill(left + 4, y, right - 4, y + ROW_H - 2, selected ? DevUi.ROW_SELECTED : hover ? DevUi.ROW_HOVER : DevUi.CARD);
            graphics.fill(left + 4, y, left + 6, y + ROW_H - 2, row.color());
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, -140);
            graphics.renderItem(row.icon(), left + 10, y + 3);
            graphics.pose().popPose();
            String links = DevUi.str("library.links", row.links());
            int sideW = Math.max(font.width(row.badge()), font.width(links)) + 16;
            int textW = right - left - 46 - sideW;
            String title = (row.hasError() ? "⚠ " : "") + row.title();
            graphics.drawString(font, font.plainSubstrByWidth(title, textW), left + 30, y + 3, row.hasError() ? DevUi.ERROR : 0xFFF5F5F5, false);
            graphics.drawString(font, font.plainSubstrByWidth(row.key().id().toString(), textW), left + 30, y + 13, DevUi.MUTED, false);
            int sideX = right - 10;
            graphics.drawString(font, row.badge(), sideX - font.width(row.badge()), y + 3, row.color(), false);
            graphics.drawString(font, links, sideX - font.width(links), y + 13, row.links() == 0 ? DevUi.WARN : DevUi.MUTED, false);
            if (row.missingTranslation()) {
                int squareX = sideX - font.width(row.badge()) - 12;
                graphics.fill(squareX, y + 3, squareX + 7, y + 10, DevUi.WARN);
            }
        }
        if (rows.size() > end - scroll) {
            int track = screen.height - LIST_TOP - 4;
            int thumb = Math.max(12, track * (end - scroll) / rows.size());
            int thumbY = LIST_TOP + (track - thumb) * scroll / Math.max(1, rows.size() - (end - scroll));
            graphics.fill(right - 3, thumbY, right - 1, thumbY + thumb, DevUi.PANEL_EDGE);
        }
    }

    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button) {
        if (mouseY < LIST_TOP) {
            return false;
        }
        int index = scroll + (int) ((mouseY - LIST_TOP) / ROW_H);
        Row row = index >= 0 && index < rows.size() ? rows.get(index) : null;
        if (row == null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                view.select(screen, null);
                screen.rebuild();
            }
            return true;
        }
        NodeKey key = row.key();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            view.select(screen, key);
            screen.rebuild();
            screen.openMenu((int) mouseX, (int) mouseY, menu(screen, key));
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (key.equals(lastClicked) && now - lastClickTime < DOUBLE_CLICK_MS && key.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            lastClicked = null;
            screen.openDialogue(key.id());
            return true;
        }
        lastClickTime = now;
        lastClicked = key;
        view.select(screen, key);
        screen.rebuild();
        return true;
    }

    private List<MenuItem> menu(DevModeScreen screen, NodeKey key) {
        List<MenuItem> items = new ArrayList<>();
        String path = key.id().getPath();
        if (key.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            items.add(MenuItem.of(Component.literal("⤢ ").append(DevUi.text("menu.open_dialogue")), () -> screen.openDialogue(key.id())));
        }
        if (key.type().equals(ClientDevStore.TYPE_QUEST)) {
            items.add(MenuItem.of(Component.literal("📜 ").append(DevUi.text("menu.show_on_map")), () -> view.reveal(screen, StoryView.Tab.STORY, key)));
        }
        items.add(MenuItem.of(Component.literal("🕸 ").append(DevUi.text("menu.show_links")), () -> view.reveal(screen, StoryView.Tab.ALL, key)));
        items.add(MenuItem.of(Component.literal("📋 ").append(DevUi.text("menu.duplicate", path)), () -> view.duplicateFromList(screen, key)));
        items.add(MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("menu.delete", path)), () -> view.deleteSelected(screen)));
        return items;
    }
}
