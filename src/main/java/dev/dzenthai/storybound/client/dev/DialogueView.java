package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dzenthai.storybound.client.dev.DevModeScreen.MenuItem;
import dev.dzenthai.storybound.client.dev.DialogueGraph.Edge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * One dialogue's graph: its nodes as cards, its branches as arrows, the node list on the left and the selected
 * node's inspector on the right. Where the cards sit is kept in the dialogue's layout
 * ({@code config/storybound/dialogue_layouts/}), which the game never reads.
 */
final class DialogueView extends GraphView {
    static final int NODE_W = 144;
    static final int NODE_H = 46;

    private record Card(String id, String type, String title, String badge, int color, ItemStack icon, int x, int y, boolean start, boolean missingTranslation) {}

    /** The dialogue and its layout as JSON, for undo and redo. */
    private record State(String dialogue, String layout) {}

    private @Nullable ResourceLocation id;
    private JsonObject json = new JsonObject();
    private final Map<String, int[]> positions = new LinkedHashMap<>();
    private final List<Card> cards = new ArrayList<>();
    private List<Edge> edges = List.of();
    private @Nullable String selected;
    private @Nullable String dragging;
    private double dragOffsetX;
    private double dragOffsetY;
    private @Nullable State dragBefore;
    private @Nullable String linkingFrom;
    private boolean dirty;
    /** The dialogue as last loaded or saved: what undo goes back to after edits in the form. */
    private @Nullable State saved;
    private @Nullable DevModeScreen screen;
    /** The node (and its type) the form shows; another one starts the form from its first page. */
    private @Nullable String formShown;
    private final FormView form = new FormView(new FormView.Host() {
        @Override
        public JsonObject root() {
            JsonObject node = selected != null ? DialogueGraph.node(json, selected) : null;
            return node != null ? node : new JsonObject();
        }

        @Override
        public String textKey(String path, String field) {
            return id != null ? ClientDevStore.canonicalKey(ClientDevStore.TYPE_DIALOGUE, id, "node." + selected + "." + path + field)
                    : ClientDevStore.generateUniqueKey(field);
        }

        @Override
        public void edited() {
            dirty = true;
        }

        @Override
        public void changed() {
            if (screen != null) {
                formChanged(screen);
            }
        }

        /** Where a branch goes: this dialogue's nodes, {@code @end} and {@code @exit}; who speaks: the participants and the player. */
        @Override
        public List<String> choices(String field) {
            return switch (field) {
                case "next", "then", "else", "node" -> {
                    List<String> targets = new ArrayList<>(DialogueGraph.nodes(json).keySet());
                    targets.add(DialogueGraph.END);
                    targets.add(DialogueGraph.EXIT);
                    yield targets;
                }
                case "speaker" -> {
                    List<String> speakers = new ArrayList<>(speakers());
                    speakers.add("player");
                    yield speakers;
                }
                default -> List.of();
            };
        }
    });
    private int menuWorldX;
    private int menuWorldY;

    boolean isOpen() {
        return id != null;
    }

    void open(ResourceLocation dialogue, DevModeScreen screen) {
        id = dialogue;
        dirty = false;
        formShown = null;
        panX = 40;
        panY = 40;
        zoom = 1.0f;
        reload();
        selected = DialogueGraph.start(json).isEmpty() ? null : DialogueGraph.start(json);
    }

    void close(DevModeScreen screen) {
        saveEdits(screen);
        id = null;
        selected = null;
        dragging = null;
        linkingFrom = null;
        dragBefore = null;
    }

    @Override
    Component backLabel() {
        return DevUi.text("back.story");
    }

    @Override
    void back(DevModeScreen screen) {
        screen.closeDialogue();
    }

    @Override
    Component title() {
        return Component.literal("💬 " + (id != null ? id.getPath() : ""));
    }

    @Override
    int untranslated() {
        return (int) cards.stream().filter(Card::missingTranslation).count();
    }

    @Override
    boolean showsIssues() {
        return false;
    }

    @Override
    boolean inspectorVisible(DevModeScreen screen) {
        return selected != null && !screen.inspectorCollapsed();
    }

