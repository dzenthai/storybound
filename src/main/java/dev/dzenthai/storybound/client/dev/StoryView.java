package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.story.StoryFlow;
import dev.dzenthai.storybound.core.story.StoryLinks.Edge;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import dev.dzenthai.storybound.core.story.StoryRenames;
import dev.dzenthai.storybound.client.dev.DevModeScreen.MenuItem;
import dev.dzenthai.storybound.client.dev.NodeTemplates.Template;
import dev.dzenthai.storybound.client.text.ClientConfigTexts;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The story editor's three tabs, with the selected definition's inspector on the right ({@link StoryInspector}):
 * <ul>
 *   <li><b>Story</b>, a map of the quests as in FTB Quests: an arrow from a quest to each quest it leads to, the
 *   dialogues, scripts and documents between them folded into the arrow ({@link StoryFlow}); chapters on the left.</li>
 *   <li><b>Library</b>, every definition as a list ({@link StoryLibrary}).</li>
 *   <li><b>All links</b>, every definition as a card with every link as an arrow, to find what is linked with what.</li>
 * </ul>
 * On both canvases Shift selects several cards; dragging from a card's port to another card writes the link into the
 * first one's JSON ({@link StoryEdits#link}).
 */
final class StoryView extends GraphView {
    static final int NODE_W = 136;
    static final int NODE_H = 42;
    private static final long DOUBLE_CLICK_MS = 350;
    /** How often the shown player's progress is asked for again, in ticks. */
    private static final int PROGRESS_REFRESH = 40;
    /** The kinds of definitions, in the order "All links" puts them in columns, left to right. */
    static final List<ResourceLocation> KINDS = List.of(ClientDevStore.TYPE_NPC, ClientDevStore.TYPE_DIALOGUE,
            ClientDevStore.TYPE_SCRIPT, ClientDevStore.TYPE_DOCUMENT, ClientDevStore.TYPE_QUEST, ClientDevStore.TYPE_WAYPOINT, ClientDevStore.TYPE_BOSS,
            ClientDevStore.TYPE_CODEX, ClientDevStore.TYPE_EVENT);
    /** The map's grid for quests not placed by hand: a column per step of the story. */
    private static final int MAP_COLUMN_W = 190;
    private static final int MAP_ROW_H = 64;
    private static final int TABS_TOP = DevUi.TOP_BAR_H + 4;
    /** Where the left panel's list (chapters, kinds) starts, under the tabs and its heading. */
    private static final int LIST_TOP = DevUi.TOP_BAR_H + 84;

    enum Tab { STORY, LIBRARY, ALL }

    /** A template in the "new" menus, with the kind it makes. */
    private record TemplateItem(String icon, String label, Template template, ResourceLocation kind) {}

    private static final List<TemplateItem> TEMPLATES = List.of(
            new TemplateItem("📜", "menu.new.quest_main", Template.QUEST_MAIN, ClientDevStore.TYPE_QUEST),
            new TemplateItem("🗡", "menu.new.quest_side", Template.QUEST_SIDE, ClientDevStore.TYPE_QUEST),
            new TemplateItem("⚔", "menu.new.contract", Template.CONTRACT, ClientDevStore.TYPE_QUEST),
            new TemplateItem("💬", "menu.new.dialogue_screen", Template.DIALOGUE_SCREEN, ClientDevStore.TYPE_DIALOGUE),
            new TemplateItem("💭", "menu.new.dialogue_bubble", Template.DIALOGUE_BUBBLE, ClientDevStore.TYPE_DIALOGUE),
            new TemplateItem("🧑", "menu.new.npc", Template.NPC, ClientDevStore.TYPE_NPC),
            new TemplateItem("📖", "menu.new.document", Template.DOCUMENT, ClientDevStore.TYPE_DOCUMENT),
            new TemplateItem("📍", "menu.new.waypoint", Template.WAYPOINT, ClientDevStore.TYPE_WAYPOINT),
            new TemplateItem("🐉", "menu.new.boss", Template.BOSS, ClientDevStore.TYPE_BOSS),
            new TemplateItem("⚙", "menu.new.script", Template.SCRIPT, ClientDevStore.TYPE_SCRIPT));

