package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.DevModeScreen.MenuItem;
import dev.dzenthai.storybound.client.dev.NodeTemplates.Template;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.story.StoryFlow;
import dev.dzenthai.storybound.core.story.StoryLinks.Edge;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import dev.dzenthai.storybound.core.story.StoryRenames;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.Documents;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The story editor: one workspace (November 16, 2026, stage 1 of the workspace redesign; before, three tabs, Story,
 * Library and All links): the left panel ({@link StoryPanel}: chapters, search, problems), the canvas, and the selected
 * definition's inspector on the right ({@link StoryInspector}). The canvas is the story's spine as in FTB Quests: the
 * quests and story events of the open chapter, an arrow from each to what it leads to with the nodes between folded
 * into it ({@link StoryFlow}) and its words on it ("after it is completed", "through 💬"); what belongs to a quest or
 * an event (its giver, dialogues, documents, waypoints, boss) is badges under it ({@link StoryFlow#attached}).
 * Hovering a node lights its links and dims the rest. "Show all links" ({@link Tab#ALL}) shows every definition as a
 * card with every link as an arrow. On both, Shift selects several cards; dragging from a card's port to another card
 * writes the link into the first one's JSON ({@link StoryEdits#link}).
 */
final class StoryView extends GraphView {
    /**
     * A node's box: two cells of the grid by two, its shape centred in it (November 18, 2026: shapes as in FTB Quests;
     * before, cards of 6 cells by 2, and that day first 136 by 42), its title under it, its badges under the title.
     */
    static final int NODE_W = 2 * DevUi.GRID;
    static final int NODE_H = 2 * DevUi.GRID;
    /** How wide a node's title is at most, under it. */
    private static final int TITLE_W = 4 * DevUi.GRID - 4;
    private static final long DOUBLE_CLICK_MS = 350;
    /** How often the shown player's progress is asked for again, in ticks. */
    private static final int PROGRESS_REFRESH = 40;
    /** The kinds of definitions, in the order "All links" puts them in columns, left to right. */
    static final List<ResourceLocation> KINDS = List.of(ClientDevStore.TYPE_NPC, ClientDevStore.TYPE_PHRASES, ClientDevStore.TYPE_DIALOGUE,
            ClientDevStore.TYPE_SCRIPT, ClientDevStore.TYPE_DOCUMENT, ClientDevStore.TYPE_QUEST, ClientDevStore.TYPE_WAYPOINT, ClientDevStore.TYPE_BOSS,
            ClientDevStore.TYPE_CODEX, ClientDevStore.TYPE_EVENT);
    /** The map's grid for quests not placed by hand: a column per step of the story. */
    private static final int MAP_COLUMN_W = 5 * DevUi.GRID;
    private static final int MAP_ROW_H = 4 * DevUi.GRID;
    /** A badge under a node of the map: one of its own nodes. */
    private static final int BADGE_W = 11;
    private static final int BADGE_H = 11;
    /** Badges shown under a node before the rest fold into "+N". */
    private static final int MAX_BADGES = 5;
    /**
     * "Not in a chapter": the quests and story events no chapter has, kept at the places of the whole story's view
     * ({@link Chapters#QUESTS}). Never written as a chapter of its own.
     */
    static final ResourceLocation UNSORTED = dev.dzenthai.storybound.Storybound.id("__unsorted__");

    /** The map of the story's spine, or every definition with every link ("Show all links"). */
    enum Tab { STORY, ALL }

    /** A template in the "new" menus, with the kind it makes. */
    private record TemplateItem(String icon, String label, Template template, ResourceLocation kind) {}

    private static final List<TemplateItem> TEMPLATES = List.of(
            new TemplateItem("📜", "menu.new.quest_main", Template.QUEST_MAIN, ClientDevStore.TYPE_QUEST),
            new TemplateItem("🗡", "menu.new.quest_side", Template.QUEST_SIDE, ClientDevStore.TYPE_QUEST),
            new TemplateItem("⚔", "menu.new.contract", Template.CONTRACT, ClientDevStore.TYPE_QUEST),
            new TemplateItem("💬", "menu.new.dialogue_screen", Template.DIALOGUE_SCREEN, ClientDevStore.TYPE_DIALOGUE),
            new TemplateItem("💭", "menu.new.dialogue_bubble", Template.DIALOGUE_BUBBLE, ClientDevStore.TYPE_DIALOGUE),
            new TemplateItem("🧑", "menu.new.npc", Template.NPC, ClientDevStore.TYPE_NPC),
            new TemplateItem("🗨", "menu.new.phrases", Template.PHRASES, ClientDevStore.TYPE_PHRASES),
            new TemplateItem("📖", "menu.new.document", Template.DOCUMENT, ClientDevStore.TYPE_DOCUMENT),
            new TemplateItem("📍", "menu.new.waypoint", Template.WAYPOINT, ClientDevStore.TYPE_WAYPOINT),
            new TemplateItem("🐉", "menu.new.boss", Template.BOSS, ClientDevStore.TYPE_BOSS),
            new TemplateItem("📚", "menu.new.codex", Template.CODEX, ClientDevStore.TYPE_CODEX),
            new TemplateItem("⚡", "menu.new.event", Template.EVENT, ClientDevStore.TYPE_EVENT),
            new TemplateItem("⚙", "menu.new.script", Template.SCRIPT, ClientDevStore.TYPE_SCRIPT));

    /**
     * A card on a canvas.
     *
     * @param subtitle  the id, in its tooltip
     * @param badge     what kind of node it is, in its tooltip
     * @param look      its shape and size
     * @param item      its icon: the author's (its {@code icon}) or its kind's
     * @param own       on the map, the nodes that belong to it, drawn as badges under it
     * @param selfStart a quest that starts by its own trigger, not given by anything on the canvas
     */
    record Card(NodeKey key, String title, String subtitle, String badge, int color, Look look, ItemStack item, int x, int y,
                boolean hasError, boolean missingTranslation, List<NodeKey> own, boolean selfStart) {
        Card(NodeKey key, String title, String subtitle, String badge, int color, Look look, ItemStack item, int x, int y,
             boolean hasError, boolean missingTranslation) {
            this(key, title, subtitle, badge, color, look, item, x, y, hasError, missingTranslation, List.of(), false);
        }

        Card withOwn(List<NodeKey> nodes, boolean starts) {
            return new Card(key, title, subtitle, badge, color, look, item, x, y, hasError, missingTranslation, nodes, starts);
        }

        /** The pixel its shape is centred on. */
        int cx() {
            return x + NODE_W / 2;
        }

        int cy() {
            return y + NODE_H / 2;
        }

        boolean holds(double worldX, double worldY) {
            return NodeShapes.contains(look.shape(), look.radius(), worldX - cx() - 0.5, worldY - cy() - 0.5);
        }

        /** The point {@code gap} pixels out of its edge towards {@code x, y} (canvas units), where a link to there starts. */
        double[] towards(double x, double y, double gap) {
            double dx = x - cx() - 0.5;
            double dy = y - cy() - 0.5;
            double length = Math.max(1e-6, Math.hypot(dx, dy));
            double reach = look.radius() + gap;
            return new double[]{cx() + 0.5 + dx / length * reach, cy() + 0.5 + dy / length * reach};
        }

        /** Where its links leave (right) or enter (left). */
        /** Its right edge, where its link port is. */
        int rightX() {
            return cx() + look.radius() + 1;
        }

        /** On its link port, the small square on its right corner that a link is dragged from. */
        boolean onPort(double worldX, double worldY) {
            return Math.abs(worldX - (rightX() - 0.5)) <= 5 && Math.abs(worldY - (cy() + 0.5)) <= 5;
        }
    }

    /**
     * A node's look by its kind (November 18, 2026, the author): a quest a diamond, the main ones larger, a contract a
     * triangle; a boss a triangle; a story event a circle, and in "all links" every other kind a smaller circle. The
     * rune is the kind's icon (a quest's by its category).
     */
    record Look(NodeShapes.Shape shape, int radius) {}

    /**
     * A node's shape by its kind (November 19, 2026, the author, as in FTB Quests: the shape tells the kind, the item
     * the node): a quest or a contract a diamond (a main quest larger), a story event or a script a circle, an NPC or a
     * boss a hexagon, a dialogue, phrases, a document or a Codex entry a square, a waypoint a triangle, apex down.
     */
    static Look look(ResourceLocation type, JsonObject json) {
        if (type.equals(ClientDevStore.TYPE_QUEST)) {
            return new Look(NodeShapes.Shape.DIAMOND, "main".equals(category(json)) ? 18 : 15);
        }
        if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            return new Look(NodeShapes.Shape.TRIANGLE, 15);
        }
        if (type.equals(ClientDevStore.TYPE_NPC) || type.equals(ClientDevStore.TYPE_BOSS)) {
            return new Look(NodeShapes.Shape.HEXAGON, 13);
        }
        if (type.equals(ClientDevStore.TYPE_DIALOGUE) || type.equals(ClientDevStore.TYPE_PHRASES) || type.equals(ClientDevStore.TYPE_DOCUMENT)
                || type.equals(ClientDevStore.TYPE_CODEX)) {
            return new Look(NodeShapes.Shape.SQUARE, 11);
        }
        return new Look(NodeShapes.Shape.CIRCLE, 13);
    }

    /**
     * A node's icon: the item the author picked for it (its {@code icon}, which only the editor reads; November 19,
     * 2026), else its kind's.
     */
    static ItemStack icon(NodeKey key, JsonObject json) {
        if (json.get("icon") instanceof com.google.gson.JsonPrimitive picked && picked.isString()) {
            ResourceLocation id = ResourceLocation.tryParse(picked.getAsString());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                return new ItemStack(BuiltInRegistries.ITEM.get(id));
            }
        }
        return kindIcon(key, json);
    }

    /** A kind's own icon, where the author picked none. */
    static ItemStack kindIcon(NodeKey key, JsonObject json) {
        ResourceLocation type = key.type();
        if (type.equals(ClientDevStore.TYPE_PHRASES)) {
            return new ItemStack(Items.NAME_TAG);
        }
        if (type.equals(ClientDevStore.TYPE_SCRIPT)) {
            return new ItemStack(Items.COMMAND_BLOCK);
        }
        if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
            DocumentDefinition definition = DocumentRegistries.DOCUMENTS.clientStore().getOrNull(key.id());
            return Documents.createStack(key.id(), definition);
        }
        if (type.equals(ClientDevStore.TYPE_QUEST)) {
            return new ItemStack(switch (category(json)) {
                case "contract" -> Items.PAPER;
                case "main" -> Items.ENCHANTED_BOOK;
                default -> Items.WRITABLE_BOOK;
            });
        }
        if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            return new ItemStack("bubble".equals(presentation(json)) ? Items.FEATHER : Items.GOAT_HORN);
        }
        if (type.equals(ClientDevStore.TYPE_NPC)) {
            return new ItemStack(Items.EMERALD);
        }
        if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            return new ItemStack(Items.COMPASS);
        }
        if (type.equals(ClientDevStore.TYPE_BOSS)) {
            return new ItemStack(Items.IRON_SWORD);
        }
        return new ItemStack(type.equals(ClientDevStore.TYPE_CODEX) ? Items.BOOK : Items.REDSTONE);
    }

    /** A badge under a card: one of its own nodes, or "+N" for the rest ({@code node} null). */
    private record Badge(Card card, int x, int y, int width, @Nullable NodeKey node, List<NodeKey> rest) {}

    /**
     * An arrow between two cards.
     *
     * @param litLabel the label while one of its cards is selected (on the map: the names of the nodes between)
     */
    private record Arrow(NodeKey from, NodeKey to, String label, String litLabel) {}

    Tab tab = Tab.STORY;
    /** The map's open chapter; {@link Chapters#QUESTS} is "All quests". */
    private ResourceLocation storyChapter = Chapters.quests();
    private final Map<NodeKey, int[]> positions = new LinkedHashMap<>();
    /** The quests on the map's open chapter. */
    private final Set<NodeKey> chapterNodes = new LinkedHashSet<>();
    @Nullable NodeKey selected;
    final Set<NodeKey> selection = new LinkedHashSet<>();
    /** The selected definition as edited in the inspector, not yet saved when {@link #dirty}. */
    @Nullable JsonObject editing;
    boolean dirty;
    /** What the last save sent or kept as a draft: an inspector showing just that has nothing unsaved. */
    private @Nullable String lastSaved;
    final StoryInspector inspector = new StoryInspector(this);
    private final StoryPanel panel = new StoryPanel(this);
    /** Pan and zoom of the tabs not open. */
    private final Map<Tab, double[]> views = new EnumMap<>(Tab.class);

    private final List<Card> cards = new ArrayList<>();
    private List<Arrow> arrows = List.of();
    private @Nullable NodeKey dragging;
    private double dragOffsetX;
    private double dragOffsetY;
    private final Map<NodeKey, int[]> dragStart = new LinkedHashMap<>();
    private @Nullable NodeKey linkingFrom;
    private boolean boxSelecting;
    private double boxStartX;
    private double boxStartY;
    private double boxEndX;
    private double boxEndY;
    private long lastClickTime;
    private @Nullable NodeKey lastClicked;
    /** Where the context menu was opened, on the canvas: new nodes go there. */
    private int menuWorldX;
    private int menuWorldY;

    @Override
    Component backLabel() {
        return DevUi.text("back.menu");
    }

    @Override
    void back(DevModeScreen screen) {
        screen.onClose();
    }

    /** No title over the map: the chapter strip and the map say where one is (November 16, 2026; before, "⚙ Dev Mode"). */
    @Override
    Component title() {
        return Component.empty();
    }

    @Override
    int untranslated() {
        return ClientDevStore.missingTranslationCount(ClientDevStore.activeLanguage());
    }

    /** The problems are this view's own ⚠ button, which opens their list ({@link StoryFinder}). */
    @Override
    boolean showsIssues() {
        return false;
    }

    @Override
    int canvasLeft() {
        return tab == Tab.STORY ? StoryPanel.STRIP_W : 0;
    }

    @Override
    boolean inspectorVisible(DevModeScreen screen) {
        return selected != null && editing != null && !screen.inspectorCollapsed();
    }

    /**
     * The chapter whose places the open canvas uses: the map's open chapter (the whole story's for the nodes in no
     * chapter), or the hidden one of "All links".
     */
    private ResourceLocation chapter() {
        if (tab == Tab.ALL) {
            return Chapters.overview();
        }
        return UNSORTED.equals(storyChapter) ? Chapters.quests() : storyChapter;
    }

    /** The story's spine, the map's nodes: quests and story events; the rest are their badges. */
    static boolean spine(NodeKey key) {
        return key.type().equals(ClientDevStore.TYPE_QUEST) || key.type().equals(ClientDevStore.TYPE_EVENT);
    }

    /** Whether the open canvas shows the node: the map its spine, "All links" every kind. */
    private boolean shows(NodeKey key) {
        return tab == Tab.STORY ? spine(key) : KINDS.contains(key.type());
    }

    /** How many quests and story events the story has. */
    int spineCount() {
        return ClientDevStore.byType(ClientDevStore.TYPE_QUEST).size() + ClientDevStore.byType(ClientDevStore.TYPE_EVENT).size();
    }

    /** The quests and story events no chapter has. */
    private Set<NodeKey> unsorted() {
        Set<NodeKey> inChapters = new java.util.HashSet<>();
        for (Map.Entry<ResourceLocation, JsonObject> entry : Chapters.sorted()) {
            inChapters.addAll(Chapters.positions(entry.getValue()).keySet());
        }
        Set<NodeKey> out = new LinkedHashSet<>();
        for (ResourceLocation type : List.of(ClientDevStore.TYPE_QUEST, ClientDevStore.TYPE_EVENT)) {
            for (ResourceLocation id : ClientDevStore.byType(type).keySet()) {
                NodeKey key = new NodeKey(type, id);
                if (!inChapters.contains(key)) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    int unsortedCount() {
        return unsorted().size();
    }

    /** The chapter the map shows now (none while every link is shown). */
    boolean isOpenChapter(ResourceLocation id) {
        return tab == Tab.STORY && id.equals(storyChapter);
    }

    /** Opens a chapter of the map, also from "Show all links". */
    void showChapter(DevModeScreen screen, ResourceLocation id) {
        if (tab != Tab.STORY) {
            openTab(screen, Tab.STORY);
        }
        openChapter(id);
        screen.rebuild();
    }

    @Override
    void reload() {
        if (!Chapters.hidden(storyChapter) && !UNSORTED.equals(storyChapter)
                && ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, storyChapter) == null) {
            storyChapter = Chapters.quests();
        }
        if (movedNamespace != null) {
            // The story's nodes moved to another namespace: the selected ones stay selected under their new ids.
            String[] move = movedNamespace;
            java.util.function.UnaryOperator<NodeKey> moved = key -> key.id().getNamespace().equals(move[0]) && ClientDevStore.getJson(key.type(), key.id()) == null
                    && ClientDevStore.getJson(key.type(), ResourceLocation.fromNamespaceAndPath(move[1], key.id().getPath())) != null
                    ? new NodeKey(key.type(), ResourceLocation.fromNamespaceAndPath(move[1], key.id().getPath())) : key;
            List<NodeKey> keys = new ArrayList<>(selection);
            selection.clear();
            keys.forEach(key -> selection.add(moved.apply(key)));
            if (selected != null) {
                selected = moved.apply(selected);
            }
        }
        selection.removeIf(key -> ClientDevStore.getJson(key.type(), key.id()) == null);
        if (selected != null && ClientDevStore.getJson(selected.type(), selected.id()) == null) {
            selected = selection.isEmpty() ? null : selection.iterator().next();
            dirty = false;
        }
        // Unsaved inspector edits stay; anything else shows what the server has now.
        if (!dirty) {
            editing = selected != null ? ClientDevStore.parseObject(ClientDevStore.getJson(selected.type(), selected.id())) : null;
        }
        loadChapter();
        rebuildCards();
    }

    /**
     * The open canvas's saved positions (the views showing everything keep theirs as hidden chapters); read when it
     * opens and when the server sends them, not while cards move.
     */
    private void loadChapter() {
        chapterNodes.clear();
        positions.clear();
        Chapters.positions(ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, chapter()))).forEach((key, pos) -> {
            if (spine(key)) {
                chapterNodes.add(key);
            }
            positions.put(key, pos);
        });
        if (UNSORTED.equals(storyChapter) && tab == Tab.STORY) {
            chapterNodes.clear();
            chapterNodes.addAll(unsorted());
        }
    }

    /** Lays out what the open canvas shows, the map or every node, and the left panel's search. */
    void rebuildCards() {
        cards.clear();
        List<Edge> edges = ClientDevStore.collectEdges();
        if (tab == Tab.STORY) {
            rebuildMap(edges);
        } else {
            rebuildAll(edges);
        }
    }

    /** Every node, by kind in columns for nodes not placed yet, with every link. */
    private void rebuildAll(List<Edge> edges) {
        int column = 0;
        for (ResourceLocation type : KINDS) {
            Map<ResourceLocation, String> definitions = ClientDevStore.byType(type);
            if (definitions.isEmpty()) {
                continue;
            }
            int row = 0;
            for (Map.Entry<ResourceLocation, String> entry : definitions.entrySet()) {
                NodeKey key = new NodeKey(type, entry.getKey());
                int x = column * 5 * DevUi.GRID + DevUi.GRID;
                int y = row * 3 * DevUi.GRID + DevUi.GRID;
                int[] pos = positions.computeIfAbsent(key, k -> new int[]{x, y});
                cards.add(card(key, ClientDevStore.parseObject(entry.getValue()), key.id().getPath(), pos[0], pos[1]));
                row++;
            }
            column++;
        }
        List<Arrow> list = new ArrayList<>();
        for (Edge edge : edges) {
            String label = edgeLabel(edge.label());
            list.add(new Arrow(edge.from(), edge.to(), label, label));
        }
        arrows = list;
    }

    /**
     * The quests of the open chapter (all of them in "All quests"), an arrow from each to the quests it leads to. A
     * quest not placed by hand stands a column after the quests leading to it.
     */
    private void rebuildMap(List<Edge> edges) {
        List<StoryFlow.Flow> flows = StoryFlow.flows(edges, StoryView::spine);
        Map<NodeKey, List<NodeKey>> own = StoryFlow.attached(edges, StoryView::spine);
        List<NodeKey> nodes = new ArrayList<>();
        for (ResourceLocation type : List.of(ClientDevStore.TYPE_QUEST, ClientDevStore.TYPE_EVENT)) {
            for (ResourceLocation id : ClientDevStore.byType(type).keySet()) {
                NodeKey key = new NodeKey(type, id);
                if (Chapters.quests().equals(storyChapter) || chapterNodes.contains(key)) {
                    nodes.add(key);
                }
            }
        }
        Set<NodeKey> shown = new LinkedHashSet<>(nodes);
        List<StoryFlow.Flow> inside = flows.stream().filter(flow -> shown.contains(flow.from()) && shown.contains(flow.to())).toList();
        Map<NodeKey, Integer> columns = StoryFlow.columns(nodes, inside);
        List<NodeKey> unplaced = new ArrayList<>();
        for (NodeKey node : nodes) {
            int[] pos = positions.get(node);
            if (pos == null) {
                unplaced.add(node);
            } else {
                cards.add(mapCard(node, own, pos[0], pos[1]));
            }
        }
        Map<Integer, Integer> rows = new HashMap<>();
        for (NodeKey node : unplaced) {
            int column = columns.getOrDefault(node, 0);
            int x = column * MAP_COLUMN_W + DevUi.GRID;
            int y;
            do {
                y = rows.merge(column, 1, Integer::sum) * MAP_ROW_H - MAP_ROW_H + DevUi.GRID;
            } while (occupied(x, y));
            positions.put(node, new int[]{x, y});
            cards.add(mapCard(node, own, x, y));
        }
        List<Arrow> list = new ArrayList<>();
        for (StoryFlow.Flow flow : inside) {
            String words = flowWords(flow, edges);
            list.add(new Arrow(flow.from(), flow.to(), flow.via().isEmpty() ? words : DevUi.str("arrow.via", glyphs(flow.via())),
                    flow.via().isEmpty() ? words : DevUi.str("arrow.via", names(flow.via()))));
        }
        arrows = list;
    }

    /** A node of the map, with its own nodes as badges; a quest with a trigger of its own starts by itself. */
    private Card mapCard(NodeKey node, Map<NodeKey, List<NodeKey>> own, int x, int y) {
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(node.type(), node.id()));
        boolean starts = node.type().equals(ClientDevStore.TYPE_QUEST) && json.get("start") instanceof JsonObject start && start.has("trigger");
        return card(node, json, node.id().getPath(), x, y).withOwn(own.getOrDefault(node, List.of()), starts);
    }

    /**
     * What an arrow straight from one node to the next means, in words: the next waits for this one to be completed
     * ("after"), this one starts or moves the next; otherwise the link's own name.
     */
    private static String flowWords(StoryFlow.Flow flow, List<Edge> edges) {
        for (Edge edge : edges) {
            boolean straight = edge.from().equals(flow.from()) && edge.to().equals(flow.to());
            if (straight) {
                String key = "storybound.dev.arrow." + edge.label();
                return Language.getInstance().has(key) ? Component.translatable(key).getString() : edgeLabel(edge.label());
            }
        }
        return "";
    }

    private Card card(NodeKey key, JsonObject json, String subtitle, int x, int y) {
        return new Card(key, displayTitle(key, json), subtitle, badge(key.type(), json), color(key.type(), json), look(key.type(), json), icon(key, json), x, y,
                !ClientDevStore.issuesFor(key.type(), key.id()).isEmpty() || scriptBroken(key),
                !ClientDevStore.isNodeFullyTranslated(key, json, ClientDevStore.activeLanguage()));
    }

    /** A kind's sign, as the menus show it. */
    static String glyph(ResourceLocation type) {
        if (type.equals(ClientDevStore.TYPE_PHRASES)) {
            return "🗨";
        }
        if (type.equals(ClientDevStore.TYPE_QUEST)) {
            return "📜";
        }
        if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            return "💬";
        }
        if (type.equals(ClientDevStore.TYPE_NPC)) {
            return "🧑";
        }
        if (type.equals(ClientDevStore.TYPE_SCRIPT)) {
            return "⚙";
        }
        if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
            return "📖";
        }
        if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            return "📍";
        }
        if (type.equals(ClientDevStore.TYPE_BOSS)) {
            return "🐉";
        }
        return type.equals(ClientDevStore.TYPE_CODEX) ? "📚" : "⚡";
    }

    private static String glyphs(List<NodeKey> nodes) {
        StringBuilder out = new StringBuilder();
        nodes.forEach(node -> out.append(glyph(node.type())));
        return out.toString();
    }

    /** The nodes an arrow passes, by name: "💬 The elder → ⚙ Burn the village". */
    private static String names(List<NodeKey> nodes) {
        List<String> parts = new ArrayList<>();
        for (NodeKey node : nodes) {
            String name = title(node);
            parts.add(glyph(node.type()) + " " + (name.length() > 20 ? name.substring(0, 19) + "…" : name));
        }
        return String.join(" → ", parts);
    }

    /** A node's title as cards show it; its id's path when it has none (or does not exist). */
    static String title(NodeKey key) {
        return displayTitle(key, ClientDevStore.parseObject(ClientDevStore.getJson(key.type(), key.id())));
    }

    static String displayTitle(NodeKey key, JsonObject json) {
        String field = json.has("title") ? "title" : json.has("name") ? "name" : null;
        if (field == null) {
            return key.id().getPath();
        }
        String text = ClientDevStore.readStoryText(json, field, key.id().getPath(), ClientDevStore.activeLanguage());
        return Language.getInstance().has(text) ? Language.getInstance().getOrDefault(text) : text;
    }

    private static String category(JsonObject quest) {
        return quest.has("category") ? DevUi.shortId(quest.get("category").getAsString()) : "side";
    }

    private static String presentation(JsonObject dialogue) {
        return dialogue.has("presentation") ? dialogue.get("presentation").getAsString() : "screen";
    }

    /** A script block whose file or handler the server does not have. */
    static boolean scriptBroken(NodeKey key) {
        if (!key.type().equals(ClientDevStore.TYPE_SCRIPT)) {
            return false;
        }
        ClientDevStore.ScriptState state = ClientDevStore.scriptState(key.id());
        return state == ClientDevStore.ScriptState.FILE_MISSING || state == ClientDevStore.ScriptState.NOT_REGISTERED;
    }

    static String badge(ResourceLocation type, JsonObject json) {
        if (type.equals(ClientDevStore.TYPE_PHRASES)) {
            int lines = json.get("lines") instanceof JsonArray array ? array.size() : 0;
            String order = json.get("order") instanceof JsonPrimitive written ? written.getAsString() : "random";
            return DevUi.str(order.equals("sequence") ? "badge.phrases_sequence" : order.equals("loop") ? "badge.phrases_loop" : "badge.phrases_random", lines);
        }
        if (type.equals(ClientDevStore.TYPE_SCRIPT)) {
            return DevUi.str("badge.script");
        }
        if (type.equals(ClientDevStore.TYPE_QUEST)) {
            return DevUi.str("badge.quest", category(json));
        }
        if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            return DevUi.str("badge.dialogue", presentation(json));
        }
        if (type.equals(ClientDevStore.TYPE_NPC)) {
            return DevUi.str(json.has("override_interact") && json.get("override_interact").getAsBoolean() ? "badge.npc_override" : "badge.npc");
        }
        if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
            return DevUi.str("badge.document", json.has("kind") ? json.get("kind").getAsString() : "note");
        }
        if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            double radius = StoryEdits.radius(json);
            return radius > 0 ? DevUi.str("badge.area", (int) radius) : DevUi.str("badge.waypoint");
        }
        if (type.equals(ClientDevStore.TYPE_BOSS)) {
            return DevUi.str("badge.boss");
        }
        return DevUi.str(type.equals(ClientDevStore.TYPE_CODEX) ? "badge.codex" : "badge.event");
    }

    static int color(ResourceLocation type, JsonObject json) {
        if (type.equals(ClientDevStore.TYPE_PHRASES)) {
            return 0xFFA5B574;
        }
        if (type.equals(ClientDevStore.TYPE_SCRIPT)) {
            return 0xFFBE96D0;
        }
        if (type.equals(ClientDevStore.TYPE_QUEST)) {
            return switch (category(json)) {
                case "main" -> 0xFFE1B34D;
                case "contract" -> 0xFFD9785A;
                default -> 0xFF92AACC;
            };
        }
        if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            return "bubble".equals(presentation(json)) ? 0xFFA5B574 : 0xFF708B34;
        }
        if (type.equals(ClientDevStore.TYPE_NPC)) {
            return 0xFF708B34;
        }
        if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
            return 0xFFCDA269;
        }
        if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            return 0xFF8E64AE;
        }
        if (type.equals(ClientDevStore.TYPE_BOSS)) {
            return 0xFFB33A2F;
        }
        return type.equals(ClientDevStore.TYPE_CODEX) ? 0xFF6480AE : 0xFFD9785A;
    }

    // --- selection and saving

    /** Selects one card (or none); unsaved inspector edits of the previous one are saved first. */
    void select(DevModeScreen screen, @Nullable NodeKey key) {
        saveEdits(screen);
        selection.clear();
        if (key != null) {
            selection.add(key);
            screen.showInspector();
        }
        selected = key;
        editing = key != null ? ClientDevStore.parseObject(ClientDevStore.getJson(key.type(), key.id())) : null;
    }

    /**
     * Gives the node a new id (see {@link StoryRenames}): unsaved edits go first, a
     * node still being filled in is not renamed. One undoable step: undo renames it back.
     */
    void rename(DevModeScreen screen, NodeKey key, ResourceLocation to) {
        saveEdits(screen);
        if (dirty) {
            screen.status(DevUi.text("rename.incomplete"), DevUi.WARN);
            return;
        }
        NodeKey renamed = new NodeKey(key.type(), to);
        requestRename(screen, key, to);
        screen.remember(DevUi.str("history.rename", key.id().toString(), to.toString()),
                () -> requestRename(screen, renamed, key.id()),
                () -> requestRename(screen, key, to));
    }

    /**
     * Renames here at once, as the server will (the node, an NPC's relationship scale, what names them), so the card
     * keeps its place and stays selected; the server's snapshot follows, or puts the old name back if it refuses.
     */
    private void requestRename(DevModeScreen screen, NodeKey key, ResourceLocation to) {
        NodeKey renamed = new NodeKey(key.type(), to);
        List<StoryRenames.Rename> renames = StoryRenames.cascade(
                new StoryRenames.Rename(key.type(), key.id(), to), (type, id) -> ClientDevStore.getJson(type, id) != null);
        for (StoryRenames.Rename rename : renames) {
            String json = ClientDevStore.getJson(rename.type(), rename.from());
            if (json != null) {
                ClientDevStore.removeLocal(rename.type(), rename.from());
                ClientDevStore.putLocal(rename.type(), rename.to(), json);
            }
        }
        ClientDevStore.all().forEach((type, definitions) -> {
            if (StoryRenames.REFERRING.contains(type)) {
                definitions.forEach((id, raw) -> {
                    JsonObject json = ClientDevStore.parseObject(raw);
                    if (StoryRenames.rewrite(type, json, renames) > 0) {
                        ClientDevStore.putLocal(type, id, DevUi.GSON.toJson(json));
                    }
                });
            }
        });
        if (selection.remove(key)) {
            selection.add(renamed);
        }
        if (key.equals(selected)) {
            selected = renamed;
        }
        editing = selected != null ? ClientDevStore.parseObject(ClientDevStore.getJson(selected.type(), selected.id())) : null;
        dirty = false;
        if (key.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            screen.dialogueRenamed(key.id(), to);
        }
        DevRequests.rename(key.type(), key.id(), to);
        loadChapter();
        rebuildCards();
        screen.status(DevUi.text("rename.sent", key.id().toString(), to.toString()), DevUi.OK);
        screen.rebuild();
    }

    /**
     * Renames a stage of the quest ({@code objective} empty) or an objective of a stage: unsaved edits go first, a quest
     * still being filled in is not renamed. One undoable step: undo renames it back.
     */
    void renameInQuest(DevModeScreen screen, NodeKey quest, String stage, String objective, String to) {
        saveEdits(screen);
        if (dirty) {
            screen.status(DevUi.text("rename.incomplete"), DevUi.WARN);
            return;
        }
        requestRenameInQuest(screen, quest, stage, objective, to);
        boolean ofStage = objective.isEmpty();
        screen.remember(DevUi.str("history.rename", ofStage ? stage : objective, to),
                () -> requestRenameInQuest(screen, quest, ofStage ? to : stage, ofStage ? "" : to, ofStage ? stage : objective),
                () -> requestRenameInQuest(screen, quest, stage, objective, to));
    }

    /** Renames here at once, as the server will; its snapshot follows, or puts the old name back if it refuses. */
    private void requestRenameInQuest(DevModeScreen screen, NodeKey quest, String stage, String objective, String to) {
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(quest.type(), quest.id()));
        if (objective.isEmpty()) {
            StoryRenames.renameStage(json, stage, to);
            ClientDevStore.putLocal(quest.type(), quest.id(), DevUi.GSON.toJson(json));
            ClientDevStore.all().forEach((type, definitions) -> {
                if (StoryRenames.REFERRING.contains(type)) {
                    definitions.forEach((id, raw) -> {
                        JsonObject other = ClientDevStore.parseObject(raw);
                        if (StoryRenames.rewriteStage(other, quest.id(), stage, to) > 0) {
                            ClientDevStore.putLocal(type, id, DevUi.GSON.toJson(other));
                        }
                    });
                }
            });
        } else {
            StoryRenames.renameObjective(json, stage, objective, to);
            ClientDevStore.putLocal(quest.type(), quest.id(), DevUi.GSON.toJson(json));
        }
        if (quest.equals(selected)) {
            editing = ClientDevStore.parseObject(ClientDevStore.getJson(quest.type(), quest.id()));
            dirty = false;
        }
        DevRequests.renameInQuest(quest.id(), stage, objective, to);
        rebuildCards();
        screen.status(DevUi.text("rename.sent", objective.isEmpty() ? stage : objective, to), DevUi.OK);
        screen.rebuild();
    }

    void renameCodexSection(DevModeScreen screen, NodeKey entry, String from, String to) {
        saveEdits(screen);
        if (dirty) {
            screen.status(DevUi.text("rename.incomplete"), DevUi.WARN);
            return;
        }
        requestRenameCodexSection(screen, entry, from, to);
        screen.remember(DevUi.str("history.rename", from, to),
                () -> requestRenameCodexSection(screen, entry, to, from),
                () -> requestRenameCodexSection(screen, entry, from, to));
    }

    /** Renames here at once, as the server will; its snapshot follows, or puts the old name back if it refuses. */
    private void requestRenameCodexSection(DevModeScreen screen, NodeKey entry, String from, String to) {
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(entry.type(), entry.id()));
        StoryRenames.renameCodexSection(json, from, to);
        ClientDevStore.putLocal(entry.type(), entry.id(), DevUi.GSON.toJson(json));
        ClientDevStore.all().forEach((type, definitions) -> {
            if (StoryRenames.REFERRING.contains(type)) {
                definitions.forEach((id, raw) -> {
                    JsonObject other = ClientDevStore.parseObject(raw);
                    if (StoryRenames.rewriteCodexSection(other, entry.id(), from, to) > 0) {
                        ClientDevStore.putLocal(type, id, DevUi.GSON.toJson(other));
                    }
                });
            }
        });
        if (entry.equals(selected)) {
            editing = ClientDevStore.parseObject(ClientDevStore.getJson(entry.type(), entry.id()));
            dirty = false;
        }
        DevRequests.renameCodexSection(entry.id(), from, to);
        rebuildCards();
        screen.status(DevUi.text("rename.sent", from, to), DevUi.OK);
        screen.rebuild();
    }

    /** The story's nodes are moving from one namespace to another: once they arrive, the selection follows them. */
    private String @Nullable [] movedNamespace;

    void namespaceMoved(String from, String to) {
        movedNamespace = new String[]{from, to};
    }

    /** Re-reads the primary selection after the selection set changed. */
    private void selectFromSet(DevModeScreen screen, @Nullable NodeKey primary) {
        saveEdits(screen);
        selected = primary;
        editing = primary != null ? ClientDevStore.parseObject(ClientDevStore.getJson(primary.type(), primary.id())) : null;
    }

    @Override
    void saveEdits(DevModeScreen screen) {
        if (dirty) {
            save(screen);
        }
    }

    /** Sends the selected definition as edited, with its document text, as one undoable step. */
    void save(DevModeScreen screen) {
        if (selected == null || editing == null) {
            return;
        }
        Optional<String> problem = DevChecks.problem(selected.type(), editing);
        if (problem.isPresent()) {
            // Still being filled in: kept here (over the snapshots too), sent once it loads.
            dirty = true;
            NodeKey drafted = selected;
            String draftBefore = ClientDevStore.getJson(drafted.type(), drafted.id());
            String draft = DevUi.GSON.toJson(editing);
            lastSaved = draft;
            ClientDevStore.putDraft(drafted.type(), drafted.id(), draft);
            // Undoable as any edit (November 7, 2026; before, an edit that left the node unfinished, a switch that
            // cleared what the entity was known by, could not be undone).
            if (draftBefore != null && !draftBefore.equals(draft)) {
                screen.remember(DevUi.str("history.save", drafted.id().getPath()),
                        () -> DevRequests.restore(drafted.type(), drafted.id(), draftBefore, Optional.empty()),
                        () -> DevRequests.restore(drafted.type(), drafted.id(), draft, Optional.empty()));
            }
            screen.status(DevUi.text("status.incomplete", DevChecks.shorten(problem.get())), DevUi.WARN);
            return;
        }
        NodeKey target = selected;
        String before = ClientDevStore.getJson(target.type(), target.id());
        Optional<String> beforeText = documentText(target.id());
        String after = DevUi.GSON.toJson(editing);
        Optional<String> afterText = inspector.documentText();
        dirty = false;
        lastSaved = after;
        ClientDevStore.putLocal(target.type(), target.id(), after);
        DevRequests.save(target.type(), target.id(), after, afterText);
        inspector.textSaved();
        // A script block's handler changed: what ran the old one runs the new one, in the same undoable step.
        List<Unlinked> renamed = new ArrayList<>();
        if (target.type().equals(ClientDevStore.TYPE_SCRIPT) && before != null) {
            String oldHook = StoryEdits.hookOf(target.id(), ClientDevStore.parseObject(before));
            String newHook = StoryEdits.hookOf(target.id(), editing);
            if (!oldHook.equals(newHook)) {
                renamed = renameHook(oldHook, newHook);
                if (!renamed.isEmpty()) {
                    screen.status(DevUi.text("status.hook_renamed", renamed.size(), newHook), DevUi.LINK);
                }
            }
        }
        List<Unlinked> alsoChanged = renamed;
        if (before != null && !before.equals(after) || !beforeText.equals(afterText) && afterText.isPresent()) {
            screen.remember(DevUi.str("history.save", target.id().getPath()),
                    () -> {
                        DevRequests.restore(target.type(), target.id(), before != null ? before : after, beforeText);
                        alsoChanged.forEach(change -> restore(change.key(), change.before()));
                    },
                    () -> {
                        DevRequests.restore(target.type(), target.id(), after, afterText);
                        alsoChanged.forEach(change -> restore(change.key(), change.after()));
                    });
        }
        if (!renamed.isEmpty()) {
            rebuildCards();
            return;
        }
        rebuildCards();
        screen.status(DevUi.text("status.saved", target.id().toString()), DevUi.OK);
    }

    /** A toggle in the inspector changed the definition: saved at once, so nothing is lost when switching cards. */
    @Override
    void beforeHistoryReload() {
        // A draft keeps the inspector "dirty"; with nothing typed since it was kept, the undone state shows.
        if (dirty && editing != null && DevUi.GSON.toJson(editing).equals(lastSaved)) {
            dirty = false;
        }
    }

    void changed(DevModeScreen screen) {
        dirty = true;
        save(screen);
        screen.rebuild();
    }

    /** The document's text in the active language, else the source language, else English. */
    static Optional<String> documentText(ResourceLocation document) {
        return ClientDevStore.document(document, ClientDevStore.activeLanguage())
                .or(() -> ClientDevStore.document(document, ClientDevStore.sourceLanguage()))
                .or(() -> ClientDevStore.document(document, "en_us"));
    }

    // --- creating, linking and deleting

    private int freeNumber() {
        int count = 0;
        for (Map.Entry<ResourceLocation, Map<ResourceLocation, String>> entry : ClientDevStore.all().entrySet()) {
            if (KINDS.contains(entry.getKey())) {
                count += entry.getValue().size();
            }
        }
        return count + 1;
    }

    /** Makes the template's definitions at the context menu's place; returns the one the inspector shows. */
    private @Nullable NodeKey create(DevModeScreen screen, Template template) {
        List<NodeTemplates.Created> created = fresh(template);
        NodeKey last = null;
        NodeKey shown = null;
        for (NodeTemplates.Created definition : created) {
            place(screen, definition, menuWorldX + definition.offsetX(), menuWorldY);
            last = definition.key();
            shown = shows(last) ? last : shown;
        }
        if (shown != null && !shown.equals(last)) {
            // A contract on the map: its quest is what the map shows, the notice is in the library.
            select(screen, shown);
        }
        if (last != null) {
            ask(screen, last, NodeTemplates.ask(template));
        }
        return last;
    }

    /** A new node's first question: who it is (its entity's page), which creature (the mobs), when it fires (the triggers). */
    private void ask(DevModeScreen screen, NodeKey key, NodeTemplates.Ask ask) {
        switch (ask) {
            case ENTITY, TRIGGER -> inspector.ask(key, ask);
            case CREATURE -> screen.catalog().open(DevUi.text("template.ask_creature"), Catalog.Kind.MOB, (kind, entry) -> {
                if (!key.equals(selected) || editing == null) {
                    return;
                }
                editing.addProperty("entity", entry.id());
                // Its section now opens on sight instead of by an action alone.
                if (editing.get("sections") instanceof com.google.gson.JsonArray sections && !sections.isEmpty()
                        && sections.get(0) instanceof JsonObject first && first.get("unlock") instanceof JsonObject unlock) {
                    com.google.gson.JsonArray on = new com.google.gson.JsonArray();
                    on.add("seen");
                    unlock.add("on", on);
                }
                changed(screen);
            });
            default -> {
            }
        }
    }

    /** The template's definitions with ids nothing has yet, placed where the author stands (for waypoints). */
    private List<NodeTemplates.Created> fresh(Template template) {
        Player player = Minecraft.getInstance().player;
        NodeTemplates.Place place = player != null
                ? new NodeTemplates.Place(new double[]{player.getX(), player.getY(), player.getZ()}, player.level().dimension().location().toString())
                : new NodeTemplates.Place(null, "minecraft:overworld");
        int number = freeNumber();
        List<NodeTemplates.Created> created = NodeTemplates.create(template, number, place, DevUi::str);
        while (created.stream().anyMatch(c -> ClientDevStore.getJson(c.key().type(), c.key().id()) != null)) {
            created = NodeTemplates.create(template, ++number, place, DevUi::str);
        }
        return created;
    }

    /**
     * The quest giver's dialogue for a phase of the quest (see {@link QuestGiver}): a new dialogue, an entry for it in
     * the NPC's binding, one undoable step; then its graph opens to be written.
     */
    void addGiverDialogue(DevModeScreen screen, ResourceLocation quest, QuestGiver.Giver giver, QuestGiver.Phase phase) {
        saveEdits(screen);
        NodeKey npc = giver.npc();
        String bindingBefore = ClientDevStore.getJson(npc.type(), npc.id());
        if (bindingBefore == null) {
            return;
        }
        NodeTemplates.Created dialogue = fresh(Template.DIALOGUE_SCREEN).getFirst();
        NodeKey key = dialogue.key();
        String dialogueJson = DevUi.GSON.toJson(dialogue.json());
        JsonObject binding = ClientDevStore.parseObject(bindingBefore);
        QuestGiver.addEntry(binding, key.id(), quest, phase);
        String bindingAfter = DevUi.GSON.toJson(binding);
        ClientDevStore.putLocal(key.type(), key.id(), dialogueJson);
        DevRequests.save(key.type(), key.id(), dialogueJson, dialogue.text());
        restore(npc, bindingAfter);
        if (npc.equals(selected)) {
            editing = binding;
            dirty = false;
        }
        screen.remember(DevUi.str("history.giver", key.id().getPath()),
                () -> {
                    DevRequests.delete(key.type(), key.id());
                    restore(npc, bindingBefore);
                },
                () -> {
                    ClientDevStore.putLocal(key.type(), key.id(), dialogueJson);
                    DevRequests.restore(key.type(), key.id(), dialogueJson, dialogue.text());
                    restore(npc, bindingAfter);
                });
        rebuildCards();
        screen.openDialogue(key.id());
        screen.status(DevUi.text("status.giver_created", key.id().getPath(), DevUi.str("giver." + phase.name().toLowerCase(Locale.ROOT))), DevUi.OK);
    }

    /** "+ New" in the library: the template's definitions, nowhere on a canvas. */
    void createFromList(DevModeScreen screen, Template template) {
        menuWorldX = 20;
        menuWorldY = 20;
        create(screen, template);
        screen.rebuild();
    }

    void duplicateFromList(DevModeScreen screen, NodeKey source) {
        duplicate(screen, source);
        screen.rebuild();
    }

    /** Saves a new definition, puts it on the canvas (and the open chapter, when the canvas shows it) and selects it. */
    private void place(DevModeScreen screen, NodeTemplates.Created definition, int pickedX, int pickedY) {
        NodeKey key = definition.key();
        int x = DevUi.snap(pickedX);
        int y = DevUi.snap(pickedY);
        String json = DevUi.GSON.toJson(definition.json());
        select(screen, null);
        positions.put(key, new int[]{x, y});
        ClientDevStore.putLocal(key.type(), key.id(), json);
        DevRequests.save(key.type(), key.id(), json, definition.text());
        addToChapter(key, x, y);
        selection.add(key);
        selected = key;
        editing = definition.json();
        screen.showInspector();
        screen.remember(DevUi.str("history.create", key.id().getPath()),
                () -> {
                    positions.remove(key);
                    selection.remove(key);
                    if (key.equals(selected)) {
                        selected = null;
                        editing = null;
                    }
                    DevRequests.delete(key.type(), key.id());
                },
                () -> {
                    positions.put(key, new int[]{x, y});
                    ClientDevStore.putLocal(key.type(), key.id(), json);
                    DevRequests.restore(key.type(), key.id(), json, definition.text());
                    addToChapter(key, x, y);
                });
        rebuildCards();
        screen.status(DevUi.text("status.created", key.id().getPath()), DevUi.OK);
    }

    /** Creates a node next to another and links them; with {@code reverse} the new node links to the old one. */
    private void createLinked(DevModeScreen screen, NodeKey source, Template template, boolean reverse) {
        int[] from = positions.getOrDefault(source, new int[]{menuWorldX, menuWorldY});
        int x = reverse ? from[0] - 5 * DevUi.GRID : from[0] + 5 * DevUi.GRID;
        int y = from[1];
        for (int attempt = 0; attempt < 12 && occupied(x, y); attempt++) {
            y += 4 * DevUi.GRID;
        }
        menuWorldX = x;
        menuWorldY = y;
        NodeKey created = create(screen, template);
        if (created != null) {
            link(screen, reverse ? created : source, reverse ? source : created);
        }
    }

    private boolean occupied(int x, int y) {
        for (Card card : cards) {
            // A node with its title (4 cells wide) and badges: 4 cells by 3.
            if (Math.abs(card.x() - x) < 4 * DevUi.GRID && Math.abs(card.y() - y) < 3 * DevUi.GRID) {
                return true;
            }
        }
        return false;
    }

    private void duplicate(DevModeScreen screen, NodeKey source) {
        String raw = ClientDevStore.getJson(source.type(), source.id());
        if (raw == null) {
            return;
        }
        int number = freeNumber();
        ResourceLocation id = source.id().withPath(source.id().getPath() + "_copy_" + number);
        while (ClientDevStore.getJson(source.type(), id) != null) {
            id = source.id().withPath(source.id().getPath() + "_copy_" + ++number);
        }
        int[] from = positions.getOrDefault(source, new int[]{menuWorldX, menuWorldY});
        place(screen, new NodeTemplates.Created(new NodeKey(source.type(), id), ClientDevStore.parseObject(raw), documentText(source.id()), 0),
                from[0] + 24, from[1] + 24);
    }

    /** Writes the link into the first node's JSON (see {@link StoryEdits#link}). */
    void link(DevModeScreen screen, NodeKey from, NodeKey to) {
        String before = ClientDevStore.getJson(from.type(), from.id());
        if (from.equals(to) || before == null) {
            return;
        }
        JsonObject json = ClientDevStore.parseObject(before);
        if (!StoryEdits.link(json, from, to)) {
            screen.status(DevUi.text("status.no_link"), DevUi.WARN);
            return;
        }
        String after = DevUi.GSON.toJson(json);
        if (from.equals(selected)) {
            editing = json;
            dirty = false;
        }
        ClientDevStore.putLocal(from.type(), from.id(), after);
        DevRequests.save(from.type(), from.id(), after, Optional.empty());
        screen.remember(DevUi.str("history.link", from.id().getPath(), to.id().getPath()),
                () -> {
                    ClientDevStore.putLocal(from.type(), from.id(), before);
                    DevRequests.restore(from.type(), from.id(), before, Optional.empty());
                },
                () -> {
                    ClientDevStore.putLocal(from.type(), from.id(), after);
                    DevRequests.restore(from.type(), from.id(), after, Optional.empty());
                });
        rebuildCards();
        screen.status(DevUi.text("status.linked", from.id().getPath(), to.id().getPath()), DevUi.LINK);
    }

    /** Deletes the selected card, or the chapter whose title is being edited. */
    void deleteSelected(DevModeScreen screen) {
        if (selected != null && selected.type().equals(ClientDevStore.TYPE_CHAPTER)) {
            ResourceLocation id = selected.id();
            selected = null;
            selection.clear();
            editing = null;
            dirty = false;
            deleteChapter(screen, id);
            screen.rebuild();
            return;
        }
        deleteSelection(screen);
    }

    /**
     * Deletes a node's definition. A script block whose handler the scripts still register would come straight back
     * from the code, so it is hidden instead ({@code "hidden": true} in its file; the code stays in KubeJS); returns
     * whether it was.
     */
    private static boolean removeNode(NodeKey key, @Nullable String json) {
        if (json != null && key.type().equals(ClientDevStore.TYPE_SCRIPT) && ClientDevStore.scriptRegistered(key.id())) {
            JsonObject hidden = JsonParser.parseString(json).getAsJsonObject();
            hidden.addProperty("hidden", true);
            DevRequests.restore(key.type(), key.id(), hidden.toString(), Optional.empty());
            return true;
        }
        DevRequests.delete(key.type(), key.id());
        return false;
    }

    private void deleteSelection(DevModeScreen screen) {
        Set<NodeKey> targets = new LinkedHashSet<>(selection);
        if (selected != null) {
            targets.add(selected);
        }
        if (targets.isEmpty()) {
            return;
        }
        record Deleted(NodeKey key, String json, Optional<String> text, int @Nullable [] pos) {}
        List<Deleted> deleted = new ArrayList<>();
        for (NodeKey key : targets) {
            String json = ClientDevStore.getJson(key.type(), key.id());
            if (json != null) {
                int[] pos = positions.get(key);
                deleted.add(new Deleted(key, json, documentText(key.id()), pos != null ? pos.clone() : null));
            }
            positions.remove(key);
            if (removeNode(key, json)) {
                screen.status(DevUi.text("status.script_hidden"), DevUi.OK);
            }
        }
        selected = null;
        selection.clear();
        editing = null;
        dirty = false;
        List<Unlinked> unlinked = unlinkEverywhere(targets);
        rebuildCardsWithout(targets);
        saveChapter();
        if (!deleted.isEmpty()) {
            String label = deleted.size() == 1 ? DevUi.str("history.delete", deleted.getFirst().key().id().getPath())
                    : DevUi.str("history.delete_many", deleted.size());
            screen.remember(label,
                    () -> {
                        for (Deleted node : deleted) {
                            if (node.pos() != null) {
                                positions.put(node.key(), node.pos().clone());
                                addToChapter(node.key(), node.pos()[0], node.pos()[1]);
                            }
                            DevRequests.restore(node.key().type(), node.key().id(), node.json(), node.text());
                        }
                        unlinked.forEach(change -> restore(change.key(), change.before()));
                    },
                    () -> {
                        for (Deleted node : deleted) {
                            positions.remove(node.key());
                            removeNode(node.key(), node.json());
                        }
                        unlinked.forEach(change -> restore(change.key(), change.after()));
                        rebuildCardsWithout(targets);
                        saveChapter();
                    });
        } else {
            screen.rebuild();
        }
        long left = ClientDevStore.all().entrySet().stream().filter(entry -> KINDS.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().entrySet().stream().filter(definition -> !targets.contains(new NodeKey(entry.getKey(), definition.getKey())))
                        .map(definition -> ClientDevStore.parseObject(definition.getValue())))
                .filter(json -> targets.stream().anyMatch(target -> StoryEdits.refersTo(json, target)))
                .count();
        screen.status(left > 0 ? DevUi.text("status.deleted_refs_left", targets.size(), unlinked.size(), left)
                : DevUi.text("status.deleted_unlinked", targets.size(), unlinked.size()), DevUi.BAD);
    }

    /** A definition changed because a node it pointed at was deleted. */
    private record Unlinked(NodeKey key, String before, String after) {}

    /** Takes the deleted nodes out of every other definition (see {@link StoryEdits#unlink}) and saves those. */
    private static List<Unlinked> unlinkEverywhere(Set<NodeKey> deleted) {
        List<Unlinked> changes = new ArrayList<>();
        for (ResourceLocation type : KINDS) {
            ClientDevStore.byType(type).forEach((id, before) -> {
                NodeKey key = new NodeKey(type, id);
                if (deleted.contains(key)) {
                    return;
                }
                JsonObject json = ClientDevStore.parseObject(before);
                boolean changed = false;
                for (NodeKey target : deleted) {
                    changed |= StoryEdits.unlink(json, target);
                }
                if (changed) {
                    changes.add(new Unlinked(key, before, DevUi.GSON.toJson(json)));
                }
            });
        }
        changes.forEach(change -> restore(change.key(), change.after()));
        return changes;
    }

    /** Points every action and condition "Script" of the old handler to the new one, and saves what changed. */
    private static List<Unlinked> renameHook(String oldHook, String newHook) {
        List<Unlinked> changes = new ArrayList<>();
        for (ResourceLocation type : KINDS) {
            ClientDevStore.byType(type).forEach((id, before) -> {
                JsonObject json = ClientDevStore.parseObject(before);
                if (StoryEdits.renameScriptHook(json, oldHook, newHook)) {
                    changes.add(new Unlinked(new NodeKey(type, id), before, DevUi.GSON.toJson(json)));
                }
            });
        }
        changes.forEach(change -> restore(change.key(), change.after()));
        return changes;
    }

    /** Puts a definition back as it was (undo and redo of an edit made outside the inspector). */
    private static void restore(NodeKey key, String json) {
        ClientDevStore.putLocal(key.type(), key.id(), json);
        DevRequests.restore(key.type(), key.id(), json, Optional.empty());
    }

    /** Removes what the selected definition has about the target (a link in the inspector's list). */
    void unlinkSelected(DevModeScreen screen, NodeKey target) {
        if (editing != null && StoryEdits.unlink(editing, target)) {
            changed(screen);
            screen.status(DevUi.text("status.unlinked", target.id().getPath()), DevUi.WARN);
        }
    }

    /** The cards before the server confirms the deletion, so the chapter is saved without them. */
    private void rebuildCardsWithout(Set<NodeKey> removed) {
        rebuildCards();
        cards.removeIf(card -> removed.contains(card.key()));
    }

    // --- chapters

    private void addToChapter(NodeKey key, int x, int y) {
        if (!shows(key)) {
            return;
        }
        ResourceLocation open = chapter();
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, open));
        Chapters.place(open, json, key, x, y);
        chapterNodes.add(key);
        saveChapterJson(open, json);
    }

    /**
     * Writes where the open canvas's cards are now. Places of nodes this canvas does not show (a dialogue in a
     * chapter made before the map showed only quests) stay as they were.
     */
    void saveChapter() {
        ResourceLocation open = chapter();
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, open));
        Map<NodeKey, int[]> placed = Chapters.positions(json);
        if (!UNSORTED.equals(storyChapter) || tab != Tab.STORY) {
            // "Not in a chapter" shows some of the whole story's places: the others stay as they are.
            placed.keySet().removeIf(this::shows);
        }
        for (Card card : cards) {
            placed.put(card.key(), new int[]{card.x(), card.y()});
        }
        Chapters.setPositions(open, json, placed);
        saveChapterJson(open, json);
    }

    private static void saveChapterJson(ResourceLocation id, JsonObject json) {
        String raw = DevUi.GSON.toJson(json);
        ClientDevStore.putLocal(ClientDevStore.TYPE_CHAPTER, id, raw);
        DevRequests.save(ClientDevStore.TYPE_CHAPTER, id, raw, Optional.empty());
    }

    /** A new chapter of the map with the selected quests on it (at their places), opened at once. */
    void createChapter(DevModeScreen screen) {
        ResourceLocation id = Chapters.nextId();
        String number = id.getPath().substring("chapter_".length());
        JsonObject json = new JsonObject();
        ClientDevStore.writeStoryText(json, "title", DevUi.str("chapter.default_title", number),
                ClientDevStore.canonicalKey(ClientDevStore.TYPE_CHAPTER, id, "title"));
        json.addProperty("order", Integer.parseInt(number));
        Map<NodeKey, int[]> placed = new LinkedHashMap<>();
        for (Card card : cards) {
            if (selection.contains(card.key())) {
                placed.put(card.key(), new int[]{card.x(), card.y()});
            }
        }
        Chapters.setPositions(id, json, placed);
        String raw = DevUi.GSON.toJson(json);
        ResourceLocation previous = storyChapter;
        ClientDevStore.putLocal(ClientDevStore.TYPE_CHAPTER, id, raw);
        DevRequests.save(ClientDevStore.TYPE_CHAPTER, id, raw, Optional.empty());
        openChapter(id);
        screen.remember(DevUi.str("history.create_chapter", id.getPath()),
                () -> {
                    storyChapter = previous;
                    DevRequests.delete(ClientDevStore.TYPE_CHAPTER, id);
                },
                () -> {
                    storyChapter = id;
                    DevRequests.restore(ClientDevStore.TYPE_CHAPTER, id, raw, Optional.empty());
                });
        screen.status(DevUi.text("status.chapter_created", DevUi.str("chapter.default_title", number)), DevUi.OK);
    }

    void deleteChapter(DevModeScreen screen, ResourceLocation id) {
        String before = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, id);
        if (before == null) {
            return;
        }
        if (id.equals(storyChapter)) {
            openChapter(Chapters.quests());
        }
        DevRequests.delete(ClientDevStore.TYPE_CHAPTER, id);
        screen.remember(DevUi.str("history.delete_chapter", id.getPath()),
                () -> {
                    storyChapter = id;
                    DevRequests.restore(ClientDevStore.TYPE_CHAPTER, id, before, Optional.empty());
                },
                () -> {
                    if (id.equals(storyChapter)) {
                        storyChapter = Chapters.quests();
                    }
                    DevRequests.delete(ClientDevStore.TYPE_CHAPTER, id);
                });
        rebuildCards();
        screen.status(DevUi.text("status.chapter_deleted", id.getPath()), DevUi.BAD);
    }

    private void openChapter(ResourceLocation id) {
        storyChapter = id;
        loadChapter();
        rebuildCards();
    }

    /** Puts the quest on the chapter, or takes it off; one undoable step. */
    private void setInChapter(DevModeScreen screen, ResourceLocation chapterId, NodeKey quest, boolean in) {
        String before = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, chapterId);
        if (before == null) {
            return;
        }
        JsonObject json = ClientDevStore.parseObject(before);
        if (in) {
            int[] pos = positions.getOrDefault(quest, new int[]{DevUi.GRID, DevUi.GRID});
            Chapters.place(chapterId, json, quest, pos[0], pos[1]);
        } else {
            Map<NodeKey, int[]> placed = Chapters.positions(json);
            placed.remove(quest);
            Chapters.setPositions(chapterId, json, placed);
        }
        String after = DevUi.GSON.toJson(json);
        saveChapterJson(chapterId, json);
        if (chapterId.equals(storyChapter)) {
            loadChapter();
            rebuildCards();
        }
        screen.remember(DevUi.str("history.chapter_nodes", chapterName(chapterId)),
                () -> DevRequests.restore(ClientDevStore.TYPE_CHAPTER, chapterId, before, Optional.empty()),
                () -> DevRequests.restore(ClientDevStore.TYPE_CHAPTER, chapterId, after, Optional.empty()));
        screen.status(DevUi.text(in ? "status.to_chapter" : "status.out_of_chapter", title(quest), chapterName(chapterId)), DevUi.OK);
    }

    private static String chapterName(ResourceLocation id) {
        return ClientDevStore.readStoryText(ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, id)), "title", id.getPath());
    }

    /** Selects the chapter itself, so the inspector edits its title. */
    void renameChapter(DevModeScreen screen, ResourceLocation id) {
        openChapter(id);
        select(screen, new NodeKey(ClientDevStore.TYPE_CHAPTER, id));
        screen.rebuild();
    }

    // --- tabs

    void openTab(DevModeScreen screen, Tab next) {
        // Each canvas keeps where it was looked at.
        views.put(tab, new double[]{panX, panY, zoom});
        double[] view = views.getOrDefault(next, new double[]{40, 30, 1});
        panX = view[0];
        panY = view[1];
        setZoom((float) view[2]);
        tab = next;
        loadChapter();
        rebuildCards();
        screen.rebuild();
    }

    /** Opens the tab with the node in the middle of its canvas, selected; on the map, in "All quests". */
    void reveal(DevModeScreen screen, Tab target, NodeKey key) {
        if (target == Tab.STORY && !chapterNodes.contains(key)) {
            storyChapter = Chapters.quests();
        }
        openTab(screen, target);
        select(screen, key);
        for (Card card : cards) {
            if (card.key().equals(key)) {
                centerOn(screen, card.x() + NODE_W / 2.0, card.y() + NODE_H / 2.0);
            }
        }
        screen.rebuild();
    }

    /**
     * Shows a node wherever it is (a search row, a problem): a quest or a story event on the map, in a chapter that has
     * it, centred and selected; anything else selected in the inspector, the map centred on a node it belongs to.
     */
    void revealAnywhere(DevModeScreen screen, NodeKey key) {
        if (tab == Tab.ALL) {
            reveal(screen, Tab.ALL, key);
            return;
        }
        if (spine(key) && !chapterNodes.contains(key) && !Chapters.quests().equals(storyChapter)) {
            ResourceLocation home = unsorted().contains(key) ? UNSORTED : Chapters.quests();
            for (Map.Entry<ResourceLocation, JsonObject> entry : Chapters.sorted()) {
                if (Chapters.positions(entry.getValue()).containsKey(key)) {
                    home = entry.getKey();
                    break;
                }
            }
            openChapter(home);
        }
        select(screen, key);
        for (Card card : cards) {
            if (card.key().equals(key) || card.own().contains(key)) {
                centerOn(screen, card.x() + NODE_W / 2.0, card.y() + NODE_H / 2.0);
                break;
            }
        }
        screen.rebuild();
    }

    /**
     * A search row let go on the canvas: a quest or a story event goes on the open chapter there, or moves there when
     * the map shows it already; anything else is a badge, never a node of the map, and is only shown.
     */
    void dropOnCanvas(DevModeScreen screen, NodeKey key, double mouseX, double mouseY) {
        if (tab != Tab.STORY || !spine(key)) {
            screen.status(DevUi.text("status.drop_not_spine"), DevUi.WARN);
            return;
        }
        int x = DevUi.snap(worldX(mouseX) - NODE_W / 2.0);
        int y = DevUi.snap(worldY(mouseY) - NODE_H / 2.0);
        boolean realChapter = !Chapters.hidden(storyChapter) && !UNSORTED.equals(storyChapter);
        if (realChapter && !chapterNodes.contains(key)) {
            positions.put(key, new int[]{x, y});
            setInChapter(screen, storyChapter, key, true);
        } else if (cards.stream().anyMatch(card -> card.key().equals(key))) {
            int[] before = positions.getOrDefault(key, new int[]{x, y}).clone();
            positions.put(key, new int[]{x, y});
            rebuildCards();
            saveChapter();
            ResourceLocation moved = chapter();
            screen.remember(DevUi.str("history.move", 1), () -> moveBack(moved, Map.of(key, before)), () -> moveBack(moved, Map.of(key, new int[]{x, y})));
        } else {
            screen.status(DevUi.text("status.drop_in_chapter", title(key)), DevUi.WARN);
            return;
        }
        select(screen, key);
        rebuildCards();
        screen.rebuild();
    }

    // --- widgets and drawing

    // --- the progress layer: where a player stands, on the map

    /** The player whose progress the map shows; null when the layer is off. Kept while the game runs. */
    private @Nullable String progressOf;
    private int progressTicks;

    /** Off, then each online player (the author first), then off again. */
    private void nextProgressPlayer(DevModeScreen screen) {
        List<String> names = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            names.add(minecraft.player.getGameProfile().getName());
        }
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().getOnlinePlayers().stream().map(info -> info.getProfile().getName()).sorted()
                    .filter(name -> !names.contains(name)).forEach(names::add);
        }
        int at = progressOf == null ? -1 : names.indexOf(progressOf);
        progressOf = at + 1 < names.size() ? names.get(at + 1) : null;
        if (progressOf != null) {
            DevRequests.requestProgress(progressOf);
        }
        progressTicks = 0;
        screen.rebuild();
    }

    @Override
    void tick(DevModeScreen screen) {
        if (progressOf != null && tab == Tab.STORY && ++progressTicks % PROGRESS_REFRESH == 0) {
            DevRequests.requestProgress(progressOf);
        }
    }

    /** Whether the progress layer shows a quest the player does not know yet: drawn dim. */
    private boolean unknownToPlayer(Card card) {
        String player = progressOf;
        return player != null && card.key().type().equals(ClientDevStore.TYPE_QUEST) && ClientDevStore.hasProgress(player)
                && ClientDevStore.progress(player, card.key().id()) == null;
    }

    /** A quest's mark on the progress layer, above its shape: its state and stage. */
    private void renderProgress(GuiGraphics graphics, Font font, Card card) {
        String player = progressOf;
        if (player == null || !card.key().type().equals(ClientDevStore.TYPE_QUEST) || !ClientDevStore.hasProgress(player)) {
            return;
        }
        QuestProgress progress = ClientDevStore.progress(player, card.key().id());
        if (progress == null) {
            return;
        }
        String mark = switch (progress.status()) {
            case COMPLETED -> "✔";
            case FAILED -> "✘";
            case ACTIVE -> "▶ " + progress.stage().orElse("");
        };
        int color = switch (progress.status()) {
            case COMPLETED -> DevUi.GOOD;
            case FAILED -> DevUi.ERROR;
            case ACTIVE -> DevUi.ACCENT;
        };
        String shown = font.plainSubstrByWidth(mark, TITLE_W - 6);
        int width = font.width(shown) + 6;
        int x = card.cx() - width / 2;
        int y = card.cy() - card.look().radius() - 13;
        graphics.fill(x, y, x + width, y + 10, color);
        graphics.drawString(font, shown, x + 3, y + 1, DevUi.DARK_TEXT, false);
    }

    @Override
    void addWidgets(DevModeScreen screen) {
        // The top bar's right end, small signs with their words on hover, from the right: the problems, the search,
        // every link or back to the chapters, whose progress the map shows.
        int x = screen.width - 4;
        int problems = ClientDevStore.issues().size();
        boolean errors = ClientDevStore.issues().stream().anyMatch(line -> line.startsWith("[ERROR]"));
        Component problemsLabel = problems == 0 ? Component.literal("✔") : Component.literal("⚠ " + problems)
                .withColor(errors ? DevUi.ERROR : DevUi.WARN);
        x = topButton(screen, x, problemsLabel, problems == 0 ? "top.problems_none.tooltip" : "top.problems.tooltip",
                () -> screen.openFinder(StoryFinder.Mode.PROBLEMS));
        x = topButton(screen, x, Component.literal("🔍"), "top.search.tooltip", () -> screen.openFinder(StoryFinder.Mode.SEARCH));
        // In the middle of the top bar, in words (November 17, 2026, the author; before, a bare 🕸 among the signs at the
        // right): what the map shows instead, the chapters or every link.
        Component toggle = DevUi.text(tab == Tab.STORY ? "canvas.show_all_links" : "canvas.show_chapters");
        int toggleW = DevButton.oddWidth(screen.font(), toggle) + 8;
        screen.add(Button.builder(toggle, b -> {
                    saveEdits(screen);
                    openTab(screen, tab == Tab.STORY ? Tab.ALL : Tab.STORY);
                }).tooltip(Tooltip.create(DevUi.text(tab == Tab.STORY ? "canvas.all_links.tooltip" : "canvas.back_to_chapters.tooltip")))
                .bounds((screen.width - toggleW) / 2, 3, toggleW, 19).build());
        if (tab == Tab.STORY) {
            Component who = progressOf == null ? Component.literal("👤") : Component.literal("👤 " + progressOf);
            topButton(screen, x, who, "progress.tooltip", () -> nextProgressPlayer(screen));
        }
        if (inspectorVisible(screen)) {
            inspector.addWidgets(screen);
        }
    }

    /** A small button of the top bar's right end, right-aligned at {@code right}; returns where the next one ends. */
    private static int topButton(DevModeScreen screen, int right, Component label, String tooltip, Runnable action) {
        int w = DevButton.oddWidth(screen.font(), label);
        screen.add(Button.builder(label, b -> action.run()).tooltip(Tooltip.create(DevUi.text(tooltip)))
                .bounds(right - w, 3, w, 19).build());
        return right - w - 2;
    }

    @Override
    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        if (tab == Tab.STORY) {
            panel.hover(screen, mouseX, mouseY, dragging != null || linkingFrom != null || boxSelecting);
        }
        renderCanvas(screen, graphics, mouseX, mouseY);
        if (inspectorVisible(screen)) {
            inspector.render(screen, graphics);
        }
        if (tab == Tab.STORY) {
            panel.render(screen, graphics, mouseX, mouseY);
        }
    }

    /**
     * The chapter strip, only on the map (November 17, 2026, the author: it is for chapters, and in "all links" a click
     * on it threw the author back to the map unasked): every link is shown on the whole width.
     */
    private boolean stripHolds(DevModeScreen screen, double mouseX, double mouseY) {
        return tab == Tab.STORY && panel.holds(screen, mouseX, mouseY);
    }

    /** "On the map" from the search: a quest or a story event goes on the open chapter, in the middle of what is seen. */
    void putOnMap(DevModeScreen screen, NodeKey key) {
        if (tab != Tab.STORY) {
            openTab(screen, Tab.STORY);
        }
        dropOnCanvas(screen, key, (canvasLeft() + screen.canvasRight()) / 2.0, (DevUi.TOP_BAR_H + screen.height) / 2.0);
    }

    /** The badges under a card of the map: its own nodes, "+N" for the rest; a quest starting by itself gets ⚡ first. */
    private static List<Badge> badges(Card card, Font font) {
        List<Badge> list = new ArrayList<>();
        int y = card.y() + NODE_H + 12;
        // Centred under the node: their whole width first.
        int count = (card.selfStart() ? 1 : 0) + Math.min(card.own().size(), MAX_BADGES);
        boolean more = card.own().size() > MAX_BADGES;
        int total = count == 0 ? 0 : (more ? (count - 1) * (BADGE_W + 1) + font.width("+" + (card.own().size() - MAX_BADGES + 1)) + 4
                : count * (BADGE_W + 1) - 1);
        int x = card.cx() - total / 2;
        if (card.selfStart()) {
            list.add(new Badge(card, x, y, BADGE_W, null, List.of()));
            x += BADGE_W + 1;
        }
        List<NodeKey> own = card.own();
        int shown = own.size() > MAX_BADGES ? MAX_BADGES - 1 : own.size();
        for (int i = 0; i < shown; i++) {
            list.add(new Badge(card, x, y, BADGE_W, own.get(i), List.of()));
            x += BADGE_W + 1;
        }
        if (own.size() > shown) {
            List<NodeKey> rest = own.subList(shown, own.size());
            list.add(new Badge(card, x, y, font.width("+" + rest.size()) + 4, null, List.copyOf(rest)));
        }
        return list;
    }

    private static boolean hasProblem(NodeKey key) {
        return !ClientDevStore.issuesFor(key.type(), key.id()).isEmpty() || scriptBroken(key);
    }

    private @Nullable Badge badgeAt(double worldX, double worldY, Font font) {
        if (tab != Tab.STORY) {
            return null;
        }
        for (int i = cards.size() - 1; i >= 0; i--) {
            for (Badge badge : badges(cards.get(i), font)) {
                if (DevUi.inside(worldX, worldY, badge.x(), badge.y(), badge.width(), BADGE_H)) {
                    return badge;
                }
            }
        }
        return null;
    }

    /** What a badge is, for its tooltip: the node's kind and title, or what the mark means. */
    private static Component badgeTooltip(Badge badge) {
        if (badge.node() == null) {
            return badge.rest().isEmpty() ? DevUi.text("badge.self_start") : DevUi.text("badge.more", badge.rest().size());
        }
        NodeKey key = badge.node();
        String line = glyph(key.type()) + " " + DevUi.str("kind." + key.type().getPath()) + ": " + title(key);
        List<String> issues = ClientDevStore.issuesFor(key.type(), key.id());
        return Component.literal(issues.isEmpty() ? line : line + "\n⚠ " + issues.getFirst());
    }

    private void renderCanvas(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        beginCanvas(screen, graphics, DevUi.GRID_LINE);
        Map<NodeKey, Card> byKey = new HashMap<>();
        for (Card card : cards) {
            byKey.put(card.key(), card);
        }
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
        boolean idle = onCanvas(screen, mouseX, mouseY) && dragging == null && linkingFrom == null && !boxSelecting && !screen.modalOpen() && !stripHolds(screen, mouseX, mouseY);
        // Hovering a node lights its links and dims what is not linked with it.
        Card focus = idle ? cardAt(worldX, worldY) : null;
        Set<NodeKey> lit = new java.util.HashSet<>();
        if (focus != null) {
            lit.add(focus.key());
            for (Arrow arrow : arrows) {
                if (arrow.from().equals(focus.key())) {
                    lit.add(arrow.to());
                } else if (arrow.to().equals(focus.key())) {
                    lit.add(arrow.from());
                }
            }
        }
        List<Runnable> words = new ArrayList<>();
        for (Arrow arrow : arrows) {
            Card from = byKey.get(arrow.from());
            Card to = byKey.get(arrow.to());
            if (from != null && to != null) {
                boolean bright = focus != null ? arrow.from().equals(focus.key()) || arrow.to().equals(focus.key())
                        : selection.contains(arrow.from()) || selection.contains(arrow.to()) || arrow.from().equals(selected) || arrow.to().equals(selected);
                int color = bright ? GOLD_LIGHT : focus != null ? DevUi.blend(OCHRE_DARK, DevUi.BACKGROUND, 0.35f) : 0x8C775025;
                double[] a = from.towards(to.cx() + 0.5, to.cy() + 0.5, 4);
                double[] b = to.towards(from.cx() + 0.5, from.cy() + 0.5, 4);
                // Straight and quiet, always there; their words only while lit (November 18, 2026).
                NodeShapes.link(graphics, a[0], a[1], b[0], b[1], color);
                String label = bright ? arrow.litLabel() : "";
                if (!label.isEmpty()) {
                    int mx = (int) Math.round((a[0] + b[0]) / 2);
                    int my = (int) Math.round((a[1] + b[1]) / 2) + 6;
                    words.add(() -> backedText(graphics, font, label, mx, my, GOLD_LIGHT));
                }
            }
        }
        Card source = linkingFrom != null ? byKey.get(linkingFrom) : null;
        if (source != null) {
            double[] a = source.towards(worldX, worldY, 4);
            NodeShapes.link(graphics, a[0], a[1], worldX, worldY, GOLD_LIGHT);
        }
        Badge hoveredBadge = idle ? badgeAt(worldX, worldY, font) : null;
        for (Card card : cards) {
            boolean primary = card.key().equals(selected);
            boolean multi = selection.contains(card.key());
            boolean hovered = focus != null && focus.key().equals(card.key());
            boolean dim = focus != null && !lit.contains(card.key()) || tab == Tab.STORY && unknownToPlayer(card);
            boolean problem = card.hasError() || card.own().stream().anyMatch(StoryView::hasProblem);
            renderNode(graphics, font, card, primary, multi, hovered, dim, problem);
            if (tab == Tab.STORY && !dim) {
                renderProgress(graphics, font, card);
            }
            if (tab == Tab.STORY) {
                renderBadges(graphics, font, card, hoveredBadge, dim);
            }
        }
        // The lit links' words over the nodes, so a node never hides them.
        words.forEach(Runnable::run);
        if (boxSelecting) {
            int x1 = (int) Math.min(boxStartX, boxEndX);
            int y1 = (int) Math.min(boxStartY, boxEndY);
            int x2 = (int) Math.max(boxStartX, boxEndX);
            int y2 = (int) Math.max(boxStartY, boxEndY);
            graphics.fill(x1, y1, x2, y2, 0x33E1B34D);
            DevUi.horizontal(graphics, x1, x2, y1, 0xFFE1B34D);
            DevUi.horizontal(graphics, x1, x2, y2, 0xFFE1B34D);
            DevUi.vertical(graphics, x1, y1, y2, 0xFFE1B34D);
            DevUi.vertical(graphics, x2, y1, y2, 0xFFE1B34D);
        }
        endCanvas(graphics);
        if (tab == Tab.STORY && cards.isEmpty()) {
            int left = canvasLeft() + 16;
            int y = DevUi.TOP_BAR_H + 26;
            for (FormattedCharSequence line : font.split(DevUi.text(Chapters.quests().equals(storyChapter) ? "map.empty" : "map.empty_chapter"),
                    screen.canvasRight() - left - 16)) {
                graphics.drawString(font, line, left, y, DevUi.HINT, false);
                y += 11;
            }
        }
        if (hoveredBadge != null) {
            graphics.renderTooltip(font, font.split(badgeTooltip(hoveredBadge), 240), mouseX, mouseY);
        } else if (focus != null) {
            graphics.renderComponentTooltip(font, nodeTooltip(focus), mouseX, mouseY);
        }
    }

    static final int GOLD_GLINT = 0xFFFFF4C4;
    static final int GOLD_LIGHT = 0xFFFFE08A;
    static final int GOLD = 0xFFE1B34D;
    static final int OCHRE = 0xFFB08031;
    static final int OCHRE_DARK = 0xFF775025;
    /** A selected node's inside: a warm lighter brown, so its item still reads. */
    static final int SELECTED_FILL = 0xFF3F2C18;
    /** A node's inside: a little lighter than the ground. */
    static final int NODE_FILL = 0xFF251B14;

    /**
     * A node (November 18, 2026, with the author, after the skill trees of their references): a dark shape in a thin
     * bronze ring (a main quest's gold, red when it or one of its own nodes has a problem), its gold icon, its title
     * under it on a patch of the ground, so links pass under the words. Hovered: a warm glow, the ring and the icon a
     * step lighter, its link port. Selected: the shape gold, its icon dark. Dim (another node hovered and this one not
     * linked with it, or a quest the player does not know yet): ring, icon and title faded.
     */
    private void renderNode(GuiGraphics graphics, Font font, Card card, boolean primary, boolean multi, boolean hovered, boolean dim,
                            boolean problem) {
        drawNode(graphics, font, card.look(), card.item(), card.cx(), card.cy(), card.title(), TITLE_W, primary, multi, hovered, dim,
                problem, card.missingTranslation());
        if (hovered || primary) {
            int px = card.rightX();
            graphics.fill(px - 3, card.cy() - 3, px + 4, card.cy() + 4, DevUi.DARK_TEXT);
            graphics.fill(px - 2, card.cy() - 2, px + 3, card.cy() + 3, GOLD_LIGHT);
        }
    }

    /**
     * A node as every canvas draws it (the story's, a quest's inside, a dialogue's; November 19, 2026, in the manner of
     * FTB Quests): its shape centred on the pixel {@code cx, cy}, dark, in a thin bronze ring (gold when selected or
     * hovered, red when it or one of its own nodes has a problem), its item in the middle, its title under it
     * ({@code titleWidth} at most) on a patch of the ground. Hovered: a warm glow and the shape lit over its item.
     * Dim: the shape shaded over its item, its title faded.
     */
    static void drawNode(GuiGraphics graphics, Font font, Look look, ItemStack item, int cx, int cy, String title, int titleWidth,
                         boolean primary, boolean multi, boolean hovered, boolean dim, boolean problem, boolean missingTranslation) {
        int ringColor = problem ? DevUi.ERROR : primary ? GOLD_GLINT : hovered || multi ? GOLD_LIGHT : OCHRE;
        int glow = hovered ? 0x66E1B34D : 0;
        NodeShapes.draw(graphics, look.shape(), look.radius(), cx, cy, glow, primary ? SELECTED_FILL : NODE_FILL, ringColor);
        graphics.pose().pushPose();
        // The item behind the canvas's own overlays and words (an item is drawn far in front otherwise).
        graphics.pose().translate(0.5f, 0.5f - NodeShapes.iconLift(look.shape(), look.radius()), -140);
        graphics.renderItem(item, cx - 8, cy - 8);
        graphics.pose().popPose();
        if (hovered) {
            NodeShapes.overlay(graphics, look.shape(), look.radius(), cx, cy, 0x30FFF4C4);
        } else if (dim) {
            NodeShapes.overlay(graphics, look.shape(), look.radius(), cx, cy, 0x99140E0A);
        }
        if (missingTranslation) {
            int dot = cx + look.radius() - 2;
            graphics.fill(dot, cy - look.radius(), dot + 3, cy - look.radius() + 3, DevUi.WARN);
        }
        if (!title.isEmpty()) {
            String shown = font.plainSubstrByWidth(title, titleWidth);
            int titleColor = hovered || primary ? DevUi.TEXT : dim ? DevUi.blend(DevUi.LABEL, DevUi.BACKGROUND, 0.5f) : DevUi.LABEL;
            backedText(graphics, font, shown, cx, cy + look.radius() + 5, titleColor);
        }
    }

    /** Words centred on {@code cx}, on a patch of the canvas's ground (lines under them stop at its edge). */
    static void backedText(GuiGraphics graphics, Font font, String text, int cx, int y, int color) {
        int w = font.width(text);
        int x = cx - w / 2 + 1;
        graphics.fill(x - 2, y - 2, x + w + 1, y + 9, DevUi.BACKGROUND);
        graphics.drawString(font, text, x, y, color, false);
    }

    /** What a hovered node is: its whole title, its kind, its id, its first problem. */
    private static List<Component> nodeTooltip(Card card) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(card.title()).withColor(DevUi.TEXT));
        lines.add(Component.literal(card.badge()).withColor(card.color()));
        lines.add(Component.literal(card.key().id().toString()).withColor(DevUi.HINT));
        List<String> issues = ClientDevStore.issuesFor(card.key().type(), card.key().id());
        if (!issues.isEmpty()) {
            lines.add(Component.literal("⚠ " + issues.getFirst()).withColor(DevUi.ERROR));
        }
        return lines;
    }

    /** A card's badges: each node's sign, a red dot on one with a problem, the hovered one lit. */
    private void renderBadges(GuiGraphics graphics, Font font, Card card, @Nullable Badge hovered, boolean dim) {
        for (Badge badge : badges(card, font)) {
            boolean lit = hovered != null && hovered.card().equals(card) && hovered.x() == badge.x()
                    || badge.node() != null && (badge.node().equals(selected) || selection.contains(badge.node()));
            float k = dim ? 0.35f : 1f;
            graphics.fill(badge.x(), badge.y(), badge.x() + badge.width(), badge.y() + BADGE_H, lit ? DevUi.ROW_SELECTED : DevUi.CARD);
            graphics.fill(badge.x(), badge.y() + BADGE_H - 1, badge.x() + badge.width(), badge.y() + BADGE_H,
                    DevUi.blend(lit ? DevUi.WARN : DevUi.CARD_EDGE, DevUi.BACKGROUND, k));
            String sign = badge.node() != null ? glyph(badge.node().type()) : badge.rest().isEmpty() ? "⚡" : "+" + badge.rest().size();
            int signColor = DevUi.blend(badge.node() == null && badge.rest().isEmpty() ? DevUi.WARN : lit ? DevUi.ACCENT : DevUi.LABEL,
                    DevUi.CARD, k);
            String icon = DevIcons.split(sign).icon();
            if (icon != null) {
                DevIcons.draw(graphics, icon, badge.x() + (badge.width() - DevIcons.SIZE) / 2, badge.y() + 1, signColor);
            } else {
                graphics.drawString(font, sign, badge.x() + (badge.width() - font.width(sign)) / 2 + 1, badge.y() + 2, signColor, false);
            }
            boolean problem = badge.node() != null ? hasProblem(badge.node()) : badge.rest().stream().anyMatch(StoryView::hasProblem);
            if (problem) {
                graphics.fill(badge.x() + badge.width() - 3, badge.y(), badge.x() + badge.width(), badge.y() + 3, DevUi.ERROR);
            }
        }
    }

    /** Arrow labels come from the store as link kinds; known kinds are translated. */
    static String edgeLabel(String kind) {
        String key = "storybound.dev.edge." + kind;
        return Language.getInstance().has(key) ? Component.translatable(key).getString() : kind;
    }

    /**
     * A link in the inspector's words (November 16, 2026; before, "→ quest_52 (stage)"): what it is to the selected
     * node, as seen from it ({@code outgoing}: the selected node holds the link), else the link's own name.
     */
    static String linkPhrase(String kind, boolean outgoing) {
        String key = "storybound.dev.links.rel." + kind + (outgoing ? ".out" : ".in");
        return Language.getInstance().has(key) ? Component.translatable(key).getString() : edgeLabel(kind);
    }

    // --- input

    private @Nullable Card cardAt(double worldX, double worldY) {
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (card.holds(worldX, worldY)) {
                return card;
            }
        }
        return null;
    }

    @Override
    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button) {
        if (stripHolds(screen, mouseX, mouseY)) {
            return clickLeftPanel(screen, mouseX, mouseY, button);
        }
        if (!onCanvas(screen, mouseX, mouseY)) {
            return false;
        }
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
        Badge badge = badgeAt(worldX, worldY, screen.font());
        if (badge != null && button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            clickBadge(screen, badge, mouseX, mouseY);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            menuWorldX = (int) Math.round(worldX);
            menuWorldY = (int) Math.round(worldY);
            screen.openMenu((int) mouseX, (int) mouseY, menu(screen, cardAt(worldX, worldY)));
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            startPan(mouseX, mouseY);
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (card.onPort(worldX, worldY)) {
                linkingFrom = card.key();
                return true;
            }
            if (card.holds(worldX, worldY)) {
                clickCard(screen, card, worldX, worldY);
                return true;
            }
        }
        if (Screen.hasShiftDown()) {
            boxSelecting = true;
            boxStartX = boxEndX = worldX;
            boxStartY = boxEndY = worldY;
            return true;
        }
        if (selection.size() > 1) {
            selection.clear();
            if (selected != null) {
                selection.add(selected);
            }
            screen.rebuild();
        }
        startPan(mouseX, mouseY);
        return true;
    }

    private boolean clickLeftPanel(DevModeScreen screen, double mouseX, double mouseY, int button) {
        return panel.mouseClicked(screen, mouseX, mouseY, button);
    }

    /**
     * A badge: its node shows in the inspector (a double click opens a dialogue's graph); "+N" lists the rest to pick
     * from; ⚡ only says what it means.
     */
    private void clickBadge(DevModeScreen screen, Badge badge, double mouseX, double mouseY) {
        if (badge.node() == null) {
            if (!badge.rest().isEmpty()) {
                List<MenuItem> items = new ArrayList<>();
                for (NodeKey key : badge.rest()) {
                    items.add(MenuItem.of(Component.literal(glyph(key.type()) + " " + title(key)), () -> {
                        select(screen, key);
                        screen.rebuild();
                    }));
                }
                screen.openMenu((int) mouseX, (int) mouseY, items);
            }
            return;
        }
        NodeKey key = badge.node();
        long now = System.currentTimeMillis();
        if (key.equals(lastClicked) && now - lastClickTime < DOUBLE_CLICK_MS && key.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            lastClicked = null;
            screen.openDialogue(key.id());
            return;
        }
        lastClickTime = now;
        lastClicked = key;
        select(screen, key);
        screen.rebuild();
    }

    private void clickCard(DevModeScreen screen, Card card, double worldX, double worldY) {
        NodeKey key = card.key();
        long now = System.currentTimeMillis();
        if (key.equals(lastClicked) && now - lastClickTime < DOUBLE_CLICK_MS && key.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            lastClicked = null;
            screen.openDialogue(key.id());
            return;
        }
        if (key.equals(lastClicked) && now - lastClickTime < DOUBLE_CLICK_MS && key.type().equals(ClientDevStore.TYPE_QUEST)) {
            lastClicked = null;
            screen.openQuest(key.id());
            return;
        }
        lastClickTime = now;
        lastClicked = key;
        screen.showInspector();
        if (Screen.hasShiftDown()) {
            if (selection.remove(key)) {
                selectFromSet(screen, key.equals(selected) ? (selection.isEmpty() ? null : selection.iterator().next()) : selected);
            } else {
                selection.add(key);
                selectFromSet(screen, key);
            }
            screen.rebuild();
            return;
        }
        if (!selection.contains(key)) {
            select(screen, key);
        } else if (!key.equals(selected)) {
            selectFromSet(screen, key);
        }
        dragging = key;
        dragOffsetX = worldX - card.x();
        dragOffsetY = worldY - card.y();
        dragStart.clear();
        for (NodeKey moved : selection) {
            int[] pos = positions.get(moved);
            if (pos != null) {
                dragStart.put(moved, pos.clone());
            }
        }
        screen.rebuild();
    }

    private List<MenuItem> menu(DevModeScreen screen, @Nullable Card clicked) {
        List<MenuItem> items = new ArrayList<>();
        if (selection.size() > 1) {
            items.add(MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("menu.delete_selected", selection.size())), () -> deleteSelection(screen)));
            items.add(MenuItem.of(Component.literal("✕ ").append(DevUi.text("menu.clear_selection")), () -> {
                select(screen, null);
                screen.rebuild();
            }));
            return items;
        }
        if (clicked == null) {
            // The map makes its own nodes, quests and story events; what they use is made from a node's menu or the search's "+".
            return templateMenu(screen, tab == Tab.STORY ? Set.of(ClientDevStore.TYPE_QUEST, ClientDevStore.TYPE_EVENT) : null, true);
        }
        NodeKey target = clicked.key();
        select(screen, target);
        screen.rebuild();
        String path = target.id().getPath();
        items.add(MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("menu.delete", path)), () -> deleteSelection(screen)));
        items.add(MenuItem.of(Component.literal("📋 ").append(DevUi.text("menu.duplicate", path)), () -> duplicate(screen, target)));
        if (tab == Tab.STORY) {
            items.add(MenuItem.of(Component.literal("🕸 ").append(DevUi.text("menu.show_links")), () -> reveal(screen, Tab.ALL, target)));
            if (!Chapters.hidden(storyChapter)) {
                ResourceLocation open = storyChapter;
                items.add(MenuItem.of(Component.literal("📤 ").append(DevUi.text("menu.out_of_chapter")), () -> setInChapter(screen, open, target, false)));
            }
            for (Map.Entry<ResourceLocation, JsonObject> entry : Chapters.sorted()) {
                if (!entry.getKey().equals(storyChapter) && !Chapters.positions(entry.getValue()).containsKey(target)) {
                    ResourceLocation id = entry.getKey();
                    items.add(MenuItem.of(Component.literal("📁 ").append(DevUi.text("menu.to_chapter", chapterName(id))),
                            () -> setInChapter(screen, id, target, true)));
                }
            }
        } else if (target.type().equals(ClientDevStore.TYPE_QUEST)) {
            items.add(MenuItem.of(Component.literal("📜 ").append(DevUi.text("menu.show_on_map")), () -> reveal(screen, Tab.STORY, target)));
            items.add(MenuItem.of(Component.literal("🔎 ").append(DevUi.text("menu.inside_quest")), () -> screen.openQuest(target.id())));
        }
        ResourceLocation type = target.type();
        if (type.equals(ClientDevStore.TYPE_NPC)) {
            items.add(linked(screen, target, "💬", "menu.link.dialogue", Template.DIALOGUE_SCREEN, false));
            items.add(linked(screen, target, "💭", "menu.link.bubble", Template.DIALOGUE_BUBBLE, false));
            items.add(linked(screen, target, "🗨", "menu.link.phrases", Template.PHRASES, false));
        } else if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            items.add(MenuItem.of(Component.literal("⤢ ").append(DevUi.text("menu.open_dialogue")), () -> screen.openDialogue(target.id())));
            items.add(linked(screen, target, "📜", "menu.link.quest_main", Template.QUEST_MAIN, false));
            items.add(linked(screen, target, "🗡", "menu.link.quest_side", Template.QUEST_SIDE, false));
            items.add(linked(screen, target, "📖", "menu.link.give_document", Template.DOCUMENT, false));
            items.add(linked(screen, target, "🧑", "menu.link.new_npc", Template.NPC, true));
            items.add(linked(screen, target, "⚙", "menu.link.runs_script", Template.SCRIPT, false));
        } else if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
            items.add(linked(screen, target, "📜", "menu.link.quest_main", Template.QUEST_MAIN, false));
            items.add(linked(screen, target, "🗡", "menu.link.quest_side", Template.QUEST_SIDE, false));
            items.add(linked(screen, target, "💬", "menu.link.from_dialogue", Template.DIALOGUE_SCREEN, true));
        } else if (type.equals(ClientDevStore.TYPE_QUEST)) {
            items.add(linked(screen, target, "📍", "menu.link.waypoint", Template.WAYPOINT, false));
            items.add(linked(screen, target, "📜", "menu.link.next_quest", Template.QUEST_MAIN, false));
            items.add(linked(screen, target, "🗡", "menu.link.quest_side", Template.QUEST_SIDE, false));
            items.add(linked(screen, target, "🐉", "menu.link.boss", Template.BOSS, false));
            items.add(linked(screen, target, "💬", "menu.link.started_by_dialogue", Template.DIALOGUE_SCREEN, true));
            items.add(linked(screen, target, "📖", "menu.link.started_by_document", Template.DOCUMENT, true));
            items.add(linked(screen, target, "⚙", "menu.link.runs_script_on_complete", Template.SCRIPT, false));
        } else if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            items.add(linked(screen, target, "📜", "menu.link.new_quest", Template.QUEST_MAIN, true));
        } else if (type.equals(ClientDevStore.TYPE_BOSS)) {
            items.add(linked(screen, target, "🗡", "menu.link.hunt_quest", Template.QUEST_SIDE, true));
        } else if (type.equals(ClientDevStore.TYPE_SCRIPT)) {
            items.add(linked(screen, target, "📜", "menu.link.script_leads_to_quest", Template.QUEST_MAIN, false));
            items.add(linked(screen, target, "💬", "menu.link.script_run_by_dialogue", Template.DIALOGUE_SCREEN, true));
        }
        return items;
    }

    /** The templates making definitions of the kind; all of them without one. */
    static List<Template> templatesFor(@Nullable ResourceLocation kind) {
        return TEMPLATES.stream().filter(item -> kind == null || item.kind().equals(kind)).map(TemplateItem::template).toList();
    }

    /**
     * "New …" lines for the kinds (all kinds without them): on the canvas at the menu's place, from the search's "+"
     * nowhere (shown in the inspector; a quest or a story event also goes on the open chapter).
     */
    List<MenuItem> templateMenu(DevModeScreen screen, @Nullable Set<ResourceLocation> kinds, boolean onCanvas) {
        List<MenuItem> items = new ArrayList<>();
        for (TemplateItem item : TEMPLATES) {
            if (kinds == null || kinds.contains(item.kind())) {
                items.add(MenuItem.of(Component.literal(item.icon() + " ").append(DevUi.text(item.label())), () -> {
                    if (onCanvas) {
                        create(screen, item.template());
                        screen.rebuild();
                    } else {
                        createFromList(screen, item.template());
                    }
                }));
            }
        }
        return items;
    }

    private MenuItem linked(DevModeScreen screen, NodeKey source, String icon, String label, Template template, boolean reverse) {
        return MenuItem.of(Component.literal(icon + " ").append(DevUi.text(label)), () -> {
            createLinked(screen, source, template, reverse);
            screen.rebuild();
        });
    }

    @Override
    boolean mouseDragged(DevModeScreen screen, double mouseX, double mouseY) {
        if (boxSelecting) {
            boxEndX = worldX(mouseX);
            boxEndY = worldY(mouseY);
            double minX = Math.min(boxStartX, boxEndX);
            double minY = Math.min(boxStartY, boxEndY);
            double maxX = Math.max(boxStartX, boxEndX);
            double maxY = Math.max(boxStartY, boxEndY);
            selection.clear();
            for (Card card : cards) {
                if (card.x() + NODE_W >= minX && card.x() <= maxX && card.y() + NODE_H >= minY && card.y() <= maxY) {
                    selection.add(card.key());
                }
            }
            NodeKey primary = selection.isEmpty() ? null : selection.iterator().next();
            if (primary == null ? selected != null : !primary.equals(selected)) {
                selectFromSet(screen, primary);
            }
            return true;
        }
        if (dragging != null) {
            int[] anchor = dragStart.get(dragging);
            if (anchor != null) {
                // The grabbed card snaps to the grid and the others move with it, each onto the grid too (one placed
                // off it before November 18, 2026 lands on it at the first move).
                int dx = DevUi.snap(worldX(mouseX) - dragOffsetX) - anchor[0];
                int dy = DevUi.snap(worldY(mouseY) - dragOffsetY) - anchor[1];
                dragStart.forEach((key, start) -> positions.put(key, new int[]{DevUi.snap(start[0] + dx), DevUi.snap(start[1] + dy)}));
            }
            rebuildCards();
            return true;
        }
        return pan(mouseX, mouseY);
    }

    @Override
    boolean mouseReleased(DevModeScreen screen, double mouseX, double mouseY) {
        stopPan();
        if (boxSelecting) {
            boxSelecting = false;
            screen.rebuild();
            return true;
        }
        if (linkingFrom != null) {
            Card target = cardAt(worldX(mouseX), worldY(mouseY));
            NodeKey from = linkingFrom;
            linkingFrom = null;
            if (target != null) {
                link(screen, from, target.key());
            }
            return true;
        }
        if (dragging != null) {
            Map<NodeKey, int[]> before = new LinkedHashMap<>();
            Map<NodeKey, int[]> after = new LinkedHashMap<>();
            dragStart.forEach((key, start) -> {
                int[] now = positions.get(key);
                if (now != null && (now[0] != start[0] || now[1] != start[1])) {
                    before.put(key, start.clone());
                    after.put(key, now.clone());
                }
            });
            dragging = null;
            dragStart.clear();
            if (!after.isEmpty()) {
                saveChapter();
                ResourceLocation moved = chapter();
                screen.remember(DevUi.str("history.move", after.size()), () -> moveBack(moved, before), () -> moveBack(moved, after));
            }
            return true;
        }
        return false;
    }

    /** Puts the cards where they were on that chapter (undo and redo), even when another chapter is open now. */
    private void moveBack(ResourceLocation chapterId, Map<NodeKey, int[]> places) {
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, chapterId));
        Map<NodeKey, int[]> saved = Chapters.positions(json);
        places.forEach((key, pos) -> saved.put(key, pos.clone()));
        Chapters.setPositions(chapterId, json, saved);
        saveChapterJson(chapterId, json);
        if (chapterId.equals(chapter())) {
            loadChapter();
            rebuildCards();
        }
    }

    @Override
    void wheel(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        zoomAt(mouseX, mouseY, amount);
    }

    @Override
    boolean scrollSidePanel(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        return tab == Tab.STORY && panel.scroll(screen, mouseX, mouseY, amount);
    }

    @Override
    void scrollInspector(double amount) {
        inspector.scroll(amount);
    }

    @Override
    boolean keyPressed(DevModeScreen screen, int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_F && Screen.hasControlDown()) {
            screen.openFinder(StoryFinder.Mode.SEARCH);
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) && (selected != null || !selection.isEmpty())) {
            deleteSelection(screen);
            return true;
        }
        return false;
    }
}