    /** Re-reads the dialogue and its layout; a dialogue deleted meanwhile closes the graph. */
    @Override
    void reload() {
        if (id == null) {
            return;
        }
        String raw = ClientDevStore.getJson(ClientDevStore.TYPE_DIALOGUE, id);
        if (raw == null) {
            id = null;
            selected = null;
            return;
        }
        if (!dirty) {
            json = ClientDevStore.parseObject(raw);
        }
        positions.clear();
        JsonObject layout = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_DIALOGUE_LAYOUT, id));
        if (layout.has("nodes") && layout.get("nodes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : layout.getAsJsonObject("nodes").entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    JsonObject pos = entry.getValue().getAsJsonObject();
                    positions.put(entry.getKey(), new int[]{pos.has("x") ? pos.get("x").getAsInt() : 0, pos.has("y") ? pos.get("y").getAsInt() : 0});
                }
            }
        }
        if (selected != null && DialogueGraph.node(json, selected) == null) {
            selected = null;
        }
        rebuildCards();
        if (!dirty) {
            saved = state();
        }
    }

    /** Cards for every node; nodes without a place get the automatic layout's. */
    private void rebuildCards() {
        cards.clear();
        if (id == null) {
            return;
        }
        DialogueGraph.sanitizeKeys(id, json);
        JsonObject nodes = DialogueGraph.nodes(json);
        if (!positions.keySet().containsAll(nodes.keySet())) {
            Map<String, int[]> auto = DialogueGraph.autoLayout(json);
            auto.forEach(positions::putIfAbsent);
        }
        String start = DialogueGraph.start(json);
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                int[] pos = positions.get(entry.getKey());
                cards.add(card(entry.getKey(), entry.getValue().getAsJsonObject(), entry.getKey().equals(start), pos[0], pos[1]));
            }
        }
        edges = DialogueGraph.edges(json, ClientDevStore.activeLanguage());
    }

    private static Card card(String id, JsonObject node, boolean start, int x, int y) {
        String language = ClientDevStore.activeLanguage();
        boolean translating = !language.equalsIgnoreCase(ClientDevStore.sourceLanguage());
        String type = DialogueGraph.type(node);
        return switch (type) {
            case "choice" -> {
                JsonArray options = DialogueGraph.options(node);
                String title = !options.isEmpty() && options.get(0).isJsonObject()
                        ? ClientDevStore.readStoryText(options.get(0).getAsJsonObject(), "text", "#1", language) : DevUi.str("node.no_options");
                boolean missing = false;
                for (JsonElement option : options) {
                    missing |= translating && option.isJsonObject() && untranslated(option.getAsJsonObject(), language);
                }
                yield new Card(id, type, title, DevUi.str("node.choice", options.size()), 0xFFFFB300, new ItemStack(Items.WRITABLE_BOOK), x, y, start, missing);
            }
            case "condition" -> new Card(id, type, "→ " + (node.has("then") ? node.get("then").getAsString() : DialogueGraph.END),
                    DevUi.str("node.condition"), 0xFFAB47BC, new ItemStack(Items.COMPARATOR), x, y, start, false);
            case "action" -> new Card(id, type, "→ " + DialogueGraph.next(node), DevUi.str("node.action"), 0xFFFF7043, new ItemStack(Items.REDSTONE), x, y, start, false);
            case "random" -> new Card(id, type, DevUi.str("node.branches"), DevUi.str("node.random"), 0xFF8E24AA, new ItemStack(Items.DISPENSER), x, y, start, false);
            case "jump" -> new Card(id, type, node.has("node") ? node.get("node").getAsString() : node.has("dialogue") ? node.get("dialogue").getAsString() : DialogueGraph.END,
                    DevUi.str("node.jump"), 0xFF00ACC1, new ItemStack(Items.ENDER_PEARL), x, y, start, false);
            case "call" -> new Card(id, type, node.has("dialogue") ? node.get("dialogue").getAsString() : "?",
                    DevUi.str("node.call"), 0xFF1E88E5, new ItemStack(Items.BELL), x, y, start, false);
            case "end" -> new Card(id, type, DialogueGraph.END, DevUi.str("node.end"), 0xFFE53935, new ItemStack(Items.BARRIER), x, y, start, false);
            default -> {
                boolean player = "player".equalsIgnoreCase(node.has("speaker") ? node.get("speaker").getAsString() : "");
                String text = ClientDevStore.readStoryText(node, "text", "", language);
                yield new Card(id, type, text.isBlank() ? DevUi.str("node.empty_line") : text, DevUi.str(player ? "node.player_line" : "node.npc_line"),
                        player ? 0xFF42A5F5 : 0xFF26A69A, new ItemStack(player ? Items.PLAYER_HEAD : Items.FEATHER), x, y, start,
                        translating && untranslated(node, language));
            }
        };
    }

    private static boolean untranslated(JsonObject owner, String language) {
        String key = ClientDevStore.getKey(owner, "text");
        return key != null && !ClientDevStore.hasTranslation(key, language);
    }

    /** The participants who can speak besides the player; a dialogue without them has the bound {@code npc}. */
    private List<String> speakers() {
        List<String> speakers = new ArrayList<>();
        if (json.has("participants") && json.get("participants").isJsonObject()) {
            speakers.addAll(json.getAsJsonObject("participants").keySet());
        }
        if (speakers.isEmpty()) {
            speakers.add("npc");
        }
        return speakers;
    }

    // --- saving and history

    private State state() {
        return new State(DevUi.GSON.toJson(json), DevUi.GSON.toJson(layoutJson()));
    }

    private JsonObject layoutJson() {
        JsonObject nodes = new JsonObject();
        for (String node : DialogueGraph.nodes(json).keySet()) {
            int[] pos = positions.get(node);
            if (pos != null) {
                JsonObject at = new JsonObject();
                at.addProperty("x", pos[0]);
                at.addProperty("y", pos[1]);
                nodes.add(node, at);
            }
        }
        JsonObject layout = new JsonObject();
        layout.add("nodes", nodes);
        return layout;
    }

    @Override
    void saveEdits(DevModeScreen screen) {
        if (dirty) {
            saveDialogue();
        }
    }

    private void saveDialogue() {
        if (id == null) {
            return;
        }
        Optional<String> problem = DevChecks.problem(ClientDevStore.TYPE_DIALOGUE, json);
        if (problem.isPresent()) {
            dirty = true;
            if (screen != null) {
                screen.status(DevUi.text("status.incomplete", DevChecks.shorten(problem.get())), DevUi.WARN);
            }
            return;
        }
        dirty = false;
        String raw = DevUi.GSON.toJson(json);
        ClientDevStore.putLocal(ClientDevStore.TYPE_DIALOGUE, id, raw);
        DevRequests.save(ClientDevStore.TYPE_DIALOGUE, id, raw, Optional.empty());
        saved = state();
    }

    private void saveLayout() {
        if (id == null) {
            return;
        }
        String raw = DevUi.GSON.toJson(layoutJson());
        ClientDevStore.putLocal(ClientDevStore.TYPE_DIALOGUE_LAYOUT, id, raw);
        DevRequests.restore(ClientDevStore.TYPE_DIALOGUE_LAYOUT, id, raw, Optional.empty());
    }

    /** Runs an edit of the dialogue as one undoable step and saves the dialogue and its layout. */
    private void edit(DevModeScreen screen, String label, Runnable change) {
        if (id == null) {
            return;
        }
        State before = state();
        change.run();
        rebuildCards();
        saveDialogue();
        saveLayout();
        remember(screen, label, before, state());
    }

    private void remember(DevModeScreen screen, String label, State before, State after) {
        ResourceLocation dialogue = id;
        if (dialogue == null) {
            return;
        }
        screen.remember(label, () -> restore(dialogue, before), () -> restore(dialogue, after));
    }

    private void restore(ResourceLocation dialogue, State state) {
        ClientDevStore.putLocal(ClientDevStore.TYPE_DIALOGUE, dialogue, state.dialogue());
        ClientDevStore.putLocal(ClientDevStore.TYPE_DIALOGUE_LAYOUT, dialogue, state.layout());
        DevRequests.restore(ClientDevStore.TYPE_DIALOGUE, dialogue, state.dialogue(), Optional.empty());
        DevRequests.restore(ClientDevStore.TYPE_DIALOGUE_LAYOUT, dialogue, state.layout(), Optional.empty());
        if (dialogue.equals(id)) {
            dirty = false;
        }
    }

    // --- node edits

    private void createNode(DevModeScreen screen, String type, @Nullable String speaker, int x, int y, @Nullable String linkFrom) {
        if (id == null) {
            return;
        }
        ResourceLocation dialogue = id;
        String[] created = new String[1];
        edit(screen, DevUi.str(linkFrom != null ? "history.dialogue_create_linked" : "history.dialogue_create", DevUi.str("node." + type)), () -> {
            created[0] = DialogueGraph.createNode(json, dialogue, type, speaker, DevUi.str("template.new_line"), DevUi.str("template.option", 1));
            positions.put(created[0], new int[]{x, y});
            if (linkFrom != null) {
                int option = DialogueGraph.optionCount(DialogueGraph.node(json, linkFrom)) + 1;
                DialogueGraph.connect(json, dialogue, linkFrom, created[0], DevUi.str("template.option", option));
            }
        });
        selected = created[0];
        screen.showInspector();
        screen.rebuild();
        screen.status(DevUi.text("status.created", created[0]), DevUi.OK);
    }

    private void createLinked(DevModeScreen screen, String from, String type, @Nullable String speaker) {
        int[] pos = positions.getOrDefault(from, new int[]{DialogueGraph.MARGIN, DialogueGraph.MARGIN});
        createNode(screen, type, speaker, pos[0] + 200, pos[1], from);
    }

    private void connect(DevModeScreen screen, String from, String to) {
        if (id == null) {
            return;
        }
        ResourceLocation dialogue = id;
        int optionNumber = DialogueGraph.optionCount(DialogueGraph.node(json, from)) + 1;
        edit(screen, DevUi.str("history.dialogue_link", from, to),
                () -> DialogueGraph.connect(json, dialogue, from, to, DevUi.str("template.option", optionNumber)));
        screen.rebuild();
        screen.status(DevUi.text("status.linked", from, to), DevUi.LINK);
    }

    private void deleteNode(DevModeScreen screen, String node) {
        edit(screen, DevUi.str("history.dialogue_delete", node), () -> {
            DialogueGraph.deleteNode(json, node);
            positions.remove(node);
        });
        if (node.equals(selected)) {
            selected = null;
        }
        screen.rebuild();
        screen.status(DevUi.text("status.node_deleted", node), DevUi.BAD);
    }

    private void setStart(DevModeScreen screen, String node) {
        edit(screen, DevUi.str("history.dialogue_start"), () -> json.addProperty("start", node));
        screen.rebuild();
    }

    // --- widgets

    @Override
    void addWidgets(DevModeScreen screen) {
        this.screen = screen;
        if (id == null) {
            return;
        }
        ResourceLocation dialogue = id;
        screen.add(Button.builder(Component.literal("▶ ").append(DevUi.text("dialogue.test")), b -> screen.playtest("dialogue_start", dialogue))
                .bounds(screen.canvasRight() - 100, 3, 96, 18).build());
        int w = DevUi.LEFT_PANEL_W - 12;
        String npc = speakers().getFirst();
        screen.add(Button.builder(DevUi.text("dialogue.add_npc_line"), b -> createNode(screen, "line", npc, 60, 60, null))
                .bounds(6, screen.height - 90, w, 18).build());
        screen.add(Button.builder(DevUi.text("dialogue.add_player_line"), b -> createNode(screen, "line", "player", 60, 60, null))
                .bounds(6, screen.height - 68, w, 18).build());
        screen.add(Button.builder(DevUi.text("dialogue.add_choice"), b -> createNode(screen, "choice", null, 60, 60, null))
                .bounds(6, screen.height - 46, w, 18).build());
        screen.add(Button.builder(DevUi.text("dialogue.add_end"), b -> createNode(screen, "end", null, 60, 60, null))
                .bounds(6, screen.height - 24, w, 18).build());
        if (inspectorVisible(screen)) {
            addInspector(screen);
        }
    }

    private void addInspector(DevModeScreen screen) {
        String nodeId = selected;
        JsonObject node = nodeId != null ? DialogueGraph.node(json, nodeId) : null;
        if (nodeId == null || node == null) {
            return;
        }
        int x = screen.width - DevUi.RIGHT_PANEL_W + 8;
        int w = DevUi.RIGHT_PANEL_W - 16;
        int y = DevUi.TOP_BAR_H + 32;
        String formFor = nodeId + "/" + DialogueGraph.type(node);
        if (!formFor.equals(formShown)) {
            formShown = formFor;
            Schemas.Node schema = Schemas.definition("dialogue.schema.json", "node");
            form.reset(schema != null ? Schemas.typed(schema, DialogueGraph.type(node)) : null, nodeId, Set.of("type"));
        }
        boolean start = nodeId.equals(DialogueGraph.start(json));
        Button startButton = Button.builder(start ? Component.literal("★ ").append(DevUi.text("dialogue.is_start")) : DevUi.text("dialogue.set_start"),
                b -> setStart(screen, nodeId)).bounds(x, y, w - 54, 18).build();
        startButton.active = !start;
        screen.add(startButton);
        screen.add(Button.builder(Component.literal("🗑"), b -> deleteNode(screen, nodeId))
                .tooltip(Tooltip.create(DevUi.text("dialogue.delete_node"))).bounds(x + w - 50, y, 50, 18).build());
        form.addWidgets(screen, x, y + 24, w, screen.height - 8);
    }

    /** The selected node's form changed it: saved at once, as one undoable step. */
    private void formChanged(DevModeScreen screen) {
        State before = saved != null ? saved : state();
        rebuildCards();
        saveDialogue();
        remember(screen, DevUi.str("history.dialogue_edit", selected != null ? selected : ""), before, state());
        screen.rebuild();
    }

    @Override
    void scrollInspector(double amount) {
        form.scroll(amount);
    }

    // --- drawing

    @Override
    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        beginCanvas(screen, graphics, 0xFF181C22);
        Map<String, Card> byId = new HashMap<>();
        for (Card card : cards) {
            byId.put(card.id(), card);
        }
        int index = 0;
        for (Edge edge : edges) {
            Card from = byId.get(edge.from());
            if (from == null) {
                continue;
            }
            Card to = byId.get(edge.to());
            int x2 = to != null ? to.x() : from.x() + NODE_W + 48;
            int y2 = to != null ? to.y() + NODE_H / 2 : from.y() + NODE_H / 2 + ((index % 3) - 1) * 20;
            boolean lit = edge.from().equals(selected) || edge.to().equals(selected);
            String label = edgeLabel(edge);
            if (to == null && label.isEmpty()) {
                label = edge.to();
            }
            DevUi.edge(graphics, font, from.x() + NODE_W, from.y() + NODE_H / 2, x2, y2, lit ? DevUi.WARN : edgeColor(edge.kind()), label, index++);
        }
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
        Card source = linkingFrom != null ? byId.get(linkingFrom) : null;
        if (source != null) {
            DevUi.edge(graphics, font, source.x() + NODE_W, source.y() + NODE_H / 2, (int) worldX, (int) worldY, DevUi.LINK, DevUi.str("edge.link"), 0);
        }
        for (Card card : cards) {
            boolean isSelected = card.id().equals(selected);
            boolean hovered = DevUi.inside(worldX, worldY, card.x(), card.y(), NODE_W, NODE_H);
            int border = card.start() ? DevUi.WARN : isSelected ? 0xFF64B5F6 : hovered ? DevUi.NEUTRAL : DevUi.CARD_EDGE;
            DevUi.card(graphics, font, card.x(), card.y(), NODE_W, NODE_H, border, isSelected || card.start(), card.color(),
                    (card.start() ? "★ " : "") + card.badge(), card.missingTranslation(), card.icon(), card.title(), card.id());
        }
        endCanvas(graphics);
        renderNodeList(screen, graphics, mouseX, mouseY);
        if (inspectorVisible(screen)) {
            renderInspector(screen, graphics);
        }
    }

    private static String edgeLabel(Edge edge) {
        return switch (edge.kind()) {
            case TIMEOUT -> DevUi.str("edge.timeout");
            case THEN -> DevUi.str("edge.then");
            case ELSE -> DevUi.str("edge.else");
            case RETURN -> DevUi.str("edge.return");
            case JUMP -> DevUi.str("edge.jump");
            default -> edge.label();
        };
    }

    private static int edgeColor(DialogueGraph.EdgeKind kind) {
        return switch (kind) {
            case NEXT -> 0xFF50C878;
            case ACTION -> 0xFFFFB74D;
            case OPTION -> DevUi.WARN;
            case TIMEOUT, ELSE -> 0xFFE57373;
            case THEN -> 0xFF81C784;
            case RANDOM -> 0xFFBA68C8;
            case RETURN -> 0xFF4FC3F7;
            case JUMP -> 0xFF4DD0E1;
        };
    }

    private void renderNodeList(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        DevUi.sidePanel(graphics, 0, DevUi.LEFT_PANEL_W, screen.height, true);
        graphics.drawString(font, DevUi.text("dialogue.nodes", cards.size()), 8, DevUi.TOP_BAR_H + 8, DevUi.ACCENT, false);
        int y = DevUi.TOP_BAR_H + 24;
        for (Card card : cards) {
            if (y > screen.height - 120) {
                break;
            }
            String label = (card.start() ? "★ " : "• ") + card.id() + (card.missingTranslation() ? " 🟡" : "");
            DevUi.listRow(graphics, font, label, y, card.id().equals(selected), DevUi.inside(mouseX, mouseY, 4, y, DevUi.LEFT_PANEL_W - 8, 18));
            y += 20;
        }
    }

    private void renderInspector(DevModeScreen screen, GuiGraphics graphics) {
        Font font = screen.font();
        int left = screen.width - DevUi.RIGHT_PANEL_W;
        DevUi.sidePanel(graphics, left, screen.width, screen.height, false);
        if (selected == null) {
            return;
        }
        graphics.drawString(font, DevUi.text("dialogue.node"), left + 8, DevUi.TOP_BAR_H + 6, DevUi.ACCENT, false);
        graphics.drawString(font, font.plainSubstrByWidth(selected, DevUi.RIGHT_PANEL_W - 16), left + 8, DevUi.TOP_BAR_H + 18, DevUi.MUTED, false);
        form.render(graphics, font);
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

    private void select(DevModeScreen screen, @Nullable String node) {
        if (node == null ? selected != null : !node.equals(selected)) {
            saveEdits(screen);
        }
        selected = node;
        if (node != null) {
            screen.showInspector();
        }
    }

    @Override
    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button) {
        if (mouseX < DevUi.LEFT_PANEL_W && mouseY >= DevUi.TOP_BAR_H + 24 && mouseY < screen.height - 100) {
            int row = ((int) mouseY - (DevUi.TOP_BAR_H + 24)) / 20;
            if (row >= 0 && row < cards.size()) {
                Card card = cards.get(row);
                select(screen, card.id());
                centerOn(screen, card.x() + NODE_W / 2.0, card.y() + NODE_H / 2.0);
                screen.rebuild();
                return true;
            }
            return false;
        }
        if (!onCanvas(screen, mouseX, mouseY)) {
            return false;
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
                linkingFrom = card.id();
                return true;
            }
            if (DevUi.inside(worldX, worldY, card.x(), card.y(), NODE_W, NODE_H)) {
                select(screen, card.id());
                dragging = card.id();
                dragBefore = state();
                dragOffsetX = worldX - card.x();
                dragOffsetY = worldY - card.y();
                screen.rebuild();
                return true;
            }
        }
        select(screen, null);
        startPan(mouseX, mouseY);
        screen.rebuild();
        return true;
    }

    private List<MenuItem> menu(DevModeScreen screen, @Nullable Card clicked) {
        String npc = speakers().getFirst();
        List<MenuItem> items = new ArrayList<>();
        if (clicked != null) {
            String node = clicked.id();
            select(screen, node);
            screen.rebuild();
            items.add(MenuItem.of(Component.literal("★ ").append(DevUi.text("dialogue.set_start")), () -> setStart(screen, node)));
            items.add(MenuItem.of(Component.literal("💬 ").append(DevUi.text("dialogue.link_npc_line")), () -> createLinked(screen, node, "line", npc)));
            items.add(MenuItem.of(Component.literal("🗣 ").append(DevUi.text("dialogue.link_player_line")), () -> createLinked(screen, node, "line", "player")));
            items.add(MenuItem.of(Component.literal("❓ ").append(DevUi.text("dialogue.link_choice")), () -> createLinked(screen, node, "choice", null)));
            items.add(MenuItem.of(Component.literal("⚖ ").append(DevUi.text("dialogue.link_condition")), () -> createLinked(screen, node, "condition", null)));
            items.add(MenuItem.of(Component.literal("⚡ ").append(DevUi.text("dialogue.link_action")), () -> createLinked(screen, node, "action", null)));
            items.add(MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("dialogue.delete_named", node)), () -> deleteNode(screen, node)));
            return items;
        }
        int x = menuWorldX;
        int y = menuWorldY;
        items.add(MenuItem.of(Component.literal("💬 ").append(DevUi.text("dialogue.add_npc_line")), () -> createNode(screen, "line", npc, x, y, null)));
        items.add(MenuItem.of(Component.literal("🗣 ").append(DevUi.text("dialogue.add_player_line")), () -> createNode(screen, "line", "player", x, y, null)));
        items.add(MenuItem.of(Component.literal("❓ ").append(DevUi.text("dialogue.add_choice")), () -> createNode(screen, "choice", null, x, y, null)));
        items.add(MenuItem.of(Component.literal("⚖ ").append(DevUi.text("dialogue.add_condition")), () -> createNode(screen, "condition", null, x, y, null)));
        items.add(MenuItem.of(Component.literal("⚡ ").append(DevUi.text("dialogue.add_action")), () -> createNode(screen, "action", null, x, y, null)));
        items.add(MenuItem.of(Component.literal("🏁 ").append(DevUi.text("dialogue.add_end")), () -> createNode(screen, "end", null, x, y, null)));
        items.add(MenuItem.of(Component.literal("📐 ").append(DevUi.text("dialogue.auto_layout")), () -> {
            edit(screen, DevUi.str("history.dialogue_layout"), () -> {
                positions.clear();
                positions.putAll(DialogueGraph.autoLayout(json));
            });
            screen.rebuild();
        }));
        return items;
    }

    @Override
    boolean mouseDragged(DevModeScreen screen, double mouseX, double mouseY) {
        if (dragging != null) {
            positions.put(dragging, new int[]{
                    (int) Math.round((worldX(mouseX) - dragOffsetX) / 8.0) * 8,
                    (int) Math.round((worldY(mouseY) - dragOffsetY) / 8.0) * 8});
            rebuildCards();
            return true;
        }
        return pan(mouseX, mouseY);
    }

    @Override
    boolean mouseReleased(DevModeScreen screen, double mouseX, double mouseY) {
        stopPan();
        if (linkingFrom != null) {
            String from = linkingFrom;
            linkingFrom = null;
            Card target = cardAt(worldX(mouseX), worldY(mouseY));
            if (target != null && !target.id().equals(from)) {
                connect(screen, from, target.id());
            }
            return true;
        }
        if (dragging != null) {
            dragging = null;
            State before = dragBefore;
            dragBefore = null;
            State after = state();
            if (before != null && !before.layout().equals(after.layout())) {
                saveLayout();
                remember(screen, DevUi.str("history.dialogue_move"), before, after);
            }
            return true;
        }
        return false;
    }

    @Override
    boolean keyPressed(DevModeScreen screen, int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            screen.closeDialogue();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) && selected != null) {
            deleteNode(screen, selected);
            return true;
        }
        return false;
    }
}