    /**
     * A card on a canvas.
     *
     * @param subtitle the second line: on the map, where a quest starts; elsewhere the id
     */
    record Card(NodeKey key, String title, String subtitle, String badge, int color, ItemStack icon, int x, int y, boolean hasError,
                boolean missingTranslation) {}

    /**
     * An arrow between two cards.
     *
     * @param litLabel the label while one of its cards is selected (on the map: the names of the nodes between)
     */
    private record Arrow(NodeKey from, NodeKey to, String label, String litLabel) {}

    Tab tab = Tab.STORY;
    /** The map's open chapter; {@link Chapters#QUESTS} is "All quests". */
    private ResourceLocation storyChapter = Chapters.QUESTS;
    private final Map<NodeKey, int[]> positions = new LinkedHashMap<>();
    /** The quests on the map's open chapter. */
    private final Set<NodeKey> chapterNodes = new LinkedHashSet<>();
    @Nullable NodeKey selected;
    final Set<NodeKey> selection = new LinkedHashSet<>();
    /** The selected definition as edited in the inspector, not yet saved when {@link #dirty}. */
    @Nullable JsonObject editing;
    boolean dirty;
    final StoryInspector inspector = new StoryInspector(this);
    private final StoryLibrary library = new StoryLibrary(this);
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

    @Override
    Component title() {
        return Component.literal("⚙ ").append(DevUi.text("title"));
    }

    @Override
    int untranslated() {
        return ClientDevStore.missingTranslationCount(ClientDevStore.activeLanguage());
    }

    @Override
    boolean showsIssues() {
        return true;
    }

    @Override
    boolean inspectorVisible(DevModeScreen screen) {
        return selected != null && editing != null && !screen.inspectorCollapsed();
    }

    /** The chapter whose places the open canvas uses: the map's open chapter, or the hidden one of "All links". */
    private ResourceLocation chapter() {
        return tab == Tab.ALL ? Chapters.OVERVIEW : storyChapter;
    }

    /** Whether the open tab's canvas shows the node: the map shows only quests, "All links" every kind. */
    private boolean shows(NodeKey key) {
        return switch (tab) {
            case STORY -> key.type().equals(ClientDevStore.TYPE_QUEST);
            case ALL -> KINDS.contains(key.type());
            case LIBRARY -> false;
        };
    }

