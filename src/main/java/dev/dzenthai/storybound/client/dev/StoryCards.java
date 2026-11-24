package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.NodeTemplates.Template;
import dev.dzenthai.storybound.core.story.StoryFlow;
import dev.dzenthai.storybound.core.story.StoryLinks.Edge;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.Documents;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The story editor's base ({@link StoryView}): what it shows (the open chapter, its spine, the unsorted nodes, every
 * definition on "Show all links"), the cards and arrows built from the story, their looks and colours.
 * <p>
 * One layer of the story editor, split by topic, each seeing the ones below it; the rest of the editor uses StoryView:
 * StoryCards &larr; StoryChapters &larr; StoryEditing &larr; StoryDrawing &larr; StoryView.
 */
abstract class StoryCards extends GraphView {
    /**
     * A node's box: two cells of the grid by two, its shape centred in it, its title under it, its badges under the
     * title.
     */
    static final int NODE_W = 2 * DevUi.GRID;
    static final int NODE_H = 2 * DevUi.GRID;
    /** How wide a node's title is at most, under it. */
    static final int TITLE_W = 4 * DevUi.GRID - 4;
    static final long DOUBLE_CLICK_MS = 350;
    /** How often the shown player's progress is asked for again, in ticks. */
    static final int PROGRESS_REFRESH = 40;
    /** The kinds of definitions, in the order "All links" puts them in columns, left to right. */
    static final List<ResourceLocation> KINDS = List.of(ClientDevStore.TYPE_NPC, ClientDevStore.TYPE_PHRASES, ClientDevStore.TYPE_DIALOGUE,
            ClientDevStore.TYPE_SCRIPT, ClientDevStore.TYPE_VANILLA_SCRIPT, ClientDevStore.TYPE_SCENE, ClientDevStore.TYPE_DOCUMENT, ClientDevStore.TYPE_QUEST, ClientDevStore.TYPE_WAYPOINT, ClientDevStore.TYPE_BOSS,
            ClientDevStore.TYPE_CODEX, ClientDevStore.TYPE_EVENT);
    /** The map's grid for quests not placed by hand: a column per step of the story. */
    private static final int MAP_COLUMN_W = 5 * DevUi.GRID;
    private static final int MAP_ROW_H = 4 * DevUi.GRID;
    /** A badge under a node of the map: one of its own nodes. */
    /** A badge: an icon with a pixel round it and its line under it. */
    static final int BADGE_W = DevIcons.SIZE + 2;
    static final int BADGE_H = DevIcons.SIZE + 2;
    /** Badges shown under a node before the rest fold into "+N". */
    static final int MAX_BADGES = 5;
    /**
     * "Not in a chapter": the quests and story events no chapter has, kept at the places of the whole story's view
     * ({@link Chapters#QUESTS}). Never written as a chapter of its own.
     */
    static final ResourceLocation UNSORTED = dev.dzenthai.storybound.Storybound.id("__unsorted__");

    /** The map of the story's spine, or every definition with every link ("Show all links"). */
    enum Tab { STORY, ALL }

    /** A template in the "new" menus, with the kind it makes. */
    record TemplateItem(String icon, String label, Template template, ResourceLocation kind) {}