    @Override
    void reload() {
        if (!Chapters.hidden(storyChapter) && ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, storyChapter) == null) {
            storyChapter = Chapters.QUESTS;
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
            if (key.type().equals(ClientDevStore.TYPE_QUEST)) {
                chapterNodes.add(key);
            }
            positions.put(key, pos);
        });
    }

    /** Lays out what the open tab shows: the map, every node, or the library's list. */
    void rebuildCards() {
        cards.clear();
        List<Edge> edges = ClientDevStore.collectEdges();
        switch (tab) {
            case STORY -> rebuildMap(edges);
            case ALL -> rebuildAll(edges);
            case LIBRARY -> {
                arrows = List.of();
                library.rebuild(edges);
            }
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
                int x = column * 185 + 20;
                int y = row * 72 + 20;
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
        List<StoryFlow.Flow> flows = StoryFlow.flows(edges);
        Map<NodeKey, List<NodeKey>> entries = StoryFlow.entries(edges);
        List<NodeKey> quests = new ArrayList<>();
        for (ResourceLocation id : ClientDevStore.byType(ClientDevStore.TYPE_QUEST).keySet()) {
            NodeKey key = new NodeKey(ClientDevStore.TYPE_QUEST, id);
            if (Chapters.QUESTS.equals(storyChapter) || chapterNodes.contains(key)) {
                quests.add(key);
            }
        }
        Set<NodeKey> shown = new LinkedHashSet<>(quests);
        List<StoryFlow.Flow> inside = flows.stream().filter(flow -> shown.contains(flow.from()) && shown.contains(flow.to())).toList();
        Map<NodeKey, Integer> columns = StoryFlow.columns(quests, inside);
        List<NodeKey> unplaced = new ArrayList<>();
        for (NodeKey quest : quests) {
            int[] pos = positions.get(quest);
            if (pos == null) {
                unplaced.add(quest);
            } else {
                cards.add(mapCard(quest, entries, pos[0], pos[1]));
            }
        }
        Map<Integer, Integer> rows = new HashMap<>();
        for (NodeKey quest : unplaced) {
            int column = columns.getOrDefault(quest, 0);
            int x = column * MAP_COLUMN_W + 20;
            int y;
            do {
                y = rows.merge(column, 1, Integer::sum) * MAP_ROW_H - MAP_ROW_H + 20;
            } while (occupied(x, y));
            positions.put(quest, new int[]{x, y});
            cards.add(mapCard(quest, entries, x, y));
        }
        List<Arrow> list = new ArrayList<>();
        for (StoryFlow.Flow flow : inside) {
            list.add(new Arrow(flow.from(), flow.to(), glyphs(flow.via()), names(flow.via())));
        }
        arrows = list;
    }

    /** A quest on the map: its second line says where it starts when no quest leads to it. */
    private Card mapCard(NodeKey quest, Map<NodeKey, List<NodeKey>> entries, int x, int y) {
        List<NodeKey> entry = entries.get(quest);
        String subtitle = entry == null || entry.isEmpty() ? quest.id().getPath() : "▶ " + glyphs(entry) + " " + title(entry.getFirst());
        return card(quest, ClientDevStore.parseObject(ClientDevStore.getJson(quest.type(), quest.id())), subtitle, x, y);
    }

    private Card card(NodeKey key, JsonObject json, String subtitle, int x, int y) {
        return new Card(key, displayTitle(key, json), subtitle, badge(key.type(), json), color(key.type(), json), icon(key, json), x, y,
                !ClientDevStore.issuesFor(key.type(), key.id()).isEmpty() || scriptBroken(key),
                !ClientDevStore.isNodeFullyTranslated(key, json, ClientDevStore.activeLanguage()));
    }

    /** A kind's sign, as the menus show it. */
    static String glyph(ResourceLocation type) {
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
        if (type.equals(ClientDevStore.TYPE_SCRIPT)) {
            return 0xFFEC407A;
        }
        if (type.equals(ClientDevStore.TYPE_QUEST)) {
            return switch (category(json)) {
                case "main" -> 0xFFD4AF37;
                case "contract" -> 0xFFC04030;
                default -> 0xFF4A90E2;
            };
        }
        if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            return "bubble".equals(presentation(json)) ? 0xFF50C878 : 0xFF26A69A;
        }
        if (type.equals(ClientDevStore.TYPE_NPC)) {
            return 0xFF66BB6A;
        }
        if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
            return 0xFFD49A5A;
        }
        if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            return 0xFFAB47BC;
        }
        if (type.equals(ClientDevStore.TYPE_BOSS)) {
            return 0xFFE53935;
        }
        return type.equals(ClientDevStore.TYPE_CODEX) ? 0xFF5C6BC0 : 0xFFFFA726;
    }

    static ItemStack icon(NodeKey key, JsonObject json) {
        ResourceLocation type = key.type();
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
            // Still being filled in: kept here, sent once it loads.
            dirty = true;
            screen.status(DevUi.text("status.incomplete", DevChecks.shorten(problem.get())), DevUi.WARN);
            return;
        }
        NodeKey target = selected;
        String before = ClientDevStore.getJson(target.type(), target.id());
        Optional<String> beforeText = documentText(target.id());
        String after = DevUi.GSON.toJson(editing);
        Optional<String> afterText = inspector.documentText();
        dirty = false;
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
    void changed(DevModeScreen screen) {
        dirty = true;
        save(screen);
        screen.rebuild();
    }

    /** The document's text in the active language, else the source language, else English. */
    static Optional<String> documentText(ResourceLocation document) {
        return ClientConfigTexts.document(document, ClientDevStore.activeLanguage())
                .or(() -> ClientConfigTexts.document(document, ClientDevStore.sourceLanguage()))
                .or(() -> ClientConfigTexts.document(document, "en_us"));
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
        return last;
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
    private void place(DevModeScreen screen, NodeTemplates.Created definition, int x, int y) {
        NodeKey key = definition.key();
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
        int x = reverse ? from[0] - 180 : from[0] + 180;
        int y = from[1];
        for (int attempt = 0; attempt < 12 && occupied(x, y); attempt++) {
            y += 54;
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
            if (Math.abs(card.x() - x) < NODE_W - 10 && Math.abs(card.y() - y) < NODE_H + 6) {
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
        if (tab == Tab.LIBRARY) {
            return;
        }
        ResourceLocation open = chapter();
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, open));
        Map<NodeKey, int[]> placed = Chapters.positions(json);
        placed.keySet().removeIf(this::shows);
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
    private void createChapter(DevModeScreen screen) {
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

    private void deleteChapter(DevModeScreen screen, ResourceLocation id) {
        String before = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, id);
        if (before == null) {
            return;
        }
        if (id.equals(storyChapter)) {
            openChapter(Chapters.QUESTS);
        }
        DevRequests.delete(ClientDevStore.TYPE_CHAPTER, id);
        screen.remember(DevUi.str("history.delete_chapter", id.getPath()),
                () -> {
                    storyChapter = id;
                    DevRequests.restore(ClientDevStore.TYPE_CHAPTER, id, before, Optional.empty());
                },
                () -> {
                    if (id.equals(storyChapter)) {
                        storyChapter = Chapters.QUESTS;
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
            int[] pos = positions.getOrDefault(quest, new int[]{20, 20});
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
    private void renameChapter(DevModeScreen screen, ResourceLocation id) {
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
        zoom = (float) view[2];
        tab = next;
        loadChapter();
        rebuildCards();
        screen.rebuild();
    }

    /** Opens the tab with the node in the middle of its canvas, selected; on the map, in "All quests". */
    void reveal(DevModeScreen screen, Tab target, NodeKey key) {
        if (target == Tab.STORY && !chapterNodes.contains(key)) {
            storyChapter = Chapters.QUESTS;
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

    /** A quest card's mark on the progress layer: dimmed while the player does not know it, else its state and stage. */
    private void renderProgress(GuiGraphics graphics, Font font, Card card) {
        String player = progressOf;
        if (player == null || !card.key().type().equals(ClientDevStore.TYPE_QUEST) || !ClientDevStore.hasProgress(player)) {
            return;
        }
        QuestProgress progress = ClientDevStore.progress(player, card.key().id());
        if (progress == null) {
            graphics.fill(card.x(), card.y(), card.x() + NODE_W, card.y() + NODE_H, 0x99000000);
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
        String shown = font.plainSubstrByWidth(mark, NODE_W - 8);
        int width = font.width(shown) + 6;
        int x = card.x() + NODE_W - width;
        int y = card.y() - 11;
        graphics.fill(x, y, x + width, y + 10, color);
        graphics.drawString(font, shown, x + 3, y + 1, DevUi.DARK_TEXT, false);
    }

    @Override
    void addWidgets(DevModeScreen screen) {
        if (tab == Tab.STORY) {
            screen.add(Button.builder(DevUi.text("chapter.new"), b -> createChapter(screen))
                    .bounds(6, screen.height - 24, DevUi.LEFT_PANEL_W - 12, 18).build());
            Component who = progressOf == null ? DevUi.text("progress.off") : Component.literal(progressOf);
            screen.add(Button.builder(Component.literal("👤 ").append(who), b -> nextProgressPlayer(screen))
                    .tooltip(Tooltip.create(DevUi.text("progress.tooltip")))
                    .bounds(6, screen.height - 46, DevUi.LEFT_PANEL_W - 12, 18).build());
        }
        if (tab == Tab.LIBRARY) {
            library.addWidgets(screen);
        }
        if (inspectorVisible(screen)) {
            inspector.addWidgets(screen);
        }
    }

    @Override
    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        if (tab == Tab.LIBRARY) {
            library.render(screen, graphics, mouseX, mouseY);
        } else {
            renderCanvas(screen, graphics, mouseX, mouseY);
        }
        renderLeftPanel(screen, graphics, mouseX, mouseY);
        if (inspectorVisible(screen)) {
            inspector.render(screen, graphics);
        }
    }

    private void renderCanvas(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        beginCanvas(screen, graphics, 0xFF1C2026);
        Map<NodeKey, Card> byKey = new HashMap<>();
        for (Card card : cards) {
            byKey.put(card.key(), card);
        }
        int index = 0;
        for (Arrow arrow : arrows) {
            Card from = byKey.get(arrow.from());
            Card to = byKey.get(arrow.to());
            if (from != null && to != null) {
                boolean lit = selection.contains(arrow.from()) || selection.contains(arrow.to()) || arrow.from().equals(selected) || arrow.to().equals(selected);
                DevUi.edge(graphics, font, from.x() + NODE_W, from.y() + NODE_H / 2, to.x(), to.y() + NODE_H / 2,
                        lit ? DevUi.WARN : 0xFF546E7A, lit ? arrow.litLabel() : arrow.label(), index++);
            }
        }
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
        Card source = linkingFrom != null ? byKey.get(linkingFrom) : null;
        if (source != null) {
            DevUi.edge(graphics, font, source.x() + NODE_W, source.y() + NODE_H / 2, (int) worldX, (int) worldY, DevUi.LINK, DevUi.str("edge.link"), 0);
        }
        for (Card card : cards) {
            boolean primary = card.key().equals(selected);
            boolean multi = selection.contains(card.key());
            boolean hovered = DevUi.inside(worldX, worldY, card.x(), card.y(), NODE_W, NODE_H);
            int border = card.hasError() ? DevUi.ERROR : primary ? 0xFFFFE082 : multi ? 0xFF4FC3F7 : hovered ? DevUi.NEUTRAL : DevUi.CARD_EDGE;
            DevUi.card(graphics, font, card.x(), card.y(), NODE_W, NODE_H, border, primary || multi, card.color(), card.badge(),
                    card.missingTranslation(), card.icon(), card.title(), card.subtitle());
            if (tab == Tab.STORY) {
                renderProgress(graphics, font, card);
            }
        }
        if (boxSelecting) {
            int x1 = (int) Math.min(boxStartX, boxEndX);
            int y1 = (int) Math.min(boxStartY, boxEndY);
            int x2 = (int) Math.max(boxStartX, boxEndX);
            int y2 = (int) Math.max(boxStartY, boxEndY);
            graphics.fill(x1, y1, x2, y2, 0x3364B5F6);
            DevUi.horizontal(graphics, x1, x2, y1, 0xFF64B5F6);
            DevUi.horizontal(graphics, x1, x2, y2, 0xFF64B5F6);
            DevUi.vertical(graphics, x1, y1, y2, 0xFF64B5F6);
            DevUi.vertical(graphics, x2, y1, y2, 0xFF64B5F6);
        }
        endCanvas(graphics);
        if (tab == Tab.STORY && cards.isEmpty()) {
            int left = DevUi.LEFT_PANEL_W + 16;
            int y = DevUi.TOP_BAR_H + 16;
            for (FormattedCharSequence line : font.split(DevUi.text(Chapters.QUESTS.equals(storyChapter) ? "map.empty" : "map.empty_chapter"),
                    screen.canvasRight() - left - 16)) {
                graphics.drawString(font, line, left, y, DevUi.HINT, false);
                y += 11;
            }
        }
    }

    /** Arrow labels come from the store as link kinds; known kinds are translated. */
    static String edgeLabel(String kind) {
        String key = "storybound.dev.edge." + kind;
        return Language.getInstance().has(key) ? Component.translatable(key).getString() : kind;
    }

    /** The tabs, then what the open tab lists: the map's chapters, the library's kinds, or what "All links" is for. */
    private void renderLeftPanel(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        DevUi.sidePanel(graphics, 0, DevUi.LEFT_PANEL_W, screen.height, true);
        String[] tabs = {"📜 " + DevUi.str("tab.story"), "📚 " + DevUi.str("tab.library"), "🕸 " + DevUi.str("tab.links")};
        for (int i = 0; i < tabs.length; i++) {
            int y = TABS_TOP + i * 20;
            DevUi.listRow(graphics, font, tabs[i], y, tab.ordinal() == i, DevUi.inside(mouseX, mouseY, 4, y, DevUi.LEFT_PANEL_W - 8, 18));
        }
        graphics.fill(6, LIST_TOP - 20, DevUi.LEFT_PANEL_W - 6, LIST_TOP - 19, DevUi.PANEL_EDGE);
        switch (tab) {
            case STORY -> {
                graphics.drawString(font, DevUi.text("chapter.list"), 8, LIST_TOP - 14, DevUi.ACCENT, false);
                int y = LIST_TOP;
                DevUi.listRow(graphics, font, "★ " + DevUi.str("chapter.all_quests", ClientDevStore.byType(ClientDevStore.TYPE_QUEST).size()), y,
                        Chapters.QUESTS.equals(storyChapter), DevUi.inside(mouseX, mouseY, 4, y, DevUi.LEFT_PANEL_W - 8, 18));
                for (Map.Entry<ResourceLocation, JsonObject> entry : Chapters.sorted()) {
                    y += 20;
                    String name = ClientDevStore.readStoryText(entry.getValue(), "title", entry.getKey().getPath());
                    DevUi.listRow(graphics, font, "• " + name, y, entry.getKey().equals(storyChapter), DevUi.inside(mouseX, mouseY, 4, y, DevUi.LEFT_PANEL_W - 8, 18));
                }
            }
            case LIBRARY -> library.renderKinds(screen, graphics, LIST_TOP, mouseX, mouseY);
            case ALL -> {
                int y = LIST_TOP - 14;
                for (FormattedCharSequence line : font.split(DevUi.text("links.about"), DevUi.LEFT_PANEL_W - 16)) {
                    graphics.drawString(font, line, 8, y, DevUi.HINT, false);
                    y += 11;
                }
            }
        }
    }

    // --- input

    private @Nullable Card cardAt(double worldX, double worldY) {
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (DevUi.inside(worldX, worldY, card.x(), card.y(), NODE_W, NODE_H)) {
                return card;
            }
        }
        return null;
    }

    @Override
    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button) {
        if (mouseX < DevUi.LEFT_PANEL_W && mouseY >= DevUi.TOP_BAR_H) {
            return clickLeftPanel(screen, mouseX, mouseY, button);
        }
        if (!onCanvas(screen, mouseX, mouseY)) {
            return false;
        }
        if (tab == Tab.LIBRARY) {
            return library.mouseClicked(screen, mouseX, mouseY, button);
        }
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
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
            if (DevUi.onPort(worldX, worldY, card.x(), card.y(), NODE_W, NODE_H)) {
                linkingFrom = card.key();
                return true;
            }
            if (DevUi.inside(worldX, worldY, card.x(), card.y(), NODE_W, NODE_H)) {
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

    /** The tabs, then the open tab's list (the buttons at the bottom are widgets). */
    private boolean clickLeftPanel(DevModeScreen screen, double mouseX, double mouseY, int button) {
        if (mouseY >= TABS_TOP && mouseY < TABS_TOP + Tab.values().length * 20) {
            Tab next = Tab.values()[((int) mouseY - TABS_TOP) / 20];
            if (next != tab) {
                openTab(screen, next);
            }
            return true;
        }
        if (mouseY < LIST_TOP) {
            return false;
        }
        int row = ((int) mouseY - LIST_TOP) / 20;
        return switch (tab) {
            case STORY -> mouseY < screen.height - 50 && clickChapter(screen, mouseX, mouseY, row, button);
            case LIBRARY -> mouseY < screen.height - 28 && library.clickKind(screen, row);
            case ALL -> false;
        };
    }

    private boolean clickChapter(DevModeScreen screen, double mouseX, double mouseY, int row, int button) {
        if (row == 0) {
            openChapter(Chapters.QUESTS);
            return true;
        }
        List<Map.Entry<ResourceLocation, JsonObject>> chapters = Chapters.sorted();
        if (row - 1 < 0 || row - 1 >= chapters.size()) {
            return false;
        }
        ResourceLocation id = chapters.get(row - 1).getKey();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            String name = ClientDevStore.readStoryText(chapters.get(row - 1).getValue(), "title", id.getPath());
            screen.openMenu((int) mouseX, (int) mouseY, List.of(
                    MenuItem.of(Component.literal("✏ ").append(DevUi.text("chapter.rename")), () -> renameChapter(screen, id)),
                    MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("chapter.delete", name)), () -> deleteChapter(screen, id))));
            return true;
        }
        openChapter(id);
        return true;
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
            // The map makes quests; what they use is made from a quest's menu or in the library.
            return templateMenu(screen, tab == Tab.STORY ? ClientDevStore.TYPE_QUEST : null);
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

    /** "New …" lines for the kind (all kinds without one): on a canvas at the menu's place, in the library nowhere. */
    List<MenuItem> templateMenu(DevModeScreen screen, @Nullable ResourceLocation kind) {
        List<MenuItem> items = new ArrayList<>();
        for (TemplateItem item : TEMPLATES) {
            if (kind == null || item.kind().equals(kind)) {
                items.add(MenuItem.of(Component.literal(item.icon() + " ").append(DevUi.text(item.label())), () -> {
                    if (tab == Tab.LIBRARY) {
                        createFromList(screen, item.template());
                    } else {
                        create(screen, item.template());
                        screen.rebuild();
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
        if (tab == Tab.LIBRARY) {
            return false;
        }
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
                // Snapped to an 8-pixel grid; every selected card moves by the same amount.
                int dx = (int) Math.round((worldX(mouseX) - dragOffsetX) / 8.0) * 8 - anchor[0];
                int dy = (int) Math.round((worldY(mouseY) - dragOffsetY) / 8.0) * 8 - anchor[1];
                dragStart.forEach((key, start) -> positions.put(key, new int[]{start[0] + dx, start[1] + dy}));
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
        if (tab != Tab.LIBRARY && chapterId.equals(chapter())) {
            loadChapter();
            rebuildCards();
        }
    }

    @Override
    void wheel(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        if (tab == Tab.LIBRARY) {
            library.scroll(screen, amount);
        } else {
            zoomAt(mouseX, mouseY, amount);
        }
    }

    @Override
    void scrollInspector(double amount) {
        inspector.scroll(amount);
    }

    @Override
    boolean keyPressed(DevModeScreen screen, int keyCode) {
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) && (selected != null || !selection.isEmpty())) {
            deleteSelection(screen);
            return true;
        }
        return false;
    }
}