    static final List<TemplateItem> TEMPLATES = List.of(
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
            new TemplateItem("📄", "menu.new.vanilla_script", Template.VANILLA_SCRIPT, ClientDevStore.TYPE_VANILLA_SCRIPT),
            new TemplateItem("🎬", "menu.new.scene", Template.SCENE, ClientDevStore.TYPE_SCENE),
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
     * A node's shape and size: {@code base} the radius its textures are named by, {@link #radius} the radius drawn,
     * {@link NodeShapes#SCALE} times it.
     */
    record Look(NodeShapes.Shape shape, int base) {
        int radius() {
            return base * NodeShapes.SCALE;
        }
    }

    /**
     * A node's shape by its kind: a quest or a contract a diamond (a main quest larger), a story event or a script a
     * circle, an NPC or a boss a hexagon, a dialogue, phrases, a document or a Codex entry a square, a waypoint a
     * map pin.
     */
    static Look look(ResourceLocation type, JsonObject json) {
        if (type.equals(ClientDevStore.TYPE_QUEST)) {
            return new Look(NodeShapes.Shape.DIAMOND, "main".equals(category(json)) ? 18 : 15);
        }
        if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            return new Look(NodeShapes.Shape.PIN, 15);
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
     * A node's icon: the item the author picked for it (its {@code icon}, which only the editor reads), else its
     * kind's.
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
        if (type.equals(ClientDevStore.TYPE_VANILLA_SCRIPT)) {
            return new ItemStack(Items.COMMAND_BLOCK);
        }
        if (type.equals(ClientDevStore.TYPE_SCENE)) {
            return new ItemStack(Items.SPYGLASS);
        }
        return new ItemStack(type.equals(ClientDevStore.TYPE_CODEX) ? Items.BOOK : Items.REDSTONE);
    }

    /** A badge under a card: one of its own nodes, or "+N" for the rest ({@code node} null). */
    record Badge(Card card, int x, int y, int width, @Nullable NodeKey node, List<NodeKey> rest) {}

    /**
     * An arrow between two cards.
     *
     * @param litLabel the label while one of its cards is selected (on the map: the names of the nodes between)
     */
    record Arrow(NodeKey from, NodeKey to, String label, String litLabel) {}

    Tab tab = Tab.STORY;
    /** The map's open chapter; {@link Chapters#QUESTS} is "All quests". */
    ResourceLocation storyChapter = Chapters.quests();
    final Map<NodeKey, int[]> positions = new LinkedHashMap<>();
    /** The quests on the map's open chapter. */
    final Set<NodeKey> chapterNodes = new LinkedHashSet<>();
    @Nullable NodeKey selected;
    final Set<NodeKey> selection = new LinkedHashSet<>();
    /** The selected definition as edited in the inspector, not yet saved when {@link #dirty}. */
    @Nullable JsonObject editing;
    boolean dirty;
    /** What the last save sent or kept as a draft: an inspector showing just that has nothing unsaved. */
    @Nullable String lastSaved;
    final StoryInspector inspector = new StoryInspector((StoryView) this);
    final StoryPanel panel = new StoryPanel((StoryView) this);
    /** Pan and zoom of the tabs not open. */
    final Map<Tab, double[]> views = new EnumMap<>(Tab.class);

    {
        // Opened, the story's canvas shows its compass rose in the middle.
        homePending = true;
    }

    final List<Card> cards = new ArrayList<>();
    List<Arrow> arrows = List.of();
    @Nullable NodeKey dragging;
    double dragOffsetX;
    double dragOffsetY;
    final Map<NodeKey, int[]> dragStart = new LinkedHashMap<>();
    @Nullable NodeKey linkingFrom;
    boolean boxSelecting;
    double boxStartX;
    double boxStartY;
    double boxEndX;
    double boxEndY;
    long lastClickTime;
    @Nullable NodeKey lastClicked;
    /** Where the context menu was opened, on the canvas: new nodes go there. */
    int menuWorldX;
    int menuWorldY;

    @Override
    Component backLabel() {
        return DevUi.text("back.menu");
    }

    @Override
    void back(DevModeScreen screen) {
        screen.onClose();
    }

    /** No title over the map: the chapter strip and the map say where one is. */
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
    ResourceLocation chapter() {
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
    boolean shows(NodeKey key) {
        return tab == Tab.STORY ? spine(key) : KINDS.contains(key.type());
    }

    /** How many quests and story events the story has. */
    int spineCount() {
        return ClientDevStore.byType(ClientDevStore.TYPE_QUEST).size() + ClientDevStore.byType(ClientDevStore.TYPE_EVENT).size();
    }

    /** The quests and story events no chapter has. */
    Set<NodeKey> unsorted() {
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
    void loadChapter() {
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
        List<StoryFlow.Flow> flows = StoryFlow.flows(edges, StoryCards::spine);
        Map<NodeKey, List<NodeKey>> own = StoryFlow.attached(edges, StoryCards::spine);
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

    Card card(NodeKey key, JsonObject json, String subtitle, int x, int y) {
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
        if (type.equals(ClientDevStore.TYPE_VANILLA_SCRIPT)) {
            return "📄";
        }
        if (type.equals(ClientDevStore.TYPE_SCENE)) {
            return "🎬";
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
    static String names(List<NodeKey> nodes) {
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
        if (type.equals(ClientDevStore.TYPE_VANILLA_SCRIPT)) {
            return DevUi.str("badge.vanilla_script");
        }
        if (type.equals(ClientDevStore.TYPE_SCENE)) {
            return DevUi.str("badge.scene");
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
        if (type.equals(ClientDevStore.TYPE_VANILLA_SCRIPT)) {
            return 0xFF9AA7B0;
        }
        if (type.equals(ClientDevStore.TYPE_SCENE)) {
            return 0xFF6FA8B8;
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

    /** The story's nodes are moving from one namespace to another: once they arrive, the selection follows them. */
    String @Nullable [] movedNamespace;

    boolean occupied(int x, int y) {
        for (Card card : cards) {
            // A node with its title (4 cells wide) and badges: 4 cells by 3.
            if (Math.abs(card.x() - x) < 4 * DevUi.GRID && Math.abs(card.y() - y) < 3 * DevUi.GRID) {
                return true;
            }
        }
        return false;
    }

    /** Puts a definition back as it was (undo and redo of an edit made outside the inspector). */
    static void restore(NodeKey key, String json) {
        ClientDevStore.putLocal(key.type(), key.id(), json);
        DevRequests.restore(key.type(), key.id(), json, Optional.empty());
    }

    // --- widgets and drawing

    // --- the progress layer: where a player stands, on the map

    /** The player whose progress the map shows; null when the layer is off. Kept while the game runs. */
    @Nullable String progressOf;
    int progressTicks;

    static final int GOLD_GLINT = 0xFFFFF4C4;
    static final int GOLD_LIGHT = 0xFFFFE08A;
    static final int GOLD = 0xFFE1B34D;
    static final int OCHRE = 0xFFB08031;
    static final int OCHRE_DARK = 0xFF775025;
    /** A selected node's inside: a warm lighter brown, so its item still reads. */
    static final int SELECTED_FILL = 0xFF3F2C18;
    /** A node's inside: a little lighter than the ground. */
    static final int NODE_FILL = 0xFF251B14;

    /** Arrow labels come from the store as link kinds; known kinds are translated. */
    static String edgeLabel(String kind) {
        String key = "storybound.dev.edge." + kind;
        return Language.getInstance().has(key) ? Component.translatable(key).getString() : kind;
    }

    // --- input

    @Nullable Card cardAt(double worldX, double worldY) {
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (card.holds(worldX, worldY)) {
                return card;
            }
        }
        return null;
    }

    // Done by the layers above; the cards and the chapters ask for them.

    /** Implemented by {@link StoryEditing}. */
    abstract void select(DevModeScreen screen, @Nullable NodeKey key);

    /** Implemented by {@link StoryChapters}. */
    abstract void openChapter(ResourceLocation id);

    /** Implemented by {@link StoryChapters}. */
    abstract void openTab(DevModeScreen screen, Tab next);
}
