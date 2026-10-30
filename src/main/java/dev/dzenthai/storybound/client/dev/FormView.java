package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.Catalog.Entry;
import dev.dzenthai.storybound.client.dev.Schemas.Kind;
import dev.dzenthai.storybound.client.dev.Schemas.Logic;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.client.document.DocumentTexts;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryRenames;
import dev.dzenthai.storybound.module.quest.DifficultyScale;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * A form for a JSON object, built from its schema ({@link Schemas}): a row per field, one level at a time. Nested
 * objects, lists and the logic ("when / if / then": triggers, conditions, actions) open in place, with breadcrumbs
 * back. Fields get a picker by what they name: an item, a mob, a structure, a biome, a point of interest, a quest, a
 * dialogue, a document, a boss, a Codex entry, a waypoint, a flag, a dialogue's node or speaker; positions get
 * "here". Text boxes change the JSON as typed ({@link Host#edited}); buttons change it and save ({@link Host#changed}).
 */
final class FormView {
    private static final int LABEL_H = 10;
    private static final int CONTROL_H = 18;
    private static final int ROW_GAP = 4;
    /** The radius a waypoint gets when it becomes a search area in the form. */
    private static final int NEW_AREA_RADIUS = 16;
    private static final String WAYPOINT = "waypoint";

    /** What the form edits and how its changes are kept. */
    interface Host {
        /** The object edited now; read again on every build, as saving replaces it. */
        JsonObject root();

        /** The translation key of a text field at that path ({@code stages.hunt.}) of the object. */
        String textKey(String path, String field);

        /** A text box changed the JSON: saved later (Enter, another selection, closing). */
        void edited();

        /** A button changed the JSON: saved now. */
        void changed();

        /** The NPC binding the form edits, for the fields that belong to the NPC rather than its file; null otherwise. */
        default @Nullable ResourceLocation npcBinding() {
            return null;
        }

        /** The dialogue whose node the form edits (its presentation and camera decide what a line shows); null otherwise. */
        default @Nullable JsonObject dialogue() {
            return null;
        }

        /** Shows the dialogue's node on its graph, selected (an arrow's target, clicked in the form). */
        default void revealNode(String node) {}

        /** The quest the form edits, for its stages' and objectives' names; null otherwise. */
        default @Nullable ResourceLocation quest() {
            return null;
        }

        /** Renames a stage of the quest ({@code objective} empty) or an objective of a stage, with what names it. */
        default void renameInQuest(String stage, String objective, String to) {}

        /** Renames a section of the Codex entry the form edits, with its text and what names it. */
        default void renameCodexSection(String from, String to) {}

        /** The story node the form edits (a boss's and a Codex entry's long texts are named after it); null otherwise. */
        default @Nullable StoryLinks.NodeKey node() {
            return null;
        }

        /** Values for a field that names something inside the definition (a dialogue's nodes, its speakers); usually none. */
        default List<String> choices(String field) {
            return List.of();
        }
    }

    /**
     * A page of the form: a nested object, list or logic element.
     *
     * @param removable an optional field: the page offers to remove it
     */
    private record Frame(List<Object> path, Node schema, String label, boolean removable) {}

    /** A line of text on the form: a label (cut to the width) or a line of wrapped help. */
    private record Label(@Nullable Component text, @Nullable FormattedCharSequence line, int x, int y, int color) {
        Label(Component text, int x, int y, int color) {
            this(text, null, x, y, color);
        }
    }

    private final Host host;
    private @Nullable Node rootSchema;
    private Set<String> hidden = Set.of();
    private String rootLabel = "";
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final List<Label> labels = new ArrayList<>();
    private int scroll;
    private int contentHeight;

    // While building: where the next row goes and what is visible.
    private DevModeScreen screen;
    private int x;
    private int w;
    private int y;
    private int top;
    private int bottom;

    FormView(Host host) {
        this.host = host;
    }

    /** Shows another object: back to its first page. */
    void reset(@Nullable Node schema, String label, Set<String> hiddenFields) {
        rootSchema = schema;
        rootLabel = label;
        hidden = hiddenFields;
        frames.clear();
        scroll = 0;
    }

    void scroll(double amount) {
        scroll = Math.max(0, scroll - (int) Math.round(amount * 24));
    }

    // --- JSON access by path, so pages survive the object being replaced by a fresh copy after saving

    private @Nullable JsonElement get(List<Object> path) {
        JsonElement current = host.root();
        for (Object step : path) {
            if (step instanceof String key) {
                current = current.isJsonObject() ? current.getAsJsonObject().get(key) : null;
            } else {
                int index = (Integer) step;
                // One thing written alone where a list may stand (an action, an NPC binding's entity) is its first element.
                current = current.isJsonArray() && index < current.getAsJsonArray().size() ? current.getAsJsonArray().get(index)
                        : current.isJsonObject() && index == 0 ? current : null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private @Nullable JsonObject objectAt(List<Object> path) {
        JsonElement element = get(path);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /**
     * The object at the path, made (with the containers above it) when missing. A step into a list keeps the list;
     * a single object where a list is expected (one action written alone) becomes its first element.
     */
    JsonObject ensureObject(List<Object> path) {
        JsonElement current = host.root();
        for (int i = 0; i < path.size(); i++) {
            JsonElement next = child(current, path, i);
            boolean list = i + 1 < path.size() && path.get(i + 1) instanceof Integer;
            if (list && next != null && next.isJsonObject()) {
                JsonArray wrapped = new JsonArray();
                wrapped.add(next);
                next = wrapped;
                put(current, path, i, next);
            } else if (next == null || (list ? !next.isJsonArray() : !next.isJsonObject())) {
                next = list ? new JsonArray() : new JsonObject();
                put(current, path, i, next);
            }
            current = next;
        }
        return current.getAsJsonObject();
    }

    /** The array at the path, made when missing; a single object (one action written alone) becomes its element. */
    private JsonArray ensureArray(List<Object> path) {
        JsonObject parent = ensureObject(path.subList(0, path.size() - 1));
        JsonElement value = child(parent, path, path.size() - 1);
        if (value != null && value.isJsonArray()) {
            return value.getAsJsonArray();
        }
        JsonArray array = new JsonArray();
        if (value != null && value.isJsonObject()) {
            array.add(value);
        }
        put(parent, path, path.size() - 1, array);
        return array;
    }

    /**
     * What holds the element at the path: the list it is an element of, or the object it is a field of, made when
     * missing. Never the object in place of a list, so replacing one action of a list keeps the others.
     */
    JsonElement ensureParent(List<Object> path) {
        List<Object> parent = path.subList(0, path.size() - 1);
        if (parent.isEmpty()) {
            return host.root();
        }
        return path.getLast() instanceof Integer ? ensureArray(parent) : ensureObject(parent);
    }

    private static @Nullable JsonElement child(JsonElement parent, List<Object> path, int i) {
        Object step = path.get(i);
        if (step instanceof String key) {
            return parent.isJsonObject() ? parent.getAsJsonObject().get(key) : null;
        }
        int index = (Integer) step;
        return parent.isJsonArray() && index < parent.getAsJsonArray().size() ? parent.getAsJsonArray().get(index) : null;
    }

    private static void put(JsonElement parent, List<Object> path, int i, JsonElement value) {
        Object step = path.get(i);
        if (step instanceof String key) {
            parent.getAsJsonObject().add(key, value);
        } else if ((Integer) step < parent.getAsJsonArray().size()) {
            parent.getAsJsonArray().set((Integer) step, value);
        } else {
            parent.getAsJsonArray().add(value);
        }
    }

    private static List<Object> with(List<Object> path, Object step) {
        List<Object> longer = new ArrayList<>(path);
        longer.add(step);
        return List.copyOf(longer);
    }

    /** {@code stages.hunt.objectives.wolves.}: list elements by their id when they have one, for stable text keys. */
    private String keyPath(List<Object> path) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            Object step = path.get(i);
            if (step instanceof Integer index) {
                JsonObject element = objectAt(path.subList(0, i + 1));
                out.append(element != null && element.has("id") && element.get("id").isJsonPrimitive() ? element.get("id").getAsString() : index);
            } else {
                out.append(step);
            }
            out.append('.');
        }
        return out.toString();
    }

    /** Pages whose list element was removed meanwhile close. */
    private void dropUnreachable() {
        while (!frames.isEmpty()) {
            List<Object> path = frames.peek().path();
            boolean reachable = true;
            for (int i = 0; i < path.size() && reachable; i++) {
                if (path.get(i) instanceof Integer) {
                    reachable = get(path.subList(0, i + 1)) != null;
                }
            }
            if (reachable) {
                return;
            }
            frames.pop();
        }
    }

    // --- building

    void addWidgets(DevModeScreen screen, int x, int top, int width, int bottom) {
        this.screen = screen;
        this.x = x;
        this.w = width;
        labels.clear();
        if (rootSchema == null) {
            return;
        }
        dropUnreachable();
        int y0 = top;
        if (!frames.isEmpty()) {
            Frame frame = frames.peek();
            screen.add(Button.builder(Component.literal("◂"), b -> {
                frames.pop();
                scroll = 0;
                screen.rebuild();
            }).tooltip(Tooltip.create(DevUi.text("form.back"))).bounds(x, y0, 16, 14).build());
            if (frame.removable()) {
                screen.add(Button.builder(Component.literal("✕"), b -> remove(frame))
                        .tooltip(Tooltip.create(DevUi.text("form.remove_field"))).bounds(x + width - 16, y0, 16, 14).build());
            }
            // Where the page is (greyed), then what it is, as a heading: every page goes on under one, as the node's
            // form under its name (October 30, 2026; before, only the grey path, the page's own name last in it).
            labels.add(new Label(Component.literal(crumbs(screen.font(), width - 40)), x + 20, y0 + 3, DevUi.MUTED));
            labels.add(new Label(Component.literal(screen.font().plainSubstrByWidth(frame.label(), width)), x, y0 + 18, DevUi.ACCENT));
            y0 += 32;
        }
        this.top = y0;
        this.bottom = bottom;
        scroll = Math.max(0, Math.min(scroll, contentHeight - (bottom - y0)));
        this.y = y0 - scroll;
        if (frames.isEmpty()) {
            objectPage(List.of(), rootSchema, hidden, null);
        } else {
            Frame frame = frames.peek();
            page(frame.path(), frame.schema());
        }
        contentHeight = y + scroll - y0;
        if (contentHeight > bottom - y0) {
            labels.add(new Label(Component.literal(scroll > 0 ? "▲" : " ").append(contentHeight - scroll > bottom - y0 ? " ▼" : ""),
                    x + width - 18, bottom - 9, DevUi.MUTED));
        }
    }

    /** The way to the page from the node: the node and the pages above this one (its own name is its heading). */
    private String crumbs(Font font, int width) {
        StringBuilder out = new StringBuilder(rootLabel);
        List<Frame> list = new ArrayList<>(frames);
        for (int i = list.size() - 1; i >= 1; i--) {
            out.append(" › ").append(list.get(i).label());
        }
        String text = out.toString();
        // The deepest part matters most: cut from the left.
        while (font.width(text) > width && text.length() > 4) {
            text = "…" + text.substring(2);
        }
        return text;
    }

    /** Opens a field of the node on its own page at once: a new node's first question ("Who is this NPC?"). */
    void openRoot(String name, String label) {
        Node node = rootSchema == null ? null : Schemas.properties(rootSchema).get(name);
        if (node == null) {
            return;
        }
        Node items = entityListItems(node);
        frames.clear();
        frames.push(new Frame(List.of(name), items != null ? items : node, label, false));
        scroll = 0;
    }

    /** Lists the triggers at once for the node's trigger: a new story event's first question. */
    void askTrigger(String name) {
        if (screen == null) {
            return;
        }
        pickType(Logic.TRIGGER, element -> {
            host.root().add(name, element);
            host.changed();
        });
    }

    private void push(List<Object> path, Node schema, String label, boolean removable) {
        frames.push(new Frame(path, schema, label, removable));
        scroll = 0;
        screen.rebuild();
    }

    private void remove(Frame frame) {
        List<Object> path = frame.path();
        JsonElement parent = get(path.subList(0, path.size() - 1));
        if (parent != null && parent.isJsonObject() && path.getLast() instanceof String key) {
            parent.getAsJsonObject().remove(key);
        }
        frames.pop();
        scroll = 0;
        host.changed();
    }

    private void page(List<Object> path, Node schema) {
        if (isEntitySelector(schema)) {
            entityPage(path);
            return;
        }
        if (entityListItems(schema) != null) {
            listPage(path, entityListItems(schema));
            return;
        }
        switch (Schemas.kind(schema)) {
            case CONDITIONS -> listPage(path, Schemas.logic("condition"));
            case ACTIONS -> listPage(path, Schemas.logic("action"));
            case LIST -> listPage(path, Schemas.items(schema));
            case MAP -> mapPage(path, Schemas.values(schema));
            case TRIGGER -> logicPage(path, Logic.TRIGGER);
            case CONDITION -> logicPage(path, Logic.CONDITION);
            case ACTION -> logicPage(path, Logic.ACTION);
            case VARIANTS -> variantsPage(path, schema);
            default -> objectPage(path, schema, Set.of(), null);
        }
    }

    /** Is the next row of that height inside the visible part? Moves past it either way. */
    private boolean row(int height) {
        boolean visible = y >= top && y + height <= bottom;
        y += height + ROW_GAP;
        return visible;
    }

    private int rowY(int height) {
        return y - height - ROW_GAP;
    }

    private void text(Component text, int color) {
        for (FormattedCharSequence line : screen.font().split(text, w)) {
            if (y >= top && y + LABEL_H <= bottom) {
                labels.add(new Label(null, line, x, y, color));
            }
            y += LABEL_H;
        }
        y += 2;
    }

    private void objectPage(List<Object> path, Node schema, Set<String> skip, @Nullable String typeField) {
        Set<String> required = Schemas.required(schema);
        boolean npc = path.isEmpty() && host.npcBinding() != null;
        boolean waypoint = path.isEmpty() && Schemas.properties(schema).containsKey("target") && Schemas.properties(schema).containsKey("show_ring");
        boolean dialogue = path.isEmpty() && isDialogue(schema);
        boolean participant = path.size() == 2 && "participants".equals(path.getFirst()) && Schemas.properties(schema).containsKey("portrait");
        JsonObject inDialogue = host.dialogue();
        String node = inDialogue != null && path.isEmpty() && host.root().get("type") instanceof JsonPrimitive type ? type.getAsString() : null;
        boolean option = inDialogue != null && path.size() == 2 && "options".equals(path.getFirst()) && Schemas.properties(schema).containsKey("locked");
        boolean document = path.isEmpty() && Schemas.properties(schema).containsKey("journal") && Schemas.properties(schema).containsKey("kind");
        boolean journal = path.size() == 1 && "journal".equals(path.getFirst()) && Schemas.properties(schema).containsKey("add_on_read");
        boolean quest = path.isEmpty() && Schemas.properties(schema).containsKey("stages") && Schemas.properties(schema).containsKey("fail_when");
        String inside = Schemas.properties(schema).containsKey("objectives") || Schemas.properties(schema).containsKey("since_stage") ? insideQuest(path) : null;
        boolean boss = path.isEmpty() && Schemas.properties(schema).containsKey("participation");
        boolean codex = path.isEmpty() && Schemas.properties(schema).containsKey("show_kills");
        boolean section = path.size() == 2 && "sections".equals(path.getFirst()) && Schemas.properties(schema).containsKey("unlock");
        boolean unlock = Schemas.properties(schema).containsKey("on") && Schemas.properties(schema).containsKey("count");
        boolean observed = Schemas.properties(schema).containsKey("time") && Schemas.properties(schema).containsKey("distance")
                && Schemas.properties(schema).containsKey("block");
        String rootForm = path.isEmpty() && inDialogue == null && host.node() != null ? ROOT_FORMS.get(host.node().type()) : null;
        String form = node != null ? "node." + node : option ? "option" : document ? "document" : journal ? "journal" : quest ? "quest"
                : inside != null ? inside : boss ? "boss" : codex ? "codex" : section ? "codex.section" : unlock ? "unlock" : observed ? "observed" : rootForm;
        Map<String, String> groups = npc ? NPC_GROUPS : waypoint ? WAYPOINT_GROUPS : dialogue ? DIALOGUE_GROUPS
                : form != null ? NODE_GROUPS.getOrDefault(form, Map.of()) : Map.of();
        JsonObject object = objectAt(path);
        for (Map.Entry<String, Node> property : ordered(Schemas.properties(schema), form != null ? NODE_ORDER.getOrDefault(form, List.of()) : List.of())) {
            String name = property.getKey();
            if (skip.contains(name) || name.equals(typeField) || npc && !npcFieldShown(name, object) || waypoint && !waypointFieldShown(name, object)
                    || dialogue && !dialogueFieldShown(name, object) || participant && !participantFieldShown(name, object)
                    || form != null && !nodeFieldShown(form, name, object, inDialogue)) {
                continue;
            }
            if (groups.containsKey(name)) {
                group(DevUi.text(groups.get(name)));
            }
            if (npc && name.equals("proximity")) {
                proximityField(path, property.getValue(), object);
            } else if ("node.choice".equals(form) && name.equals("timeout")) {
                timeoutField(path, property.getValue(), object);
            } else if ("document".equals(form) && name.equals("journal")) {
                // Two settings: shown here, not behind a row of their own.
                objectPage(with(path, "journal"), property.getValue(), Set.of(), null);
            } else if ("document".equals(form) && name.equals("custom_model_data")) {
                iconField(path, name, object);
            } else if ("quest".equals(form) && name.equals("repeatable")) {
                repeatField(path, property.getValue(), object);
            } else if ("quest".equals(form) && name.equals("category")) {
                questTypeField(path, object);
            } else if ("boss".equals(form) && BOSS_PARTS.contains(name)) {
                bossPartField(path, name, property.getValue(), object);
            } else if ("boss".equals(form) && name.equals("participation")) {
                percentField(path, name, property.getValue(), object, fieldLabel(name, false, schema));
            } else if ("codex".equals(form) && name.equals("category")) {
                codexCategoryField(path, object);
            } else if ("codex".equals(form) && name.equals("sections")) {
                codexSectionsField(path, property.getValue(), object);
            } else if ("unlock".equals(form) && name.equals("on")) {
                unlockSourcesField(path, object, fieldLabel(name, false, schema));
            } else {
                field(path, name, property.getValue(), required.contains(name), schema);
            }
            if (npc && name.equals("dialogues")) {
                relationshipField(host.npcBinding());
            }
        }
    }

    /**
     * An NPC binding's form in the order its author asks (October 29, 2026; before, the file's order, with the
     * relationship scale after respawn): who it is, what it says and the relationship its options move; how a talk
     * starts; what happens with several players; the rest. Each group but the first opens with a heading at this field.
     */
    private static final Map<String, String> NPC_GROUPS = Map.of(
            "entity", "npc.group.who",
            "interact", "npc.group.start",
            "exclusive", "npc.group.players",
            "respawn", "npc.group.other");

    /**
     * What depends on a switch shows only while it is on, unless the file still sets it: "instead of trading" with
     * talking by key or right-click, "reply while busy" with talking to one player at a time.
     */
    private static boolean npcFieldShown(String name, @Nullable JsonObject binding) {
        return switch (name) {
            case "override_interact" -> flag(binding, "interact", true) || flag(binding, "override_interact", false);
            case "busy_dialogue" -> flag(binding, "exclusive", false) || binding != null && binding.has("busy_dialogue");
            default -> true;
        };
    }

    private static boolean flag(@Nullable JsonObject object, String name, boolean byDefault) {
        return object != null && object.get(name) instanceof JsonPrimitive value && value.isBoolean() ? value.getAsBoolean() : byDefault;
    }

    /**
     * A waypoint's form in the order its author asks (October 29, 2026; before, the file's order: the colour before when
     * it shows, point or area at the very bottom, the stage even without a quest): its name, where it points and whether
     * a point or an area; how it looks; when it shows.
     */
    private static final Map<String, String> WAYPOINT_GROUPS = Map.of(
            "name", "waypoint.group.where",
            "color", "waypoint.group.look",
            "quest", "waypoint.group.when");

    /** The stage only with a quest (or when the file still names one). */
    private static boolean waypointFieldShown(String name, @Nullable JsonObject waypoint) {
        return !name.equals("stage") || waypoint != null && (waypoint.get("quest") instanceof JsonPrimitive quest && !quest.getAsString().isBlank()
                || waypoint.has("stage"));
    }

    /**
     * A dialogue's own settings (its lines are on the graph) in the order its author asks (October 29, 2026; before, the
     * file's order, who speaks first and the camera after what an interruption does): how it shows, with the camera
     * only on the dialogue screen; who speaks; what an interruption does.
     */
    private static final Map<String, String> DIALOGUE_GROUPS = Map.of(
            "presentation", "dialogue.group.show",
            "participants", "dialogue.group.who",
            "closable", "dialogue.group.abort");

    private static boolean isDialogue(Node schema) {
        Map<String, Node> properties = Schemas.properties(schema);
        return properties.containsKey("presentation") && properties.containsKey("nodes") && properties.containsKey("participants");
    }

    /** The camera turns only on the dialogue screen (subtitles and bubbles keep the player's view). */
    private static boolean dialogueFieldShown(String name, @Nullable JsonObject dialogue) {
        boolean screen = !(dialogue != null && dialogue.get("presentation") instanceof JsonPrimitive shown) || shown.getAsString().equals("screen");
        return !name.equals("camera") || screen || dialogue != null && dialogue.has("camera");
    }

    /** A portrait is for a speaker without an entity; one with an entity shows the entity. */
    private static boolean participantFieldShown(String name, @Nullable JsonObject participant) {
        boolean virtual = participant != null && participant.get("type") instanceof JsonPrimitive type && type.getAsString().equals("virtual");
        return !name.equals("portrait") || virtual || participant != null && participant.has("portrait");
    }

    /**
     * Who speaks besides the player, in words: "npc (started with) · narrator «Narrator»". Without any written, the
     * default: the entity the dialogue was started with. Before, a count.
     */
    private static String participantsSummary(@Nullable JsonElement value) {
        if (!(value instanceof JsonObject participants) || participants.isEmpty()) {
            return DevUi.str("dialogue.participants.default");
        }
        List<String> parts = new ArrayList<>();
        participants.entrySet().forEach(entry -> parts.add(participantSummary(entry.getKey(), entry.getValue())));
        return String.join(" · ", parts);
    }

    private static String participantSummary(String key, JsonElement value) {
        JsonObject participant = value instanceof JsonObject object ? object : new JsonObject();
        boolean virtual = participant.get("type") instanceof JsonPrimitive type && type.getAsString().equals("virtual");
        String name = ClientDevStore.readStoryText(participant, "name", "");
        return key + " (" + DevUi.str(virtual ? "dialogue.participant.virtual" : "dialogue.participant.bound")
                + (name.isBlank() ? "" : " «" + name + "»") + ")";
    }

    /**
     * The nodes of a dialogue's graph and a choice's options, in the order their author asks (October 29, 2026; before,
     * the file's order: an option's id first, a jump's node before its dialogue): what is said and where it goes first;
     * then, in groups, the rest. A line's camera shows only where it turns (the dialogue screen with the camera on).
     */
    private static final Map<String, List<String>> NODE_ORDER = Map.ofEntries(
            Map.entry("node.jump", List.of("dialogue", "node")),
            Map.entry("option", List.of("text", "next", "conditions", "locked", "locked_text", "actions", "once", "think", "id")),
            // A document (October 30, 2026; before, the file's order, the journal behind a row, the contract for every kind).
            Map.entry("document", List.of("title", "kind", "custom_model_data", "style", "journal", "on_read", "contract", "content")),
            Map.entry("journal", List.of("add_on_read", "category")),
            // A quest (October 30, 2026; before, the file's order: its group and scope before its title, its texts per
            // state, start, stages and rewards shown as counts): what it is; how it goes; what it gives and changes;
            // for whom. Then its stages and their objectives.
            Map.entry("quest", List.of("title", "description", "text", "category", "difficulty", "start", "stages", "time_limit", "fail_when",
                    "rewards", "on_start", "on_complete", "on_failed", "scope", "repeatable", "map_visibility", "notify")),
            Map.entry("stage", List.of("id", "log", "objectives", "next", "waypoints", "on_enter")),
            Map.entry("objective", List.of("id", "text", "trigger", "conditions", "count", "items", "since_stage", "optional", "hidden", "hint", "hints")),
            // A boss and a Codex entry (October 30, 2026; before, the file's order, a section's text an id of a file):
            // who or what it is; its sections in the tab, each with its text and when it opens; what a victory does.
            Map.entry("boss", List.of("title", "entity", "notify", "lore", "tactics", "rewards", "participation", "on_first_kill", "on_kill")),
            Map.entry("codex", List.of("title", "category", "entity", "show_kills", "notify", "sections")),
            Map.entry("codex.section", List.of("title", "text", "unlock", "id")),
            Map.entry("unlock", List.of("on", "count", "conditions")),
            // A story event, a script block, a flag (October 30, 2026; before, the file's order without headings): when
            // and what; what the block is and where it leads, its file last; what the flag holds, then where.
            Map.entry("event", List.of("trigger", "conditions", "once", "actions", "on_fail")),
            Map.entry("script", List.of("title", "description", "hidden", "leads_to", "file", "hook")),
            Map.entry("flag", List.of("description", "type", "default", "scope", "sync")),
            // The trigger "observed": what is looked at, how long and from how far, what the bar says.
            Map.entry("observed", List.of("entity", "block", "pos", "dimension", "time", "distance", "text")));

    /** The forms known by the node they edit rather than by their fields. */
    private static final Map<ResourceLocation, String> ROOT_FORMS = Map.of(
            ClientDevStore.TYPE_EVENT, "event",
            ClientDevStore.TYPE_SCRIPT, "script",
            ClientDevStore.TYPE_FLAG, "flag",
            ClientDevStore.TYPE_CHAPTER, "chapter");

    /** A boss's sections, each shown in its form under a heading of its own, with whether the entry has it. */
    private static final Set<String> BOSS_PARTS = Set.of("lore", "tactics", "rewards");

    /**
     * The headings of a form's groups, each at its first field (October 30, 2026: the first group's too, so every form
     * goes on from the name under a heading; before, its first fields read as the name's). A small page (a dialogue's
     * jump, a condition) has none: its title says what it is. A lone field joins a neighbouring group rather than
     * standing under a heading of its own, except a technical one at the end ("Other").
     */
    private static final Map<String, Map<String, String>> NODE_GROUPS = Map.ofEntries(
            Map.entry("boss", Map.of("title", "boss.group.who", "participation", "boss.group.victory")),
            Map.entry("codex", Map.of("title", "codex.group.entry")),
            Map.entry("node.line", Map.of("speaker", "node.group.line", "sound", "node.group.sound_time", "shot", "node.group.camera")),
            // A dialogue's other nodes: under the name, a heading with what the node is.
            Map.entry("node.choice", Map.of("options", "node.group.choice")),
            Map.entry("node.condition", Map.of("conditions", "node.group.condition")),
            Map.entry("node.action", Map.of("actions", "node.group.action")),
            Map.entry("node.random", Map.of("options", "node.group.random")),
            Map.entry("node.jump", Map.of("dialogue", "node.group.jump")),
            Map.entry("node.call", Map.of("dialogue", "node.group.call")),
            Map.entry("node.end", Map.of("actions", "node.group.end")),
            Map.entry("option", Map.of("text", "option.group.answer", "conditions", "option.group.when", "actions", "option.group.chosen", "id", "option.group.other")),
            Map.entry("document", Map.of("title", "document.group.what", "custom_model_data", "document.group.look", "journal", "document.group.journal",
                    "on_read", "document.group.read", "contract", "document.group.contract", "content", "document.group.other")),
            Map.entry("quest", Map.of("title", "quest.group.about", "start", "quest.group.flow", "rewards", "quest.group.reward", "scope", "quest.group.who")),
            Map.entry("stage", Map.of("id", "stage.group.stage", "objectives", "stage.group.objectives", "waypoints", "stage.group.during")),
            Map.entry("objective", Map.of("id", "objective.group.what", "trigger", "objective.group.counts", "optional", "objective.group.shown")),
            Map.entry("event", Map.of("trigger", "event.group.when", "actions", "event.group.does")),
            Map.entry("script", Map.of("title", "script.group.block", "leads_to", "script.group.leads", "file", "script.group.file")),
            Map.entry("flag", Map.of("description", "flag.group.what", "scope", "flag.group.where")),
            Map.entry("chapter", Map.of("title", "chapter.group.chapter")));

    private static boolean nodeFieldShown(String form, String name, @Nullable JsonObject object, @Nullable JsonObject dialogue) {
        if (form.equals("node.line") && (name.equals("shot") || name.equals("transition"))) {
            boolean screen = !(dialogue != null && dialogue.get("presentation") instanceof JsonPrimitive shown) || shown.getAsString().equals("screen");
            boolean camera = !(dialogue != null && dialogue.get("camera") instanceof JsonPrimitive mode) || !mode.getAsString().equals("off");
            return screen && camera || object != null && object.has(name);
        }
        if (form.equals("objective") && name.equals("since_stage")) {
            // Only a trigger counts "since the stage".
            return object != null && (object.has("trigger") || object.has("since_stage"));
        }
        if (form.equals("document") && name.equals("contract")) {
            // Only a contract has one.
            return object != null && (object.get("kind") instanceof JsonPrimitive kind && kind.getAsString().equals("contract") || object.has("contract"));
        }
        if (form.equals("journal") && name.equals("category")) {
            boolean added = !(object != null && object.get("add_on_read") instanceof JsonPrimitive add) || add.getAsBoolean();
            return added || object.has("category");
        }
        if (form.equals("option") && name.equals("locked_text")) {
            return object != null && (object.get("locked") instanceof JsonPrimitive locked && locked.getAsString().equals("show") || object.has("locked_text"));
        }
        if (form.equals("observed")) {
            // An entity, or a block: the other kind shows only while this one is empty (or both are written).
            boolean entity = object != null && object.has("entity");
            boolean block = object != null && (object.has("block") || object.has("pos"));
            return switch (name) {
                case "entity" -> !block || entity;
                case "block", "pos" -> !entity || block;
                case "dimension" -> object != null && (object.has("pos") || object.has("dimension"));
                default -> true;
            };
        }
        if (form.equals("codex") && name.equals("show_kills")) {
            // Kills are counted of the entry's entity.
            return object != null && (object.has("entity") || object.has("show_kills"));
        }
        if (form.equals("unlock") && name.equals("count")) {
            // A read counts once, an action opens at once: only sightings and kills are counted.
            List<String> sources = stringsOf(object != null ? object.get("on") : null);
            return sources.isEmpty() || sources.contains("seen") || sources.contains("killed") || object.has("count");
        }
        return true;
    }

    /**
     * A quest's parts in words, for the rows that open them: its stages by name ("find_tracks → kill → report"), its
     * texts per state ("its own: active, completed"), its start ("when: talked to an NPC + conditions"), its rewards
     * ("2 items, 1 of 3 to choose, 50 XP"), an objective's items ("5 × wolf hide, handed in"). Null for other rows.
     */
    private static @Nullable String questSummary(String name, Node owner, @Nullable JsonElement value) {
        Map<String, Node> properties = Schemas.properties(owner);
        boolean quest = properties.containsKey("stages") && properties.containsKey("fail_when");
        if (quest && name.equals("stages")) {
            List<String> ids = new ArrayList<>();
            if (value instanceof JsonArray stages) {
                stages.forEach(stage -> ids.add(stage instanceof JsonObject object && object.get("id") instanceof JsonPrimitive id ? id.getAsString() : "?"));
            }
            return ids.isEmpty() ? "—" : String.join(" → ", ids);
        }
        if (quest && name.equals("text")) {
            List<String> states = new ArrayList<>();
            if (value instanceof JsonObject texts) {
                for (String state : List.of("active", "completed", "failed")) {
                    if (texts.has(state)) {
                        states.add(DevUi.str("field." + state).toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            return states.isEmpty() ? DevUi.str("quest.texts_none") : DevUi.str("quest.texts_own", String.join(", ", states));
        }
        if (quest && name.equals("start")) {
            if (!(value instanceof JsonObject start) || !(start.get("trigger") instanceof JsonObject trigger) || !trigger.has("type")) {
                return DevUi.str("quest.start_none");
            }
            boolean conditions = start.get("conditions") instanceof JsonArray list ? !list.isEmpty() : start.get("conditions") instanceof JsonObject;
            return DevUi.str("quest.start_when", typeName(Logic.TRIGGER, trigger.get("type").getAsString())) + (conditions ? DevUi.str("quest.and_conditions") : "");
        }
        if (quest && name.equals("rewards")) {
            if (value == null) {
                return "—";
            }
            if (value instanceof JsonArray || value instanceof JsonObject old && old.has("type")) {
                return DevUi.str("quest.rewards_actions", value instanceof JsonArray list ? list.size() : 1);
            }
            JsonObject rewards = value.getAsJsonObject();
            List<String> parts = new ArrayList<>();
            if (rewards.get("items") instanceof JsonArray items && !items.isEmpty()) {
                parts.add(DevUi.str("quest.rewards_items", items.size()));
            }
            if (rewards.get("choice") instanceof JsonArray choice && !choice.isEmpty()) {
                parts.add(DevUi.str("quest.rewards_choice", choice.size()));
            }
            if (rewards.get("xp") instanceof JsonPrimitive xp && xp.isNumber() && xp.getAsInt() > 0) {
                parts.add(DevUi.str("quest.rewards_xp", xp.getAsInt()));
            }
            if (rewards.has("loot")) {
                parts.add(DevUi.str("quest.rewards_loot"));
            }
            if (rewards.get("actions") instanceof JsonArray actions && !actions.isEmpty() || rewards.get("actions") instanceof JsonObject) {
                parts.add(DevUi.str("quest.rewards_more"));
            }
            if (parts.isEmpty()) {
                return "—";
            }
            boolean claim = !(rewards.get("claim") instanceof JsonPrimitive claimed) || claimed.getAsBoolean();
            return String.join(", ", parts) + (claim ? "" : DevUi.str("quest.rewards_at_once"));
        }
        if (name.equals("items") && properties.containsKey("since_stage") && value instanceof JsonObject items) {
            String what = items.get("item") instanceof JsonPrimitive item ? item.getAsString() : items.get("tag") instanceof JsonPrimitive tag ? "#" + tag.getAsString() : "?";
            boolean consume = items.get("consume") instanceof JsonPrimitive consumed && consumed.getAsBoolean();
            return what + (consume ? DevUi.str("quest.items_handed_in") : DevUi.str("quest.items_had"));
        }
        return null;
    }

    /**
     * A Codex section (the condition "Codex entry open", the action "Open a Codex section"): a button with it by its
     * title and a dropdown of the chosen entry's sections, "The whole entry" first (October 30, 2026; before, its id
     * typed blind).
     */
    private void codexSectionField(List<Object> path, String name, @Nullable JsonElement value, Component label) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        JsonObject owner = objectAt(path);
        ResourceLocation entry = owner != null && owner.get("entry") instanceof JsonPrimitive id ? ResourceLocation.tryParse(id.getAsString()) : null;
        Map<String, String> sections = new LinkedHashMap<>();
        if (entry != null && ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CODEX, entry)).get("sections") instanceof JsonArray list) {
            for (JsonElement element : list) {
                if (element instanceof JsonObject section && section.get("id") instanceof JsonPrimitive id) {
                    String title = ClientDevStore.readStoryText(section, "title", "");
                    sections.put(id.getAsString(), title.isBlank() ? id.getAsString() : title + " (" + id.getAsString() + ")");
                }
            }
        }
        String current = value instanceof JsonPrimitive written ? written.getAsString() : "";
        String shown = current.isEmpty() ? DevUi.str("codex.whole_entry") : sections.getOrDefault(current, DevUi.str("codex.no_such_section", current));
        int buttonX = x;
        Button button = Button.builder(Component.literal(shown + " ▾"), b -> {
            List<DevModeScreen.MenuItem> items = new ArrayList<>();
            items.add(DevModeScreen.MenuItem.of(Component.literal((current.isEmpty() ? "✓ " : "   ") + DevUi.str("codex.whole_entry")), () -> {
                ensureObject(path).remove(name);
                host.changed();
            }));
            sections.forEach((id, title) -> items.add(DevModeScreen.MenuItem.of(Component.literal((id.equals(current) ? "✓ " : "   ") + title), () -> {
                ensureObject(path).addProperty(name, id);
                host.changed();
            })));
            if (entry == null) {
                screen.status(DevUi.text("codex.pick_entry_first"), DevUi.WARN);
            }
            screen.openMenu(buttonX, rowTop + CONTROL_H, items);
        }).bounds(x, rowTop, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("codex.section_tooltip")));
        screen.add(button);
    }

    /**
     * A boss's section (lore, tactics, rewards) under a heading of its own: whether the entry has it (a section left out
     * is not in the Bosses tab, whatever its text), and while it does its fields here, its text and when it opens first
     * (October 30, 2026; before, a row each opening a page with the text as an id of a file).
     */
    private void bossPartField(List<Object> path, String name, Node node, @Nullable JsonObject boss) {
        group(fieldLabel(name, false));
        boolean on = boss != null && boss.get(name) instanceof JsonObject;
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("boss.part_shown"));
            Button toggle = Button.builder(Component.literal(DevUi.yesNo(on)), b -> {
                if (on) {
                    // The text file stays: turning the section on again brings it back.
                    ensureObject(path).remove(name);
                } else {
                    ensureObject(path).add(name, new JsonObject());
                }
                host.changed();
            }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
            toggle.setTooltip(Tooltip.create(DevUi.text("boss.part_tooltip." + name)));
            screen.add(toggle);
        }
        if (on) {
            objectPage(with(path, name), node, Set.of(), null);
        }
    }

    /**
     * The long text ({@code .sbt}) a boss's section or a Codex section shows: its own {@code text}, else the one named
     * after it ({@code <ns>:bosses/<boss>/<section>}, {@code <ns>:codex/<entry>/<section id>}). Null elsewhere, and for
     * a Codex section without a name yet.
     */
    private @Nullable ResourceLocation longTextFile(List<Object> path) {
        StoryLinks.NodeKey key = host.node();
        if (key == null) {
            return null;
        }
        JsonObject owner = objectAt(path);
        ResourceLocation id = key.id();
        String named;
        if (key.type().equals(ClientDevStore.TYPE_BOSS) && path.size() == 1 && path.getFirst() instanceof String part && BOSS_PARTS.contains(part)) {
            named = "bosses/" + id.getPath() + "/" + part;
        } else if (key.type().equals(ClientDevStore.TYPE_CODEX) && path.size() == 2 && "sections".equals(path.getFirst())
                && owner != null && owner.get("id") instanceof JsonPrimitive section && !section.getAsString().isBlank()) {
            named = "codex/" + id.getPath() + "/" + section.getAsString();
        } else {
            return null;
        }
        ResourceLocation own = owner != null && owner.get("text") instanceof JsonPrimitive text ? ResourceLocation.tryParse(text.getAsString()) : null;
        return own != null ? own : ResourceLocation.fromNamespaceAndPath(id.getNamespace(), named);
    }

    /**
     * A section's long text: a button with its first line opens the text window (with markup), saved to its file at
     * once. A boss's rewards show a text only when they name it, so the first one written names its file.
     */
    private void longTextFileField(List<Object> path, String name, Component label, ResourceLocation textId) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        String current = DocumentTexts.load(textId, ClientDevStore.activeLanguage()).orElse("");
        String first = current.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        String shown = screen.font().plainSubstrByWidth("✎ " + (first.isEmpty() ? DevUi.str("text.empty") : first), w - 8);
        StoryLinks.NodeKey key = host.node();
        String section = path.getLast() instanceof String part ? fieldLabel(part, false).getString() : sectionName(objectAt(path));
        Button button = Button.builder(Component.literal(shown), b -> screen.textEditor().open(
                DevUi.text("text.title_section", key != null ? StoryView.title(key) : "", section), current, StoryInspector.hint(textId),
                text -> {
                    DevRequests.saveText(textId, text);
                    JsonObject owner = ensureObject(path);
                    if ("rewards".equals(path.getLast()) && !owner.has(name) && !text.isBlank()) {
                        owner.addProperty(name, textId.toString());
                        host.changed();
                    }
                }, null)).bounds(x, rowTop, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("text.edit_tooltip", textId.toString())));
        screen.add(button);
    }

    private static String sectionName(@Nullable JsonObject section) {
        String title = section != null ? ClientDevStore.readStoryText(section, "title", "") : "";
        return !title.isBlank() ? title : section != null && section.get("id") instanceof JsonPrimitive id ? id.getAsString() : "";
    }

    /**
     * A Codex entry's sections as its author thinks of them (October 30, 2026; before, one row "Sections" hiding them
     * all, the main description among them, so an entry seemed to have no "Opens" of its own): the first section is
     * the entry's main description, right here with its title, text and when it opens, under a word that the entry
     * appears in the Codex when any section opens; the others behind a row of their own. An entry without sections
     * offers to add the main description.
     */
    private void codexSectionsField(List<Object> path, Node node, @Nullable JsonObject entry) {
        Node items = Schemas.items(node);
        List<Object> sectionsPath = with(path, "sections");
        JsonArray sections = entry != null && entry.get("sections") instanceof JsonArray list ? list : new JsonArray();
        group(DevUi.text("codex.group.main"));
        text(DevUi.text("codex.main_hint"), DevUi.MUTED);
        if (!sections.isEmpty() && sections.get(0) instanceof JsonObject && items != null) {
            objectPage(with(sectionsPath, 0), items, Set.of("id"), null);
        } else if (row(CONTROL_H)) {
            screen.add(Button.builder(Component.literal("+ ").append(DevUi.text("codex.add_main")), b -> {
                JsonObject main = new JsonObject();
                main.addProperty("id", "base");
                JsonObject unlock = new JsonObject();
                JsonArray on = new JsonArray();
                on.add(host.root().has("entity") ? "seen" : "manual");
                unlock.add("on", on);
                main.add("unlock", unlock);
                JsonArray list = ensureArray(sectionsPath);
                JsonArray reordered = new JsonArray();
                reordered.add(main);
                list.forEach(reordered::add);
                ensureObject(path).add("sections", reordered);
                host.changed();
            }).bounds(x, rowY(CONTROL_H), w, CONTROL_H).build());
        }
        group(DevUi.text("codex.group.more"));
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("codex.more_sections"));
            List<String> names = new ArrayList<>();
            for (int i = 1; i < sections.size(); i++) {
                names.add(sectionName(sections.get(i) instanceof JsonObject object ? object : null));
            }
            Component summary = Component.literal((names.isEmpty() ? DevUi.str("none") : String.join(" · ", names)) + " ▸");
            Button button = Button.builder(summary, b -> push(sectionsPath, node, fieldLabel("sections", false).getString(), false))
                    .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
            button.setTooltip(Tooltip.create(DevUi.text("codex.more_tooltip")));
            screen.add(button);
        }
    }

    /** A Codex entry's sections by their titles: "Habits · Weaknesses". */
    private static String sectionsSummary(@Nullable JsonElement value) {
        List<String> names = new ArrayList<>();
        if (value instanceof JsonArray sections) {
            sections.forEach(section -> names.add(sectionName(section instanceof JsonObject object ? object : null)));
        }
        return names.isEmpty() ? "—" : String.join(" · ", names);
    }

    /**
     * When a section opens, in words: "when seen or read «Diary» · 3 times + conditions", which opens its settings. A
     * boss's section without its own opens as the game decides by default (lore when seen, tactics and rewards when
     * killed), said so; the first change writes that default out, as the sources are required.
     */
    private void unlockField(List<Object> path, String name, Node node, @Nullable JsonElement value, Component label, @Nullable Tooltip tooltip) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        JsonObject unlock = value instanceof JsonObject object ? object : null;
        List<String> written = stringsOf(unlock != null ? unlock.get("on") : null);
        List<String> sources = written.isEmpty() ? defaultSources(path) : written;
        StringBuilder words = new StringBuilder(DevUi.str("unlock.when", String.join(DevUi.str("unlock.or"), sources.stream().map(FormView::sourceName).toList())));
        if (unlock != null && unlock.get("count") instanceof JsonPrimitive count && count.isNumber() && count.getAsInt() > 1) {
            words.append(DevUi.str("unlock.times", count.getAsInt()));
        }
        if (unlock != null && (unlock.get("conditions") instanceof JsonArray list ? !list.isEmpty() : unlock.get("conditions") instanceof JsonObject)) {
            words.append(DevUi.str("quest.and_conditions"));
        }
        if (written.isEmpty()) {
            words.append(DevUi.str("unlock.by_default"));
        }
        Button button = Button.builder(Component.literal(words + " ▸"), b -> {
            JsonObject opens = ensureObject(with(path, name));
            if (stringsOf(opens.get("on")).isEmpty()) {
                JsonArray on = new JsonArray();
                sources.forEach(on::add);
                opens.add("on", on);
                host.edited();
            }
            push(with(path, name), node, fieldLabel(name, false).getString(), false);
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(tooltip != null ? tooltip : Tooltip.create(DevUi.text("unlock.tooltip")));
        screen.add(button);
    }

    /** The sources a section opens on while it names none: a boss's lore on sight, the rest on a kill; a Codex section always names them. */
    private List<String> defaultSources(List<Object> path) {
        if (path.size() == 1 && "lore".equals(path.getFirst())) {
            return List.of("seen");
        }
        if (path.size() == 1 && path.getFirst() instanceof String part && BOSS_PARTS.contains(part)) {
            return List.of("killed");
        }
        return List.of(host.root().has("entity") ? "seen" : "manual");
    }

    private static List<String> stringsOf(@Nullable JsonElement value) {
        List<String> strings = new ArrayList<>();
        if (value instanceof JsonArray list) {
            list.forEach(element -> {
                if (element instanceof JsonPrimitive string) {
                    strings.add(string.getAsString());
                }
            });
        }
        return strings;
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + text.substring(1);
    }

    /** "on sight", "on a kill", "by an action", "on reading «Diary»". */
    private static String sourceName(String source) {
        if (source.equals("observed") || source.startsWith("observed:")) {
            return DevUi.str("unlock.source.observed", source.contains(":") ? source.substring(source.indexOf(':') + 1) : format(5));
        }
        if (source.startsWith("read:")) {
            ResourceLocation document = ResourceLocation.tryParse(source.substring("read:".length()));
            String title = document == null ? source : StoryView.title(new StoryLinks.NodeKey(ClientDevStore.TYPE_DOCUMENT, document));
            return DevUi.str("unlock.source.read", title);
        }
        return DevUi.str("unlock.source." + source);
    }

    /**
     * What opens a section: switches for sight, a kill and an action, then the documents whose reading opens it, each
     * removable, and "+ Read a document…" with the story's documents (October 30, 2026; before, a list of strings typed
     * as "seen" or "read:storybound:diary"). The last source cannot go: a section must open somehow.
     */
    private void unlockSourcesField(List<Object> path, @Nullable JsonObject unlock, Component label) {
        List<String> written = stringsOf(unlock != null ? unlock.get("on") : null);
        List<String> sources = written.isEmpty() ? defaultSources(path.subList(0, Math.max(0, path.size() - 1))) : written;
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(label);
            int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
            // Two rows of two: seen, killed; studied, by an action.
            List<String> switches = List.of("seen", "killed", "observed", "manual");
            int each = (w - 2) / 2;
            for (int i = 0; i < switches.size(); i++) {
                String source = switches.get(i);
                String present = source.equals("observed") ? observedSource(sources) : sources.contains(source) ? source : null;
                boolean on = present != null;
                Button toggle = Button.builder(Component.literal((on ? "✓ " : "") + DevUi.str("unlock.switch." + source)), b -> {
                    List<String> next = new ArrayList<>(sources);
                    if (on) {
                        next.remove(present);
                    } else {
                        next.add(source.equals("observed") ? "observed:" + format(5) : source);
                    }
                    writeSources(path, next);
                }).bounds(x + (i % 2) * (each + 2), rowTop + (i / 2) * (CONTROL_H + 2), i % 2 == 1 ? w - each - 2 : each, CONTROL_H).build();
                toggle.setTooltip(Tooltip.create(DevUi.text("unlock.source_tooltip." + source)));
                screen.add(toggle);
            }
        }
        // The second row of switches.
        row(CONTROL_H);
        String study = observedSource(sources);
        if (study != null && row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("unlock.observed_time"));
            int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
            EditBox box = new EditBox(screen.font(), x, rowTop + 1, w, CONTROL_H - 2, DevUi.text("unlock.observed_time"));
            box.setMaxLength(6);
            box.setValue(study.contains(":") ? study.substring(study.indexOf(':') + 1) : format(5));
            box.setResponder(text -> {
                Double seconds = null;
                try {
                    double parsed = Double.parseDouble(text.trim().replace(',', '.'));
                    seconds = parsed >= 0.25 && parsed <= 600 ? parsed : null;
                } catch (NumberFormatException ignored) {
                    // Red until it is a number.
                }
                box.setTextColor(seconds != null ? 0xFFE0E0E0 : DevUi.ERROR);
                if (seconds != null) {
                    JsonArray on = new JsonArray();
                    for (String source : stringsOf(objectAt(path) != null ? objectAt(path).get("on") : null).isEmpty() ? sources
                            : stringsOf(objectAt(path).get("on"))) {
                        on.add(source.equals("observed") || source.startsWith("observed:") ? "observed:" + format(seconds) : source);
                    }
                    ensureObject(path).add("on", on);
                    host.edited();
                }
            });
            box.setTooltip(Tooltip.create(DevUi.text("unlock.source_tooltip.observed")));
            screen.add(box);
        }
        for (String source : sources) {
            if (!source.startsWith("read:") || !row(CONTROL_H)) {
                continue;
            }
            int rowTop = rowY(CONTROL_H);
            screen.add(Button.builder(Component.literal("📄 " + capitalized(sourceName(source))), b -> {
                ResourceLocation document = ResourceLocation.tryParse(source.substring("read:".length()));
                if (document != null) {
                    screen.status(DevUi.text("unlock.document_is", document.toString()), DevUi.LABEL);
                }
            }).bounds(x, rowTop, w - 20, CONTROL_H).build());
            screen.add(Button.builder(Component.literal("✕"), b -> {
                List<String> next = new ArrayList<>(sources);
                next.remove(source);
                writeSources(path, next);
            }).tooltip(Tooltip.create(DevUi.text("form.remove"))).bounds(x + w - 18, rowTop, 18, CONTROL_H).build());
        }
        if (!row(CONTROL_H)) {
            return;
        }
        int rowTop = rowY(CONTROL_H);
        screen.add(Button.builder(Component.literal("+ ").append(DevUi.text("unlock.add_document")), b -> {
            List<DevModeScreen.MenuItem> items = new ArrayList<>();
            ClientDevStore.byType(ClientDevStore.TYPE_DOCUMENT).keySet().stream().sorted(java.util.Comparator.comparing(ResourceLocation::toString))
                    .filter(document -> !sources.contains("read:" + document)).forEach(document -> items.add(DevModeScreen.MenuItem.of(
                            Component.literal(StoryView.title(new StoryLinks.NodeKey(ClientDevStore.TYPE_DOCUMENT, document)) + " (" + document + ")"), () -> {
                                List<String> next = new ArrayList<>(sources);
                                next.add("read:" + document);
                                writeSources(path, next);
                            })));
            if (items.isEmpty()) {
                screen.status(DevUi.text("unlock.no_documents"), DevUi.WARN);
                return;
            }
            screen.openMenu(x, rowTop + CONTROL_H, items);
        }).tooltip(Tooltip.create(DevUi.text("unlock.add_document_tooltip"))).bounds(x, rowTop, w, CONTROL_H).build());
    }

    /**
     * A list of ids (mobs, biomes, blocks, places; each an id or a #tag): a row each by its name, which picks another in
     * its place, ✕ to take it out, "+ Add" from the list (October 30, 2026; before, one line of ids typed with commas,
     * against the rule that lists are added to). One is written alone, several as a list, as the files have them.
     *
     * @param label above the rows; null where the page already says it (an entity's type under its switch)
     */
    private void idListRows(List<Object> path, String name, Picker picker, @Nullable Component label) {
        JsonObject owner = objectAt(path);
        List<String> ids = owner == null ? List.of() : stringsOf(owner.get(name) instanceof JsonPrimitive one ? asList(one) : owner.get(name));
        if (label != null && row(LABEL_H)) {
            labels.add(new Label(label, x, rowY(LABEL_H), DevUi.LABEL));
        }
        for (int i = 0; i < ids.size(); i++) {
            int index = i;
            if (!row(CONTROL_H)) {
                continue;
            }
            int rowTop = rowY(CONTROL_H);
            screen.add(Button.builder(Component.literal(idName(ids.get(i), picker)), b -> pick(picker, entry -> {
                List<String> next = new ArrayList<>(ids);
                next.set(index, entry.id());
                writeIds(path, name, next);
            })).tooltip(Tooltip.create(Component.literal(ids.get(i)))).bounds(x, rowTop, w - 20, CONTROL_H).build());
            screen.add(Button.builder(Component.literal("✕"), b -> {
                List<String> next = new ArrayList<>(ids);
                next.remove(index);
                writeIds(path, name, next);
            }).tooltip(Tooltip.create(DevUi.text("form.remove"))).bounds(x + w - 18, rowTop, 18, CONTROL_H).build());
        }
        if (row(CONTROL_H)) {
            screen.add(Button.builder(Component.literal("+ ").append(DevUi.text("form.add")), b -> pick(picker, entry -> {
                if (!ids.contains(entry.id())) {
                    List<String> next = new ArrayList<>(ids);
                    next.add(entry.id());
                    writeIds(path, name, next);
                }
            })).tooltip(Tooltip.create(DevUi.text("form.add_from_list"))).bounds(x, rowY(CONTROL_H), w, CONTROL_H).build());
        }
    }

    private static JsonArray asList(JsonPrimitive one) {
        JsonArray list = new JsonArray();
        list.add(one);
        return list;
    }

    private void writeIds(List<Object> path, String name, List<String> ids) {
        JsonObject owner = ensureObject(path);
        if (ids.isEmpty()) {
            owner.remove(name);
        } else if (ids.size() == 1) {
            owner.addProperty(name, ids.getFirst());
        } else {
            JsonArray list = new JsonArray();
            ids.forEach(list::add);
            owner.add(name, list);
        }
        host.changed();
    }

    /** An id by its name where the game has one: "Zombie", "Plains", "Lectern"; a #tag as written. */
    private static String idName(String id, Picker picker) {
        for (Entry entry : picker.entries()) {
            if (entry.id().equals(id)) {
                return entry.label();
            }
        }
        ResourceLocation key = id.startsWith("#") ? null : ResourceLocation.tryParse(id);
        if (key == null) {
            return id;
        }
        if (picker.kind() == Catalog.Kind.MOB && BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
            return BuiltInRegistries.ENTITY_TYPE.get(key).getDescription().getString();
        }
        if (picker.kind() == Catalog.Kind.ITEM && BuiltInRegistries.ITEM.containsKey(key)) {
            return BuiltInRegistries.ITEM.get(key).getDescription().getString();
        }
        if (picker.kind() == Catalog.Kind.BIOME) {
            String translation = "biome." + key.getNamespace() + "." + key.getPath();
            return Language.getInstance().has(translation) ? Language.getInstance().getOrDefault(translation) : id;
        }
        return id;
    }

    /** The "studied" source as written ({@code observed:5}), or null. */
    private static @Nullable String observedSource(List<String> sources) {
        return sources.stream().filter(source -> source.equals("observed") || source.startsWith("observed:")).findFirst().orElse(null);
    }

    private void writeSources(List<Object> path, List<String> sources) {
        if (sources.isEmpty()) {
            screen.status(DevUi.text("unlock.need_one"), DevUi.WARN);
            return;
        }
        JsonArray on = new JsonArray();
        sources.forEach(on::add);
        ensureObject(path).add("on", on);
        host.changed();
    }

    /**
     * A share written as a fraction, in percent: "5" for 0.05 (October 30, 2026; before, the fraction). Empty leaves it
     * out (the default); a number off 0–100 turns red and is not written.
     */
    private void percentField(List<Object> path, String name, Node node, @Nullable JsonObject object, Component label) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        EditBox box = new EditBox(screen.font(), x, rowTop + 1, w, CONTROL_H - 2, label);
        box.setMaxLength(8);
        box.setValue(object != null && object.get(name) instanceof JsonPrimitive share && share.isNumber()
                ? format(Math.round(share.getAsDouble() * 10000) / 100.0) : "");
        JsonElement fallback = Schemas.resolve(node).defaultValue();
        if (fallback != null && fallback.isJsonPrimitive()) {
            box.setHint(DevUi.text("form.percent_default", format(Math.round(fallback.getAsDouble() * 10000) / 100.0))
                    .copy().withStyle(style -> style.withColor(0xFF6B7380)));
        }
        box.setResponder(text -> {
            JsonObject owner = ensureObject(path);
            if (text.isBlank()) {
                owner.remove(name);
                box.setTextColor(0xFFE0E0E0);
                host.edited();
                return;
            }
            Double percent = null;
            try {
                double parsed = Double.parseDouble(text.trim().replace(',', '.'));
                percent = parsed >= 0 && parsed <= 100 ? parsed : null;
            } catch (NumberFormatException ignored) {
                // Stays red until it is a number.
            }
            box.setTextColor(percent != null ? 0xFFE0E0E0 : DevUi.ERROR);
            if (percent != null) {
                owner.addProperty(name, percent / 100);
                host.edited();
            }
        });
        String description = !node.description().isEmpty() ? node.description() : Schemas.resolve(node).description();
        if (!description.isEmpty()) {
            box.setTooltip(Tooltip.create(Component.literal(described(description))));
        }
        screen.add(box);
    }

    /**
     * A Codex entry's group in the Codex tab: a button with it by name and a dropdown of the built-in groups and those
     * other entries have (October 30, 2026; before, an id typed, labelled "Section").
     */
    private void codexCategoryField(List<Object> path, @Nullable JsonObject entry) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("field.codex.category"));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        String current = entry != null && entry.get("category") instanceof JsonPrimitive written ? shortType(written.getAsString()) : "monsters";
        // The Codex is the bestiary: groups of creatures (October 30, 2026; before, also places and legends, which nothing
        // in it records). Those stay offered where an entry already has them.
        java.util.Set<String> categories = new java.util.LinkedHashSet<>(List.of("monsters", "animals", "characters", "companions"));
        ClientDevStore.byType(ClientDevStore.TYPE_CODEX).values().forEach(raw -> {
            if (ClientDevStore.parseObject(raw).get("category") instanceof JsonPrimitive category) {
                categories.add(shortType(category.getAsString()));
            }
        });
        categories.add(current);
        int buttonX = x;
        Button button = Button.builder(Component.literal(codexCategoryName(current) + " ▾"), b -> {
            List<DevModeScreen.MenuItem> items = new ArrayList<>();
            for (String category : categories) {
                items.add(DevModeScreen.MenuItem.of(Component.literal((category.equals(current) ? "✓ " : "   ") + codexCategoryName(category)), () -> {
                    JsonObject owner = ensureObject(path);
                    if (category.equals("monsters")) {
                        owner.remove("category");
                    } else {
                        owner.addProperty("category", category);
                    }
                    host.changed();
                }));
            }
            screen.openMenu(buttonX, rowTop + CONTROL_H, items);
        }).bounds(x, rowTop, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("codex.category_tooltip")));
        screen.add(button);
    }

    /** "Monsters"; another group by its translation ({@code <ns>.codex.category.<path>}), or as it is. */
    private static String codexCategoryName(String category) {
        ResourceLocation id = ResourceLocation.tryParse(category.contains(":") ? category : "storybound:" + category);
        String key = id == null ? "" : id.getNamespace() + ".codex.category." + id.getPath();
        return Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) : category;
    }

    /**
     * The quest's type, its group in the Quests tab: a button with it by name and a dropdown of the types (main, side,
     * contract, and those other quests have), October 30, 2026 (before, an id typed, labelled "Section").
     */
    private void questTypeField(List<Object> path, @Nullable JsonObject quest) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("quest.type"));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        String current = quest != null && quest.get("category") instanceof JsonPrimitive written ? written.getAsString() : "side";
        java.util.Set<String> types = new java.util.LinkedHashSet<>(List.of("main", "side", "contract"));
        ClientDevStore.byType(ClientDevStore.TYPE_QUEST).values().forEach(raw -> {
            if (ClientDevStore.parseObject(raw).get("category") instanceof JsonPrimitive category) {
                types.add(shortType(category.getAsString()));
            }
        });
        int buttonX = x;
        Button button = Button.builder(Component.literal(questTypeName(current) + " ▾"), b -> {
            List<DevModeScreen.MenuItem> items = new ArrayList<>();
            for (String type : types) {
                items.add(DevModeScreen.MenuItem.of(Component.literal((type.equals(shortType(current)) ? "✓ " : "   ") + questTypeName(type)), () -> {
                    JsonObject owner = ensureObject(path);
                    if (type.equals("side")) {
                        owner.remove("category");
                    } else {
                        owner.addProperty("category", type);
                    }
                    host.changed();
                }));
            }
            screen.openMenu(buttonX, rowTop + CONTROL_H, items);
        }).bounds(x, rowTop, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("quest.type_tooltip")));
        screen.add(button);
    }

    /** {@code storybound:main} is written {@code main}, as the built-in types are; another namespace's stays whole. */
    private static String shortType(String type) {
        return type.startsWith("storybound:") ? type.substring("storybound:".length()) : type;
    }

    /** "Main quests"; another type by its translation, or as it is. */
    private static String questTypeName(String type) {
        ResourceLocation id = ResourceLocation.tryParse(type.contains(":") ? type : "storybound:" + type);
        String key = id == null ? "" : id.getNamespace() + ".quest.category." + id.getPath();
        return Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) : type;
    }

    /**
     * Whether the quest comes again: no, or yes with how in words ("at once, without a limit", "after 600 s, at most
     * 3 times"), which opens its settings. Before, a switch between "true" and an object.
     */
    private void repeatField(List<Object> path, Node node, @Nullable JsonObject quest) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(fieldLabel("repeatable", false));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        JsonElement repeat = quest != null ? quest.get("repeatable") : null;
        boolean on = repeat != null && !(repeat instanceof JsonPrimitive flag && !flag.getAsBoolean());
        int toggleW = on ? Math.min(60, w / 3) : w;
        Button toggle = Button.builder(Component.literal(DevUi.yesNo(on)), b -> {
            if (on) {
                ensureObject(path).remove("repeatable");
            } else {
                ensureObject(path).addProperty("repeatable", true);
            }
            host.changed();
        }).bounds(x, rowTop, toggleW, CONTROL_H).build();
        if (!node.description().isEmpty()) {
            toggle.setTooltip(Tooltip.create(Component.literal(described(node.description()))));
        }
        screen.add(toggle);
        if (on) {
            JsonObject settings = repeat instanceof JsonObject object ? object : new JsonObject();
            String cooldown = settings.get("cooldown") instanceof JsonPrimitive seconds && seconds.isNumber() && seconds.getAsDouble() > 0
                    ? DevUi.str("quest.repeat_after", format(seconds.getAsDouble())) : DevUi.str("quest.repeat_at_once");
            String times = settings.get("times") instanceof JsonPrimitive count && count.isNumber()
                    ? DevUi.str("quest.repeat_times", count.getAsInt()) : DevUi.str("quest.repeat_unlimited");
            Component summary = Component.literal(cooldown + ", " + times + " ▸");
            screen.add(Button.builder(summary, b -> {
                // "true" becomes the object its settings are written in.
                if (!(ensureObject(path).get("repeatable") instanceof JsonObject)) {
                    ensureObject(path).add("repeatable", new JsonObject());
                    host.edited();
                }
                Node object = Schemas.variants(node).stream().filter(variant -> !Schemas.properties(variant).isEmpty()).findFirst().orElse(node);
                push(with(path, "repeatable"), object, fieldLabel("repeatable", false).getString(), false);
            }).bounds(x + toggleW + 2, rowTop, w - toggleW - 2, CONTROL_H).build());
        }
    }

    /** The properties with {@code first} in front, in that order; the others after, as the schema has them. */
    private static List<Map.Entry<String, Node>> ordered(Map<String, Node> properties, List<String> first) {
        List<Map.Entry<String, Node>> entries = new ArrayList<>();
        for (String name : first) {
            if (properties.containsKey(name)) {
                entries.add(Map.entry(name, properties.get(name)));
            }
        }
        properties.entrySet().stream().filter(entry -> !first.contains(entry.getKey())).forEach(entries::add);
        return entries;
    }

    /**
     * A choice's timer: yes/no, and while yes what happens in words ("after 10 s → bye"), which opens it. Before, a row
     * "1 ▸" or "—".
     */
    private void timeoutField(List<Object> path, Node node, @Nullable JsonObject choice) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("node.timeout"));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        JsonObject timeout = choice != null && choice.get("timeout") instanceof JsonObject value ? value : null;
        boolean on = timeout != null;
        int toggleW = on ? Math.min(60, w / 3) : w;
        Button toggle = Button.builder(Component.literal(DevUi.yesNo(on)), b -> {
            if (on) {
                ensureObject(path).remove("timeout");
            } else {
                JsonObject created = new JsonObject();
                created.addProperty("seconds", 10);
                created.addProperty("next", DialogueGraph.END);
                ensureObject(path).add("timeout", created);
            }
            host.changed();
        }).bounds(x, rowTop, toggleW, CONTROL_H).build();
        toggle.setTooltip(Tooltip.create(DevUi.text("node.timeout.tooltip")));
        screen.add(toggle);
        if (on) {
            String seconds = timeout.get("seconds") instanceof JsonPrimitive value && value.isNumber() ? format(value.getAsDouble()) : "?";
            String next = timeout.get("next") instanceof JsonPrimitive value ? choiceLabel(value.getAsString()) : "?";
            Component summary = Component.empty().append(DevUi.text("node.timeout.summary", seconds, next)).append(" ▸");
            screen.add(Button.builder(summary, b -> push(with(path, "timeout"), node, DevUi.str("node.timeout"), false))
                    .bounds(x + toggleW + 2, rowTop, w - toggleW - 2, CONTROL_H).build());
        }
    }

    /**
     * Where an arrow of a dialogue node leads ({@code next}, {@code then}, {@code else}, an answer's, a branch's, the
     * timer's): not typed but shown, as the graph's arrows are the way to draw them (October 29, 2026; before, a box
     * for the node's id, the same as dragging an arrow). A click shows the node on the graph; ▾ picks another (the
     * nodes by their name and first words, the end of the dialogue, leaving the whole conversation); ✕ unlinks it, so
     * it ends the dialogue.
     */
    private void targetField(List<Object> path, String name, @Nullable JsonElement value, Component label, boolean required) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        String target = value instanceof JsonPrimitive written && !written.getAsString().isBlank() ? written.getAsString() : DialogueGraph.END;
        JsonObject dialogue = host.dialogue();
        boolean node = dialogue != null && DialogueGraph.node(dialogue, target) != null;
        Button shown = Button.builder(Component.literal("→ " + targetLabel(dialogue, target)), b -> {
            if (node) {
                host.revealNode(target);
            }
        }).bounds(x, rowTop, w - 40, CONTROL_H).build();
        shown.setTooltip(Tooltip.create(DevUi.text(node ? "target.reveal" : "target.none")));
        screen.add(shown);
        screen.add(Button.builder(Component.literal("▾"), b -> {
            List<Entry> entries = new ArrayList<>();
            if (dialogue != null) {
                DialogueGraph.nodes(dialogue).keySet().forEach(id -> entries.add(new Entry(id, targetLabel(dialogue, id), new ItemStack(Items.PAPER))));
            }
            entries.add(new Entry(DialogueGraph.END, choiceLabel(DialogueGraph.END), new ItemStack(Items.BARRIER)));
            entries.add(new Entry(DialogueGraph.EXIT, choiceLabel(DialogueGraph.EXIT), new ItemStack(Items.OAK_DOOR)));
            screen.catalog().openList(fieldLabel(name, false), entries, (kind, entry) -> {
                ensureObject(path).addProperty(name, entry.id());
                host.changed();
            });
        }).tooltip(Tooltip.create(DevUi.text("target.pick"))).bounds(x + w - 38, rowTop, 18, CONTROL_H).build());
        Button unlink = Button.builder(Component.literal("✕"), b -> {
            // A required arrow (a condition's yes and no, the timer's) ends the dialogue explicitly; another one is left out.
            if (required) {
                ensureObject(path).addProperty(name, DialogueGraph.END);
            } else {
                ensureObject(path).remove(name);
            }
            host.changed();
        }).tooltip(Tooltip.create(DevUi.text("target.unlink"))).bounds(x + w - 18, rowTop, 18, CONTROL_H).build();
        unlink.active = !target.equals(DialogueGraph.END);
        screen.add(unlink);
    }

    /** "few_hands · «Few hands, many…»"; the end and the exit in words; a node that is not there says so. */
    private static String targetLabel(@Nullable JsonObject dialogue, String target) {
        if (target.startsWith("@")) {
            return choiceLabel(target);
        }
        JsonObject found = dialogue != null ? DialogueGraph.node(dialogue, target) : null;
        if (found == null) {
            return DevUi.str("target.missing", target);
        }
        String text = ClientDevStore.readStoryText(found, "text", "", ClientDevStore.activeLanguage()).strip();
        if (text.isEmpty() && found.get("options") instanceof JsonArray options && !options.isEmpty() && options.get(0) instanceof JsonObject first) {
            text = ClientDevStore.readStoryText(first, "text", "", ClientDevStore.activeLanguage()).strip();
        }
        return text.isEmpty() ? target : target + " · «" + (text.length() > 28 ? text.substring(0, 27) + "…" : text) + "»";
    }

    /**
     * A sound (a line's, play_sound's...): its id, "…" to pick it from the story's own sounds and every sound the game
     * knows, ▶ to hear it here. One the game does not know is red, and says why: a file of the story's that is not Ogg
     * Vorbis or is wrongly named, or none at all (October 30, 2026; before, an id typed blind).
     */
    private void soundField(List<Object> path, String name, @Nullable JsonElement value, Component label) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        String current = value instanceof JsonPrimitive written ? written.getAsString() : "";
        EditBox box = new EditBox(screen.font(), x, rowTop + 1, w - 40, CONTROL_H - 2, label);
        box.setMaxLength(256);
        box.setValue(current);
        Runnable check = () -> {
            String text = box.getValue().trim();
            ResourceLocation id = ResourceLocation.tryParse(text);
            boolean known = text.isEmpty() || id != null && Minecraft.getInstance().getSoundManager().getAvailableSounds().contains(id);
            box.setTextColor(known ? 0xFFE0E0E0 : DevUi.ERROR);
            dev.dzenthai.storybound.client.sound.StorySoundFiles.Problem problem = id != null
                    ? dev.dzenthai.storybound.client.sound.StoryAssets.last().problemOf(id.toString()) : null;
            Component why = problem != null ? DevUi.text("sound.problem." + problem.kind())
                    : known ? DevUi.text("sound.tooltip", dev.dzenthai.storybound.client.sound.StoryAssets.folder().toString())
                    : DevUi.text("sound.missing");
            box.setTooltip(Tooltip.create(why));
        };
        box.setResponder(text -> {
            JsonObject owner = ensureObject(path);
            if (text.isBlank()) {
                owner.remove(name);
            } else {
                owner.addProperty(name, text.trim());
            }
            check.run();
            host.edited();
        });
        check.run();
        screen.add(box);
        screen.add(Button.builder(Component.literal("…"), b -> screen.catalog().open(DevUi.text("sound.pick"), Catalog.Kind.SOUND, (kind, entry) -> {
            if (entry.id().startsWith(Catalog.PROBLEM)) {
                screen.status(DevUi.text("sound.problem." + entry.id().substring(Catalog.PROBLEM.length())), DevUi.BAD);
                return;
            }
            ensureObject(path).addProperty(name, entry.id());
            host.changed();
        })).tooltip(Tooltip.create(DevUi.text("sound.pick"))).bounds(x + w - 38, rowTop, 18, CONTROL_H).build());
        screen.add(Button.builder(Component.literal("▶"), b -> {
            ResourceLocation id = ResourceLocation.tryParse(box.getValue().trim());
            if (id != null) {
                Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvent.createVariableRangeEvent(id), 1f));
            }
        }).tooltip(Tooltip.create(DevUi.text("sound.play"))).bounds(x + w - 18, rowTop, 18, CONTROL_H).build());
    }

    /**
     * A document's icon in the inventory, its {@code custom_model_data}: the story's icons
     * ({@code config/storybound/icons}) by name, picked from a list that shows them; empty, the kind's own icon (October
     * 30, 2026; before, a number to type, to pair with a resource pack made by hand). A number no icon of the story
     * has stays, said to come from a resource pack.
     */
    private void iconField(List<Object> path, String name, @Nullable JsonObject document) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("document.icon"));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        Integer number = document != null && document.get(name) instanceof JsonPrimitive value && value.isNumber() ? value.getAsInt() : null;
        dev.dzenthai.storybound.client.sound.StoryIconFiles.Icon icon = number != null ? dev.dzenthai.storybound.client.sound.StoryAssets.icons().byNumber(number) : null;
        Component shown = number == null ? DevUi.text("document.icon_default") : icon != null ? Component.literal(icon.id())
                : DevUi.text("document.icon_number", number);
        Button button = Button.builder(Component.empty().append(shown).append(" ▸"), b -> screen.catalog().open(DevUi.text("document.pick_icon"), Catalog.Kind.ICON, (kind, entry) -> {
            if (entry.id().startsWith(Catalog.PROBLEM)) {
                screen.status(DevUi.text("icon.problem." + entry.id().substring(Catalog.PROBLEM.length())), DevUi.BAD);
                return;
            }
            dev.dzenthai.storybound.client.sound.StoryIconFiles.Icon picked = dev.dzenthai.storybound.client.sound.StoryAssets.icons().icons().stream()
                    .filter(candidate -> candidate.id().equals(entry.id())).findFirst().orElse(null);
            if (picked != null) {
                ensureObject(path).addProperty(name, picked.number());
                host.changed();
            }
        })).tooltip(Tooltip.create(DevUi.text("document.icon_tooltip", dev.dzenthai.storybound.client.sound.StoryAssets.iconFolder().toString())))
                .bounds(x, rowTop, w - 20, CONTROL_H).build();
        screen.add(button);
        Button clear = Button.builder(Component.literal("✕"), b -> {
            ensureObject(path).remove(name);
            host.changed();
        }).tooltip(Tooltip.create(DevUi.text("document.icon_clear"))).bounds(x + w - 18, rowTop, 18, CONTROL_H).build();
        clear.active = number != null;
        screen.add(clear);
    }

    /**
     * What a document's empty style, journal section or text is, by its kind, for the box to say (October 30, 2026;
     * before, an empty box): a letter is on parchment and goes to Letters; the text is the document's own.
     */
    private @Nullable Component documentDefault(String name, List<Object> path) {
        JsonObject document = objectAt(List.of());
        if (document == null || !document.has("journal") && !document.has("kind") || host.dialogue() != null) {
            return null;
        }
        String kind = document.get("kind") instanceof JsonPrimitive value ? value.getAsString() : "";
        boolean top = path.isEmpty();
        boolean inJournal = path.size() == 1 && "journal".equals(path.getFirst());
        if (top && name.equals("style")) {
            String style = switch (kind) {
                case "book" -> "book_leather";
                case "letter" -> "parchment";
                case "scroll", "note", "contract" -> kind;
                default -> null;
            };
            return style == null ? null : Component.literal(choiceLabel("storybound:" + style));
        }
        if (inJournal && name.equals("category")) {
            String section = switch (kind) {
                case "book", "scroll", "note", "letter", "contract" -> kind + "s";
                default -> null;
            };
            return section == null ? null : Component.literal(choiceLabel("storybound:" + section));
        }
        if (top && name.equals("content")) {
            return DevUi.text("document.own_text");
        }
        return null;
    }

    /** An empty "next" of a quest's stage: the following stage, or the quest completed after the last. */
    private @Nullable Component stageDefault(String name, List<Object> path) {
        return name.equals("next") && "stage".equals(insideQuest(path)) && host.quest() != null ? DevUi.text("quest.next_default") : null;
    }

    /** A choice from a list in words where it has some: "@end" is "the end of the dialogue"; a style or a section by its name. */
    private static String choiceLabel(String choice) {
        String key = "storybound.dev.value." + choice;
        if (Language.getInstance().has(key)) {
            return Language.getInstance().getOrDefault(key) + (choice.startsWith("@") ? " (" + choice + ")" : "");
        }
        ResourceLocation id = choice.contains(":") ? ResourceLocation.tryParse(choice) : null;
        String section = id != null ? id.getNamespace() + ".journal.category." + id.getPath() : "";
        String questSection = id != null ? id.getNamespace() + ".quest.category." + id.getPath() : "";
        if (id != null && Language.getInstance().has(section)) {
            return Language.getInstance().getOrDefault(section) + " (" + choice + ")";
        }
        return id != null && Language.getInstance().has(questSection) ? Language.getInstance().getOrDefault(questSection) + " (" + choice + ")" : choice;
    }

    /** A heading over a group of fields. */
    private void group(Component title) {
        y += 4;
        text(title, DevUi.ACCENT);
    }

    /**
     * Speaking up when a player comes near: yes/no, and while yes its settings in words ("4 blocks · every 30 s"), which
     * open them. Before, one row "1 ▸" or "—", and "—" for {@code {}} too, which is on with the defaults.
     */
    private void proximityField(List<Object> path, Node node, @Nullable JsonObject binding) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(fieldLabel("proximity", false));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        JsonObject settings = binding != null && binding.get("proximity") instanceof JsonObject value ? value : null;
        boolean on = settings != null;
        int toggleW = on ? Math.min(60, w / 3) : w;
        Button toggle = Button.builder(Component.literal(DevUi.yesNo(on)), b -> {
            if (on) {
                ensureObject(path).remove("proximity");
            } else {
                ensureObject(path).add("proximity", new JsonObject());
            }
            host.changed();
        }).bounds(x, rowTop, toggleW, CONTROL_H).build();
        if (!node.description().isEmpty()) {
            toggle.setTooltip(Tooltip.create(Component.literal(described(node.description()))));
        }
        screen.add(toggle);
        if (on) {
            NpcProximity shown = NpcProximity.of(settings);
            Component summary = Component.empty()
                    .append(DevUi.text(shown.once() ? "npc.proximity.once" : "npc.proximity.summary", format(shown.radius()), shown.cooldown()))
                    .append(" ▸");
            screen.add(Button.builder(summary, b -> push(with(path, "proximity"), node, fieldLabel("proximity", false).getString(), false))
                    .bounds(x + toggleW + 2, rowTop, w - toggleW - 2, CONTROL_H).build());
        }
    }

    /** The settings a proximity object gives, with the defaults for what it leaves out (as {@code NpcBinding.Proximity}). */
    private record NpcProximity(double radius, int cooldown, boolean once) {
        static NpcProximity of(JsonObject settings) {
            return new NpcProximity(
                    settings.get("radius") instanceof JsonPrimitive radius && radius.isNumber() ? radius.getAsDouble() : 4,
                    settings.get("cooldown") instanceof JsonPrimitive cooldown && cooldown.isNumber() ? cooldown.getAsInt() : 30,
                    flag(settings, "once", false));
        }
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static boolean isEntitySelector(Node node) {
        return "entity_selector".equals(Schemas.resolve(node).ref());
    }

    /**
     * A field written as entity types (an id, a #tag or a list) or as a selector (a waypoint's nearest entity): edited
     * as a selector, whose type is the same list (October 30, 2026; before, the types' variant had nothing to edit).
     */
    private static boolean isTypesOrSelector(Node node) {
        List<Node> variants = Schemas.variants(node);
        return variants.size() == 2 && variants.stream().anyMatch(FormView::isEntitySelector)
                && variants.stream().anyMatch(variant -> "id_list".equals(Schemas.resolve(variant).ref()));
    }

    /** The selector of a field written as one entity selector or a list of them (an NPC binding's); null for others. */
    private static @Nullable Node entityListItems(Node node) {
        List<Node> variants = Schemas.variants(node);
        if (variants.size() != 2 || !isEntitySelector(variants.get(0))) {
            return null;
        }
        Node items = Schemas.items(variants.get(1));
        return items != null && isEntitySelector(items) ? variants.get(0) : null;
    }

    /**
     * Several entities (an NPC binding is for any of them): one row naming them, which opens their list, as a dialogue's
     * nodes. There "+ Add" makes an empty one and opens its page ({@link #entityPage}), as a row opens a set one.
     */
    private void entityListField(List<Object> path, String name, Node node, @Nullable JsonElement value, boolean required) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text(required ? "entity.list_required" : "entity.list"));
        List<JsonElement> entities = value == null ? List.of() : value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
        List<String> names = new ArrayList<>();
        for (JsonElement entity : entities) {
            names.add(entitySummary(entity.isJsonObject() ? entity.getAsJsonObject() : null));
        }
        Component summary = Component.literal((names.isEmpty() ? "—" : String.join("; ", names)) + " ▸");
        screen.add(Button.builder(summary, b -> push(with(path, name), node, DevUi.str("entity.list"), false))
                .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
    }

    /**
     * Which entity (an NPC binding's, a boss's, a waypoint's nearest one): one row with what it is in words ("Villager ·
     * tag demo_elder"). A click opens its page ({@link #entityPage}), also when it is empty: how it is known is chosen
     * there.
     */
    private void entityField(List<Object> path, String name, Node node, @Nullable JsonElement value, Component label, boolean required) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        JsonObject selector = value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
        Component summary = Component.literal(entitySummary(selector)).append(" ▸");
        List<Object> at = with(path, name);
        String title = fieldLabel(name, false).getString();
        // Empty or set, straight to its page: how it is known is chosen there.
        screen.add(Button.builder(summary, b -> push(at, node, title, !required))
                .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
    }

    /** "Villager · tag demo_elder", "Cat · name «Murka»", "entity …a1b2"; "—" when nothing is set. */
    private static String entitySummary(@Nullable JsonObject selector) {
        if (selector == null || selector.isEmpty()) {
            return "—";
        }
        List<String> parts = new ArrayList<>();
        if (selector.has("uuid")) {
            String uuid = selector.get("uuid").getAsString();
            parts.add(DevUi.str("entity.summary.uuid", uuid.length() > 4 ? uuid.substring(uuid.length() - 4) : uuid));
        } else if (selector.has("name")) {
            parts.add(DevUi.str("entity.summary.name", selector.get("name").getAsString()));
        } else if (selector.has("type")) {
            List<String> names = new ArrayList<>();
            JsonElement type = selector.get("type");
            for (JsonElement one : type.isJsonArray() ? type.getAsJsonArray().asList() : List.of(type)) {
                String id = one.getAsString();
                ResourceLocation key = id.startsWith("#") ? null : ResourceLocation.tryParse(id);
                names.add(key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key) ? BuiltInRegistries.ENTITY_TYPE.get(key).getDescription().getString() : id);
            }
            parts.add(String.join(", ", names));
        }
        if (selector.has("tag")) {
            parts.add(DevUi.str("entity.summary.tag", selector.get("tag").getAsString()));
        }
        return String.join(" · ", parts);
    }

    /**
     * An entity's page, in two parts: what it is known by, one of three, by a switch (its type, with the list of types;
     * only one with this name from a name tag; one entity, picked by looking at it), and the scoreboard tag it carries.
     * Switching clears what the previous way wrote, but the type a picked entity brought along stays (it reads well).
     * A file that gives several ways at once shows the most exact one (the entity, then the name, then the type).
     */
    private void entityPage(List<Object> path) {
        JsonObject selector = objectAt(path);
        // What the file says first; when it says nothing yet, the way last switched to here.
        String written = selector == null ? null : selector.has("uuid") ? "uuid" : selector.has("name") ? "name" : selector.has("type") ? "type" : null;
        String mode = written != null ? written : entityModes.getOrDefault(path.toString(), "type");
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("entity.by"));
            int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
            int modeW = Math.min(150, w * 2 / 5);
            Button switcher = Button.builder(Component.literal(DevUi.str("entity.mode." + mode) + " ▸"), b -> {
                JsonObject target = ensureObject(path);
                String next = switch (mode) {
                    case "type" -> {
                        target.remove("type");
                        yield "name";
                    }
                    case "name" -> {
                        target.remove("name");
                        yield "uuid";
                    }
                    default -> {
                        target.remove("uuid");
                        yield "type";
                    }
                };
                // An empty way still shows as chosen: the switch writes nothing until the field is filled.
                entityModes.put(path.toString(), next);
                host.changed();
            }).bounds(x, rowTop, modeW, CONTROL_H).build();
            switcher.setTooltip(Tooltip.create(DevUi.text("entity.mode.tooltip")));
            String field = mode;
            if (field.equals("type")) {
                // The type is a list: a row each under the switch, added from the mobs, not typed with commas.
                switcher.setWidth(w);
            } else {
                int boxX = x + modeW + 4;
                int boxW = w - modeW - 4 - (field.equals("uuid") ? 20 : 0);
                EditBox box = new EditBox(screen.font(), boxX, rowTop + 1, boxW, CONTROL_H - 2, DevUi.text("entity.mode." + field));
                box.setMaxLength(256);
                box.setValue(selector != null && selector.has(field) ? display(Kind.STRING, selector.get(field)) : "");
                box.setResponder(text -> {
                    JsonObject target = ensureObject(path);
                    if (text.isBlank()) {
                        target.remove(field);
                        box.setTextColor(0xFFE0E0E0);
                        host.edited();
                        return;
                    }
                    JsonElement parsed = field.equals("uuid") && !text.trim().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ? null
                            : new JsonPrimitive(text.trim());
                    box.setTextColor(parsed != null ? 0xFFE0E0E0 : DevUi.ERROR);
                    if (parsed != null) {
                        target.add(field, parsed);
                        host.edited();
                    }
                });
                box.setTooltip(Tooltip.create(DevUi.text("entity.field." + field)));
                screen.add(box);
            }
            screen.add(switcher);
            if (field.equals("uuid")) {
                screen.add(Button.builder(Component.literal("🎯"), b -> DevWorldPicker.pickEntity(screen, DevUi.text("picker.entity_prompt"), entity -> {
                    JsonObject target = ensureObject(path);
                    target.addProperty("uuid", entity.getUUID().toString());
                    // The type goes along, so the file reads well and survives a typo in the uuid.
                    target.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                    host.changed();
                })).tooltip(Tooltip.create(DevUi.text("form.pick_entity"))).bounds(x + w - 18, rowTop - 1, 18, CONTROL_H).build());
            }
        }
        if (mode.equals("type")) {
            idListRows(path, "type", new Picker(Catalog.Kind.MOB, null, List.of(), DevUi.text("entity.pick")), null);
        }
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("entity.tag"));
            EditBox tag = new EditBox(screen.font(), x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H - 2, DevUi.text("entity.tag"));
            tag.setMaxLength(256);
            tag.setValue(selector != null && selector.has("tag") ? selector.get("tag").getAsString() : "");
            tag.setResponder(text -> {
                JsonObject target = ensureObject(path);
                if (text.isBlank()) {
                    target.remove("tag");
                } else {
                    target.addProperty("tag", text.trim());
                }
                host.edited();
            });
            tag.setTooltip(Tooltip.create(DevUi.text("entity.field.tag")));
            screen.add(tag);
        }
    }

    /** The way an entity is known by, chosen on its page before its field is filled (per path; the form's own state). */
    private final Map<String, String> entityModes = new java.util.HashMap<>();

    /** "stage" for a quest stage's page ({@code stages/<i>}), "objective" for an objective's, null elsewhere. */
    private static @Nullable String insideQuest(List<Object> path) {
        boolean stage = path.size() >= 2 && "stages".equals(path.get(0)) && path.get(1) instanceof Integer;
        if (stage && path.size() == 2) {
            return "stage";
        }
        return stage && path.size() == 4 && "objectives".equals(path.get(2)) && path.get(3) instanceof Integer ? "objective" : null;
    }

    /** The names being typed for stages and objectives, by path, so rebuilding the form loses nothing. */
    private final Map<String, String> insideNames = new java.util.HashMap<>();

    /**
     * A stage's or an objective's name, like a node's own: other stages' "next", conditions, waypoints and players' place
     * and counters name it by it, so it is not typed over but renamed with ✓, with all of them (see
     * {@link StoryRenames}). An objective without a name of its own is counted by its
     * position, which the box shows greyed.
     */
    private void insideNameField(List<Object> path, String kind) {
        boolean objective = kind.equals("objective");
        JsonObject quest = host.root();
        JsonObject stageObject = objectAt(path.subList(0, 2));
        String stage = stageObject != null && stageObject.get("id") instanceof JsonPrimitive id ? id.getAsString() : "";
        JsonObject own = objectAt(path);
        boolean named = own != null && own.get("id") instanceof JsonPrimitive;
        String current = objective ? (named ? own.get("id").getAsString() : String.valueOf(path.get(3))) : stage;
        nameRow(path.toString(), DevUi.text(objective ? "rename.objective" : "rename.stage"),
                DevUi.text(objective ? "rename.objective_tooltip" : "rename.stage_tooltip", current),
                DevUi.text(objective ? "rename.objective_apply" : "rename.stage_apply"), current,
                objective && !named ? DevUi.text("rename.by_position", current) : null,
                typed -> problem(quest, objective, stage, current, typed), to -> host.renameInQuest(stage, objective ? current : "", to));
    }

    /**
     * A name inside a definition (a quest's stage or objective, a Codex section) with ✓ that renames it with what names
     * it: checked as it is typed, the reason under the box. {@code unnamed}: the hint of a box left empty (an objective
     * named by its position), which then starts empty.
     */
    private void nameRow(String key, Component label, Component tooltip, Component applyTooltip, String current, @Nullable Component unnamed,
                         java.util.function.Function<String, Optional<StoryRenames.Problem>> problemOf, Consumer<String> rename) {
        if (!row(LABEL_H + CONTROL_H + LABEL_H)) {
            return;
        }
        // The label at the top of this taller row (a reason may show under the box), not where a usual row has it.
        labels.add(new Label(label, x, rowY(LABEL_H + CONTROL_H + LABEL_H), DevUi.LABEL));
        int rowTop = rowY(LABEL_H + CONTROL_H + LABEL_H) + LABEL_H;
        EditBox box = new EditBox(screen.font(), x, rowTop + 1, w - 22, CONTROL_H - 2, label);
        box.setMaxLength(64);
        box.setValue(insideNames.getOrDefault(key, unnamed != null ? "" : current));
        if (unnamed != null) {
            box.setHint(unnamed);
        }
        // Why the typed name cannot be, under the box; replaced in place as it is typed (the form is not rebuilt).
        int reason = labels.size();
        int reasonY = rowTop + CONTROL_H + 1;
        labels.add(new Label(Component.empty(), x, reasonY, DevUi.ERROR));
        Button apply = Button.builder(Component.literal("✓"), b -> {
            String to = insideNames.getOrDefault(key, "").trim();
            if (problemOf.apply(to).isEmpty()) {
                insideNames.remove(key);
                rename.accept(to);
            }
        }).tooltip(Tooltip.create(applyTooltip)).bounds(x + w - 20, rowTop, 20, CONTROL_H).build();
        java.util.function.Consumer<String> check = text -> {
            Optional<StoryRenames.Problem> problem = problemOf.apply(text);
            boolean bad = problem.isPresent() && problem.get() != StoryRenames.Problem.SAME;
            box.setTextColor(bad ? DevUi.ERROR : 0xFFE0E0E0);
            apply.active = problem.isEmpty();
            labels.set(reason, new Label(bad ? DevUi.text("rename.problem." + problem.get().name().toLowerCase(java.util.Locale.ROOT), "") : Component.empty(),
                    x, reasonY, DevUi.ERROR));
        };
        box.setResponder(text -> {
            insideNames.put(key, text);
            check.accept(text);
        });
        check.accept(box.getValue());
        box.setTooltip(Tooltip.create(tooltip));
        screen.add(box);
        screen.add(apply);
    }

    private static Optional<StoryRenames.Problem> problem(JsonObject quest, boolean objective, String stage, String current, String typed) {
        return objective ? StoryRenames.objectiveProblem(quest, stage, current, typed)
                : StoryRenames.stageProblem(quest, current, typed);
    }

    /** The NPC's relationship scale, with the binding's other yes/no fields: yes makes it, no deletes it if unused. */
    private void relationshipField(ResourceLocation npc) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("relationship.field"));
        boolean exists = RelationshipScales.exists(npc);
        Button button = Button.builder(Component.literal(DevUi.yesNo(exists)), b -> {
            if (RelationshipScales.exists(npc)) {
                RelationshipScales.remove(screen, npc);
            } else {
                RelationshipScales.create(screen, npc);
            }
            screen.rebuild();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("relationship.tooltip", RelationshipScales.of(npc).toString())));
        screen.add(button);
    }

    private void field(List<Object> path, String name, Node node, boolean required, Node owner) {
        Kind kind = Schemas.kind(node);
        JsonObject object = objectAt(path);
        JsonElement value = object != null ? object.get(name) : null;
        Component label = fieldLabel(name, required, owner);
        Tooltip tooltip = node.description().isEmpty() && Schemas.resolve(node).description().isEmpty() ? null
                : Tooltip.create(Component.literal(described(!node.description().isEmpty() ? node.description() : Schemas.resolve(node).description())));
        if (name.equals("difficulty") && path.isEmpty()) {
            difficultyField(name, value, label);
            return;
        }
        if (isEntitySelector(node)) {
            entityField(path, name, node, value, label, required);
            return;
        }
        if (isTypesOrSelector(node)) {
            Node selector = Schemas.variants(node).stream().filter(FormView::isEntitySelector).findFirst().orElseThrow();
            JsonElement written = value;
            if (written != null && !written.isJsonObject()) {
                // Types alone are a selector of only a type: the same in game, and editable on the entity's page.
                JsonObject wrapped = new JsonObject();
                wrapped.add("type", written);
                ensureObject(path).add(name, wrapped);
                written = wrapped;
                host.edited();
            }
            entityField(path, name, selector, written, label, required);
            return;
        }
        if (entityListItems(node) != null) {
            entityListField(path, name, node, value, required);
            return;
        }
        if (name.equals("id") && host.quest() != null && insideQuest(path) != null) {
            insideNameField(path, insideQuest(path));
            return;
        }
        if (name.equals("id") && host.node() != null && host.node().type().equals(ClientDevStore.TYPE_CODEX)
                && path.size() == 2 && "sections".equals(path.getFirst())) {
            JsonObject entry = host.root();
            String current = value instanceof JsonPrimitive id ? id.getAsString() : "";
            nameRow(path.toString(), DevUi.text("rename.section"), DevUi.text("rename.section_tooltip", current), DevUi.text("rename.section_apply"),
                    current, null, typed -> StoryRenames.codexSectionProblem(entry, current, typed), to -> host.renameCodexSection(current, to));
            return;
        }
        if (name.equals("section") && Schemas.properties(owner).containsKey("entry") && kind == Kind.STRING) {
            codexSectionField(path, name, value, label);
            return;
        }
        if (name.equals("text") && kind == Kind.ID && longTextFile(path) != null) {
            longTextFileField(path, name, label, longTextFile(path));
            return;
        }
        if (name.equals("unlock") && Schemas.properties(node).containsKey("on")) {
            unlockField(path, name, node, value, label, tooltip);
            return;
        }
        if (host.dialogue() != null && "target".equals(Schemas.resolve(node).ref())) {
            targetField(path, name, value, label, required);
            return;
        }
        // A waypoint (or add_waypoint): "target or search area" first; an area's radius and looks only for an area.
        if (Schemas.properties(owner).containsKey("show_ring")) {
            if (name.equals("color")) {
                colorField(path, object);
                return;
            }
            boolean area = object != null && object.get("radius") instanceof JsonPrimitive radius && radius.isNumber() && radius.getAsDouble() > 0;
            if (name.equals("radius")) {
                waypointKindField(path, area);
                if (!area) {
                    return;
                }
            } else if ((name.equals("show_radius") || name.equals("show_ring")) && !area) {
                return;
            }
        }
        if (kind == Kind.ID_LIST) {
            Picker picker = picker(name, owner, path);
            if (picker != null) {
                idListRows(path, name, picker, label);
                return;
            }
        }
        switch (kind) {
            case TEXT -> textField(path, name, label, tooltip, longText(name, owner));
            case ENUM -> enumField(path, name, node, required, value, label, tooltip);
            case BOOLEAN -> booleanField(path, name, node, value, needsKubeJs(path, name) ? DevUi.text("form.needs_kubejs", label) : label, tooltip);
            case ID -> {
                if (name.equals("sound")) {
                    soundField(path, name, value, label);
                } else {
                    inputField(path, name, node, kind, value, label, tooltip, owner);
                }
            }
            case ID_LIST, STRING, INTEGER, NUMBER, LITERAL, POSITION, RAW -> inputField(path, name, node, kind, value, label, tooltip, owner);
            default -> {
                // Under its label, as every other field (October 30, 2026; before, "Label: value ▸" in the button itself, the
                // only fields without a label above: the eye found no column of names to run down).
                if (row(LABEL_H + CONTROL_H)) {
                    labelRow(label);
                    Component shown = name.equals("target") && path.isEmpty() && Schemas.properties(owner).containsKey("show_ring")
                            ? Component.literal(targetSummary(value))
                            : name.equals("participants") && path.isEmpty() && isDialogue(owner) ? Component.literal(participantsSummary(value))
                            : questSummary(name, owner, value) != null ? Component.literal(questSummary(name, owner, value))
                            : name.equals("sections") && Schemas.properties(owner).containsKey("show_kills") ? Component.literal(sectionsSummary(value))
                            : summary(kind, node, value);
                    Component summary = Component.empty().append(shown).append(" ▸");
                    Button button = Button.builder(summary, b -> {
                                if (Schemas.isOldActionList(node, value)) {
                                    // Written the older way (only actions, given at once): the same as the object with them and no claim.
                                    JsonArray actions = new JsonArray();
                                    if (value.isJsonArray()) {
                                        actions = value.getAsJsonArray();
                                    } else {
                                        actions.add(value);
                                    }
                                    JsonObject form = new JsonObject();
                                    form.add("actions", actions);
                                    form.addProperty("claim", false);
                                    ensureObject(path).add(name, form);
                                    host.edited();
                                }
                                push(with(path, name), node, fieldLabel(name, false).getString(), !required);
                            })
                            .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
                    if (tooltip != null) {
                        button.setTooltip(tooltip);
                    }
                    screen.add(button);
                }
            }
        }
    }

    private void labelRow(Component label) {
        labels.add(new Label(label, x, rowY(LABEL_H + CONTROL_H), DevUi.LABEL));
    }

    /**
     * Whether a story text is written at length, so its field has the text window's ✎: a dialogue's line and answer, a
     * quest's description, its texts per state and its stages' log, an objective's hint and hints by circumstance. The
     * rest (titles, names, a contract's lines, an objective's line) is one line (October 30, 2026; before, ✎ on every
     * text field).
     */
    private static boolean longText(String name, Node owner) {
        Map<String, Node> properties = Schemas.properties(owner);
        return switch (name) {
            case "description", "log", "active", "completed", "failed" -> true;
            // An objective's hint and its hints by circumstance are sentences too; a contract's hint is one line.
            case "hint" -> properties.containsKey("since_stage");
            case "text" -> properties.containsKey("speaker") || properties.containsKey("locked_text") || properties.containsKey("after");
            default -> false;
        };
    }

    private void textField(List<Object> path, String name, Component label, @Nullable Tooltip tooltip, boolean window) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        JsonObject object = objectAt(path);
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        EditBox box = new EditBox(screen.font(), x, rowTop, window ? w - 20 : w, CONTROL_H - 2, label);
        box.setMaxLength(1024);
        box.setValue(object != null ? ClientDevStore.readStoryText(object, name, "", ClientDevStore.activeLanguage()) : "");
        if (!ClientDevStore.activeLanguage().equalsIgnoreCase(ClientDevStore.sourceLanguage()) && object != null) {
            box.setHint(Component.literal(ClientDevStore.readStoryText(object, name, "", ClientDevStore.sourceLanguage())));
        }
        String key = host.textKey(keyPath(path), name);
        box.setResponder(value -> {
            JsonObject owner = ensureObject(path);
            if (value.isEmpty() && !owner.has(name)) {
                return;
            }
            String existing = ClientDevStore.getKey(owner, name);
            ClientDevStore.writeStoryText(owner, name, value, existing != null && !existing.isBlank() ? existing : key);
            host.edited();
        });
        if (tooltip != null) {
            box.setTooltip(tooltip);
        }
        screen.add(box);
        if (!window) {
            return;
        }
        // The same text in a window of its own, for a long line (October 29, 2026; before, only the one-line box).
        screen.add(Button.builder(Component.literal("✎"), b -> {
            JsonObject owner = objectAt(path);
            String current = owner != null ? ClientDevStore.readStoryText(owner, name, "", ClientDevStore.activeLanguage()) : "";
            String source = owner != null ? ClientDevStore.readStoryText(owner, name, "", ClientDevStore.sourceLanguage()) : "";
            boolean translating = !ClientDevStore.activeLanguage().equalsIgnoreCase(ClientDevStore.sourceLanguage());
            screen.textEditor().openPlain(label, current, translating && !source.isBlank() ? Component.literal(source) : DevUi.text("text.hint_plain"), text -> {
                JsonObject target = ensureObject(path);
                if (text.isEmpty() && !target.has(name)) {
                    return;
                }
                String existing = ClientDevStore.getKey(target, name);
                ClientDevStore.writeStoryText(target, name, text, existing != null && !existing.isBlank() ? existing : key);
                host.changed();
            });
        }).tooltip(Tooltip.create(DevUi.text("text.open_window"))).bounds(x + w - 18, rowTop - 1, 18, CONTROL_H).build());
    }

    private void enumField(List<Object> path, String name, Node node, boolean required, @Nullable JsonElement value, Component label, @Nullable Tooltip tooltip) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        List<String> values = new ArrayList<>(Schemas.enumValues(node));
        if (!required) {
            values.addFirst("");
        }
        String current = value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        JsonElement fallback = node.defaultValue() != null ? node.defaultValue() : Schemas.resolve(node).defaultValue();
        Component shown = current.isEmpty()
                ? DevUi.text("form.unset", fallback != null ? enumLabel(name, fallback.getAsString()) : DevUi.text("none"))
                : enumLabel(name, current);
        String byDefault = fallback != null && fallback.isJsonPrimitive() ? fallback.getAsString() : null;
        Button button = Button.builder(shown, b -> {
            String next = nextChoice(values, current, byDefault);
            JsonObject owner = ensureObject(path);
            if (next.isEmpty()) {
                owner.remove(name);
            } else {
                owner.addProperty(name, next);
            }
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        if (tooltip != null) {
            button.setTooltip(tooltip);
        }
        screen.add(button);
    }

    /**
     * A quest's difficulty: cycles through the scale the modpack picked (1 to 5 stars, or its levels), shown as the
     * Journal shows it, then unset. A value off the scale (stars under a scale of levels) shows as it is until changed.
     */
    private void difficultyField(String name, @Nullable JsonElement value, Component label) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        DifficultyScale scale = DifficultyScale.current();
        List<QuestDefinition.Difficulty> choices = scale.choices();
        QuestDefinition.Difficulty current = value == null ? null
                : QuestDefinition.Difficulty.CODEC.parse(JsonOps.INSTANCE, value).result().orElse(null);
        Component shown = current == null ? DevUi.text("form.unset", DevUi.text("none")) : scale.render(current);
        Button button = Button.builder(shown, b -> {
            int next = current == null ? 0 : choices.indexOf(current) + 1;
            JsonObject root = ensureObject(List.of());
            if (next >= choices.size()) {
                root.remove(name);
            } else {
                root.add(name, QuestDefinition.Difficulty.CODEC.encodeStart(JsonOps.INSTANCE, choices.get(next)).getOrThrow());
            }
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text(scale.style() == DifficultyScale.Style.STARS ? "form.difficulty.stars" : "form.difficulty.levels")));
        screen.add(button);
    }

    /**
     * The choice after the current one. Unset counts as its default: the server saves definitions in canonical form,
     * which leaves a default value out, so writing the default would come back unset and the button would never get
     * past it (a dialogue's presentation stuck on "screen").
     *
     * @param values  the choices, "" (unset) first when the field is optional
     * @param current the value written, "" when unset
     */
    static String nextChoice(List<String> values, String current, @Nullable String byDefault) {
        String effective = current.isEmpty() && byDefault != null && values.contains(byDefault) ? byDefault : current;
        return values.get((values.indexOf(effective) + 1) % values.size());
    }

    /**
     * A waypoint's colour: a swatch and its code. Typing a code recolours the swatch as soon as it is one; the swatch
     * opens a list of the colours that look the same everywhere ({@link WaypointDefinition#MARKER_COLORS}), with an own
     * colour written before on top. Empty, or the default's code, leaves the field out (the marker's own look).
     */
    private void colorField(List<Object> path, @Nullable JsonObject object) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("form.waypoint_color"));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        Integer written = object != null && object.get("color") instanceof JsonPrimitive value ? parseColor(value.getAsString()) : null;
        int[] shown = {written != null ? written : WaypointDefinition.DEFAULT_COLOR};
        EditBox box = new EditBox(screen.font(), x + CONTROL_H + 4, rowTop + 1, w - CONTROL_H - 4, CONTROL_H - 2, DevUi.text("form.waypoint_color"));
        box.setMaxLength(7);
        box.setValue(written != null ? String.format("#%06X", written & 0xFFFFFF) : "");
        box.setHint(DevUi.text("form.waypoint_color.default").copy().withStyle(style -> style.withColor(0xFF6B7380)));
        box.setResponder(text -> {
            JsonObject owner = ensureObject(path);
            if (text.isBlank()) {
                owner.remove("color");
                shown[0] = WaypointDefinition.DEFAULT_COLOR;
                box.setTextColor(0xFFE0E0E0);
                host.edited();
                return;
            }
            Integer parsed = parseColor(text);
            box.setTextColor(parsed != null ? 0xFFE0E0E0 : DevUi.ERROR);
            if (parsed != null) {
                if (parsed == WaypointDefinition.DEFAULT_COLOR) {
                    owner.remove("color");
                } else {
                    owner.addProperty("color", String.format("#%06X", parsed & 0xFFFFFF));
                }
                shown[0] = parsed;
                host.edited();
            }
        });
        Swatch swatch = new Swatch(x, rowTop, CONTROL_H, () -> shown[0], () -> {
            List<DevModeScreen.MenuItem> items = new ArrayList<>();
            if (shown[0] != WaypointDefinition.DEFAULT_COLOR && !WaypointDefinition.MARKER_COLORS.contains(shown[0])) {
                int own = shown[0];
                items.add(DevModeScreen.MenuItem.color(DevUi.text("form.waypoint_color.own").copy()
                        .append(String.format(" #%06X", own & 0xFFFFFF)), own, () -> pickColor(path, own)));
            }
            for (int color : WaypointDefinition.MARKER_COLORS) {
                items.add(DevModeScreen.MenuItem.color(DevUi.text("form.waypoint_color." + WaypointDefinition.colorName(color)), color,
                        () -> pickColor(path, color)));
            }
            screen.openMenu(x, rowTop + CONTROL_H + 2, items);
        });
        swatch.setTooltip(Tooltip.create(DevUi.text("form.waypoint_color.tooltip")));
        screen.add(swatch);
        screen.add(box);
    }

    private void pickColor(List<Object> path, int color) {
        JsonObject owner = ensureObject(path);
        if (color == WaypointDefinition.DEFAULT_COLOR) {
            owner.remove("color");
        } else {
            owner.addProperty("color", String.format("#%06X", color & 0xFFFFFF));
        }
        host.changed();
    }

    /** {@code #RRGGBB} or {@code RRGGBB}, opaque; null for anything else. */
    private static @Nullable Integer parseColor(String text) {
        String hex = text.trim().startsWith("#") ? text.trim().substring(1) : text.trim();
        return hex.matches("[0-9a-fA-F]{6}") ? 0xFF000000 | Integer.parseInt(hex, 16) : null;
    }

    /** The colour of a field as it is now (it follows the code being typed); a click opens the list of colours. */
    private static final class Swatch extends net.minecraft.client.gui.components.AbstractButton {
        private final Supplier<Integer> color;
        private final Runnable open;

        Swatch(int x, int y, int size, Supplier<Integer> color, Runnable open) {
            super(x, y, size, size, Component.empty());
            this.color = color;
            this.open = open;
        }

        @Override
        public void onPress() {
            open.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, isHovered() ? 0xFFFFFFFF : 0xFF8A8A8A);
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0xFF2A2A2A);
            graphics.fill(getX() + 3, getY() + 3, getX() + width - 3, getY() + height - 3, 0xFF000000 | color.get());
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * A waypoint is a target (a point, the diamond) or a search area (the ring icon, a radius, its radius written by
     * the name and its edge drawn on the ground). Switching to a target takes the area's fields away; to an area gives
     * it a radius of {@link #NEW_AREA_RADIUS}.
     */
    private void waypointKindField(List<Object> path, boolean area) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("form.waypoint_kind"));
        Component shown = DevUi.text(area ? "form.waypoint_area" : "form.waypoint_target");
        Button button = Button.builder(shown, b -> {
            JsonObject owner = ensureObject(path);
            if (area) {
                owner.remove("radius");
                owner.remove("show_radius");
                owner.remove("show_ring");
            } else {
                owner.addProperty("radius", NEW_AREA_RADIUS);
            }
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("form.waypoint_kind.tooltip")));
        screen.add(button);
    }

    /**
     * An NPC binding's {@code custom} where the server has no KubeJS: its event would reach no script, so the field says
     * so, greyed (it can still be switched, to take it back).
     */
    private boolean needsKubeJs(List<Object> path, String name) {
        ClientDevStore.Scripts scripts = ClientDevStore.scripts();
        return path.isEmpty() && name.equals("custom") && host.npcBinding() != null && scripts.known() && !scripts.kubejs();
    }

    private void booleanField(List<Object> path, String name, Node node, @Nullable JsonElement value, Component label, @Nullable Tooltip tooltip) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        if (needsKubeJs(path, name)) {
            labels.add(new Label(label, x, rowY(LABEL_H + CONTROL_H), 0xFF78909C));
        } else {
            labelRow(label);
        }
        JsonElement fallback = node.defaultValue();
        boolean byDefault = fallback != null && fallback.isJsonPrimitive() && fallback.getAsBoolean();
        boolean current = value != null && value.isJsonPrimitive() ? value.getAsBoolean() : byDefault;
        Component shown = value == null ? DevUi.text("form.unset", DevUi.yesNo(byDefault)) : Component.literal(DevUi.yesNo(current));
        Button button = Button.builder(shown, b -> {
            ensureObject(path).addProperty(name, !current);
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        if (tooltip != null) {
            button.setTooltip(tooltip);
        }
        screen.add(button);
    }

    private void inputField(List<Object> path, String name, Node node, Kind kind, @Nullable JsonElement value, Component label,
                            @Nullable Tooltip tooltip, Node owner) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        int rowY = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        boolean uuid = kind == Kind.STRING && Schemas.isUuid(node);
        Picker picker = !uuid && (kind == Kind.ID || kind == Kind.ID_LIST || kind == Kind.STRING) ? picker(name, owner, path) : null;
        boolean here = kind == Kind.POSITION;
        // A position has two buttons (where I stand, pick in the world); a picker or a uuid one.
        int boxW = w - (here ? 40 : picker != null || uuid ? 20 : 0);
        EditBox box = new EditBox(screen.font(), x, rowY, boxW, CONTROL_H - 2, label);
        box.setMaxLength(4096);
        box.setValue(display(kind, value));
        JsonElement fallback = node.defaultValue() != null ? node.defaultValue() : Schemas.resolve(node).defaultValue();
        Component byKind = documentDefault(name, path) != null ? documentDefault(name, path) : stageDefault(name, path);
        if (byKind != null) {
            box.setHint(byKind.copy().withStyle(style -> style.withColor(0xFF6B7380)));
        } else if (fallback != null) {
            box.setHint(Component.literal(display(kind, fallback)).withStyle(style -> style.withColor(0xFF6B7380)));
        }
        box.setResponder(text -> {
            JsonObject target = ensureObject(path);
            if (text.isBlank()) {
                target.remove(name);
                box.setTextColor(0xFFE0E0E0);
                host.edited();
                return;
            }
            JsonElement parsed = parse(kind, text);
            box.setTextColor(parsed != null ? 0xFFE0E0E0 : DevUi.ERROR);
            if (parsed != null) {
                target.add(name, parsed);
                host.edited();
            }
        });
        if (tooltip != null) {
            box.setTooltip(tooltip);
        }
        screen.add(box);
        if (picker != null) {
            screen.add(Button.builder(Component.literal("…"), b -> pick(picker, entry -> {
                JsonObject target = ensureObject(path);
                if (kind == Kind.ID_LIST && target.has(name) && !display(kind, target.get(name)).isBlank()) {
                    target.add(name, parse(Kind.ID_LIST, display(kind, target.get(name)) + ", " + entry.id()));
                } else {
                    target.addProperty(name, entry.id());
                }
                if (entry.held()) {
                    held(target, owner, entry.icon());
                }
                host.changed();
            })).tooltip(Tooltip.create(DevUi.text("form.pick"))).bounds(x + w - 18, rowY - 1, 18, CONTROL_H).build());
        } else if (uuid) {
            screen.add(Button.builder(Component.literal("🎯"), b -> DevWorldPicker.pickEntity(screen, DevUi.text("picker.entity_prompt"), entity -> {
                JsonObject target = ensureObject(path);
                target.addProperty(name, entity.getUUID().toString());
                // In an entity selector the type goes along, so the binding reads well and survives a typo in the uuid.
                if (Schemas.properties(owner).containsKey("type") && !target.has("type")) {
                    target.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                }
                host.changed();
            })).tooltip(Tooltip.create(DevUi.text("form.pick_entity"))).bounds(x + w - 18, rowY - 1, 18, CONTROL_H).build());
        } else if (here) {
            screen.add(Button.builder(Component.literal("📍"), b -> {
                Player player = Minecraft.getInstance().player;
                if (player != null) {
                    writePosition(path, name, node, owner, new double[]{player.getX(), player.getY(), player.getZ()},
                            player.level().dimension().location());
                }
            }).tooltip(Tooltip.create(DevUi.text("form.here"))).bounds(x + w - 38, rowY - 1, 18, CONTROL_H).build());
            screen.add(Button.builder(Component.literal("🎯"), b -> DevWorldPicker.pickBlock(screen, DevUi.text("picker.block_prompt"),
                    (pos, dimension) -> writePosition(path, name, node, owner, new double[]{pos.getX(), pos.getY(), pos.getZ()}, dimension)))
                    .tooltip(Tooltip.create(DevUi.text("form.pick_block"))).bounds(x + w - 18, rowY - 1, 18, CONTROL_H).build());
        }
    }

    /**
     * Writes a position, whole or to a tenth as the schema wants; the dimension goes along when the object has a field
     * for it (an area, a waypoint target, a place of an action).
     */
    private void writePosition(List<Object> path, String name, Node node, Node owner, double[] coordinates, ResourceLocation dimension) {
        Node items = Schemas.items(node);
        boolean whole = items != null && "integer".equals(Schemas.resolve(items).json().has("type") ? Schemas.resolve(items).json().get("type").getAsString() : "");
        JsonArray pos = new JsonArray();
        for (double coordinate : coordinates) {
            pos.add(whole ? (Number) Math.round(coordinate) : (Number) (Math.round(coordinate * 10) / 10.0));
        }
        JsonObject target = ensureObject(path);
        target.add(name, pos);
        if (Schemas.properties(owner).containsKey("dimension")) {
            target.addProperty("dimension", dimension.toString());
        }
        host.changed();
    }

    /** The held stack's count and components go to the fields next to the item, when the object has them. */
    private static void held(JsonObject target, Node owner, ItemStack stack) {
        Map<String, Node> properties = Schemas.properties(owner);
        if (properties.containsKey("count")) {
            target.addProperty("count", stack.getCount());
        }
        Minecraft minecraft = Minecraft.getInstance();
        DataComponentPatch patch = stack.getComponentsPatch();
        if (properties.containsKey("components") && !patch.isEmpty() && minecraft.level != null) {
            DataComponentPatch.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess()), patch).result()
                    .filter(element -> element.isJsonObject() && !element.getAsJsonObject().isEmpty())
                    .ifPresent(element -> target.add("components", element));
        }
    }

    private static String display(Kind kind, @Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonArray() && (kind == Kind.ID_LIST || kind == Kind.POSITION)) {
            List<String> parts = new ArrayList<>();
            value.getAsJsonArray().forEach(element -> parts.add(element.isJsonPrimitive() ? element.getAsString() : element.toString()));
            return String.join(kind == Kind.POSITION ? " " : ", ", parts);
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    /** @return the value, or null when the text is not one (the box turns red) */
    static @Nullable JsonElement parse(Kind kind, String text) {
        String trimmed = text.trim();
        try {
            return switch (kind) {
                case INTEGER -> new JsonPrimitive(Long.parseLong(trimmed));
                case NUMBER -> new JsonPrimitive(Double.parseDouble(trimmed));
                case LITERAL -> trimmed.equals("true") || trimmed.equals("false") ? new JsonPrimitive(Boolean.parseBoolean(trimmed))
                        : trimmed.matches("-?\\d+") ? new JsonPrimitive(Long.parseLong(trimmed)) : new JsonPrimitive(text);
                case ID_LIST -> {
                    List<String> ids = new ArrayList<>();
                    for (String part : trimmed.split(",")) {
                        if (!part.isBlank()) {
                            ids.add(part.trim());
                        }
                    }
                    if (ids.size() == 1) {
                        yield new JsonPrimitive(ids.getFirst());
                    }
                    JsonArray array = new JsonArray();
                    ids.forEach(array::add);
                    yield array;
                }
                case POSITION -> {
                    String[] parts = trimmed.split("[\\s,]+");
                    if (parts.length != 3) {
                        yield null;
                    }
                    JsonArray array = new JsonArray();
                    for (String part : parts) {
                        double number = Double.parseDouble(part);
                        array.add(number == Math.rint(number) ? (Number) (long) number : (Number) number);
                    }
                    yield array;
                }
                // Anything that is not JSON is taken as text, so "minecraft:wolf" needs no quotes.
                case RAW -> {
                    try {
                        yield JsonParser.parseString(trimmed);
                    } catch (JsonParseException e) {
                        yield new JsonPrimitive(text);
                    }
                }
                default -> new JsonPrimitive(text);
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- nested pages

    private void listPage(List<Object> path, @Nullable Node items) {
        if (items == null) {
            return;
        }
        JsonElement value = get(path);
        JsonArray array = value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
        if (value != null && value.isJsonObject()) {
            array.add(value);
        }
        Kind itemKind = Schemas.kind(items);
        // Values, not objects: edited in the row itself (RAW: an id or an object, as JSON or plain text).
        boolean primitive = switch (itemKind) {
            case ID, STRING, ID_LIST, INTEGER, NUMBER, LITERAL, RAW -> true;
            default -> false;
        };
        String field = path.getLast() instanceof String name ? name : "";
        Picker picker = primitive ? picker(field.equals("waypoints") ? "waypoint" : field, items) : null;
        for (int i = 0; i < array.size(); i++) {
            int index = i;
            if (!row(CONTROL_H)) {
                continue;
            }
            int rowY = rowY(CONTROL_H);
            int buttonsW = (i > 0 ? 18 : 0) + 18;
            if (primitive) {
                EditBox box = new EditBox(screen.font(), x, rowY + 1, w - buttonsW - 2, CONTROL_H - 2, Component.empty());
                box.setMaxLength(512);
                box.setValue(display(itemKind, array.get(i)));
                box.setResponder(text -> {
                    JsonArray list = ensureArray(path);
                    JsonElement parsed = parse(itemKind, text);
                    box.setTextColor(parsed != null ? 0xFFE0E0E0 : DevUi.ERROR);
                    if (index < list.size() && parsed != null) {
                        list.set(index, parsed);
                        host.edited();
                    }
                });
                screen.add(box);
            } else {
                Component summary = Component.literal(itemSummary(array.get(i), items, i) + " ▸");
                screen.add(Button.builder(summary, b -> push(with(path, index), items, itemSummary(array.get(index), items, index), false))
                        .bounds(x, rowY, w - buttonsW - 2, CONTROL_H).build());
            }
            if (i > 0) {
                screen.add(Button.builder(Component.literal("↑"), b -> {
                    JsonArray list = ensureArray(path);
                    JsonElement moved = list.get(index);
                    list.set(index, list.get(index - 1));
                    list.set(index - 1, moved);
                    host.changed();
                }).tooltip(Tooltip.create(DevUi.text("form.move_up"))).bounds(x + w - 36, rowY, 18, CONTROL_H).build());
            }
            screen.add(Button.builder(Component.literal("✕"), b -> {
                ensureArray(path).remove(index);
                host.changed();
            }).tooltip(Tooltip.create(DevUi.text("form.remove"))).bounds(x + w - 18, rowY, 18, CONTROL_H).build());
        }
        if (array.isEmpty() && row(LABEL_H)) {
            labels.add(new Label(DevUi.text("form.empty"), x, rowY(LABEL_H), 0xFF78909C));
        }
        if (!row(CONTROL_H)) {
            return;
        }
        Logic logic = switch (itemKind) {
            case CONDITION -> Logic.CONDITION;
            case ACTION -> Logic.ACTION;
            case TRIGGER -> Logic.TRIGGER;
            default -> null;
        };
        boolean entities = isEntitySelector(items);
        boolean stacks = stackList(field, items);
        screen.add(Button.builder(Component.literal("+ ").append(DevUi.text("form.add")), b -> {
            if (stacks) {
                // Rewards: straight to the items (the one in hand first, with its count and components), not a blank row.
                screen.catalog().open(DevUi.text("form.pick_title.item"), Catalog.Kind.ITEM, (kind, entry) -> {
                    ensureArray(path).add(stack(items, entry));
                    host.changed();
                });
                screen.rebuild();
            } else if (entities) {
                // Straight to its page: how it is known (type, name, one entity) is chosen there.
                JsonArray list = ensureArray(path);
                list.add(new JsonObject());
                frames.push(new Frame(with(path, list.size() - 1), items, DevUi.str("entity.new"), false));
                scroll = 0;
                host.changed();
            } else if (logic != null) {
                pickType(logic, element -> {
                    String type = element.get("type").getAsString();
                    ensureArray(path).add(element);
                    frames.push(new Frame(with(path, ensureArray(path).size() - 1), items, typeName(logic, type), false));
                    scroll = 0;
                    host.changed();
                });
            } else if (picker != null) {
                pick(picker, entry -> {
                    ensureArray(path).add(entry.id());
                    host.changed();
                });
            } else if (primitive) {
                ensureArray(path).add("");
                host.changed();
            } else {
                JsonArray list = ensureArray(path);
                JsonObject element = itemKind == Kind.OBJECT ? Schemas.skeleton(items) : new JsonObject();
                // A quest's objective gets a name too, though the schema does not ask for one: by position, players'
                // counters would move to another objective when the list is reordered (October 30, 2026).
                boolean named = Schemas.required(items).contains("id") || Schemas.properties(items).containsKey("since_stage");
                if (named && !element.has("id")) {
                    element.addProperty("id", freeId(list, field));
                }
                // A Codex section must say how it opens: when its creature is seen, or (without one) by an action.
                if (element.get("unlock") instanceof JsonObject opens && !opens.has("on")) {
                    JsonArray on = new JsonArray();
                    on.add(host.root().has("entity") ? "seen" : "manual");
                    opens.add("on", on);
                }
                list.add(element);
                frames.push(new Frame(with(path, list.size() - 1), items, itemSummary(element, items, list.size() - 1), false));
                scroll = 0;
                host.changed();
            }
        }).bounds(x, rowY(CONTROL_H), w, CONTROL_H).build());
    }

    /** {@code stage_2} for the second stage, never one the list already has. */
    private static String freeId(JsonArray list, String field) {
        String base = field.endsWith("s") ? field.substring(0, field.length() - 1) : field.isEmpty() ? "item" : field;
        Set<String> taken = new java.util.HashSet<>();
        list.forEach(element -> {
            if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                taken.add(element.getAsJsonObject().get("id").getAsString());
            }
        });
        int number = list.size() + 1;
        while (taken.contains(base + "_" + number)) {
            number++;
        }
        return base + "_" + number;
    }

    private void mapPage(List<Object> path, @Nullable Node values) {
        if (values == null) {
            return;
        }
        JsonObject map = objectAt(path);
        if (map != null) {
            for (String key : List.copyOf(map.keySet())) {
                if (!row(CONTROL_H)) {
                    continue;
                }
                int rowY = rowY(CONTROL_H);
                // A dialogue's participant says who it is: "narrator (no entity «Narrator»)".
                String shown = path.size() == 1 && "participants".equals(path.getFirst()) && Schemas.properties(values).containsKey("portrait")
                        ? participantSummary(key, map.get(key)) : key;
                screen.add(Button.builder(Component.literal(shown + " ▸"), b -> push(with(path, key), values, key, false))
                        .bounds(x, rowY, w - 20, CONTROL_H).build());
                screen.add(Button.builder(Component.literal("✕"), b -> {
                    ensureObject(path).remove(key);
                    host.changed();
                }).tooltip(Tooltip.create(DevUi.text("form.remove"))).bounds(x + w - 18, rowY, 18, CONTROL_H).build());
            }
        }
        if (!row(CONTROL_H)) {
            return;
        }
        int rowY = rowY(CONTROL_H);
        EditBox name = new EditBox(screen.font(), x, rowY + 1, w - 20, CONTROL_H - 2, DevUi.text("form.new_name"));
        name.setHint(DevUi.text("form.new_name"));
        name.setMaxLength(64);
        screen.add(name);
        screen.add(Button.builder(Component.literal("+"), b -> {
            String key = name.getValue().trim();
            if (!key.isEmpty() && (map == null || !map.has(key))) {
                ensureObject(path).add(key, Schemas.kind(values) == Kind.OBJECT ? Schemas.skeleton(values) : new JsonObject());
                frames.push(new Frame(with(path, key), values, key, false));
                scroll = 0;
                host.changed();
            }
        }).tooltip(Tooltip.create(DevUi.text("form.add"))).bounds(x + w - 18, rowY, 18, CONTROL_H).build());
    }

    /** A trigger, condition or action: its type (picked from a list), what the type does, then its fields. */
    private void logicPage(List<Object> path, Logic logic) {
        Node node = Schemas.logic(logic.name);
        JsonObject object = objectAt(path);
        String type = object != null && object.has("type") && object.get("type").isJsonPrimitive() ? object.get("type").getAsString() : null;
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("form.type"));
            screen.add(Button.builder(type != null ? Component.literal(typeName(logic, type)) : DevUi.text("form.pick_type"), b -> pickType(logic, element -> {
                put(ensureParent(path), path, path.size() - 1, element);
                host.changed();
            })).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
        }
        if (type == null || node == null) {
            return;
        }
        Node variant = Schemas.typed(node, type);
        if (variant == null) {
            // Another mod's type: no schema, so its fields are edited as JSON.
            text(DevUi.text("form.no_schema"), DevUi.MUTED);
            JsonObject rest = object.deepCopy();
            rest.remove("type");
            if (row(CONTROL_H)) {
                EditBox box = new EditBox(screen.font(), x, rowY(CONTROL_H), w, CONTROL_H - 2, Component.empty());
                box.setMaxLength(8192);
                box.setValue(rest.toString());
                box.setResponder(text -> {
                    try {
                        JsonObject fields = JsonParser.parseString(text).getAsJsonObject();
                        JsonObject element = new JsonObject();
                        element.addProperty("type", type);
                        fields.entrySet().forEach(entry -> element.add(entry.getKey(), entry.getValue()));
                        put(ensureParent(path), path, path.size() - 1, element);
                        box.setTextColor(0xFFE0E0E0);
                        host.edited();
                    } catch (RuntimeException e) {
                        box.setTextColor(DevUi.ERROR);
                    }
                });
                screen.add(box);
            }
            return;
        }
        String help = typeHelp(logic, type, variant);
        if (!help.isEmpty()) {
            text(Component.literal(help), DevUi.MUTED);
        }
        objectPage(path, variant, Set.of(), "type");
    }

    /** One of several shapes (a waypoint's target: a point, an entity, a structure, the nearest mob). */
    private void variantsPage(List<Object> path, Node schema) {
        List<Node> variants = Schemas.variants(schema);
        if (variants.isEmpty()) {
            return;
        }
        int current = Schemas.variantOf(schema, get(path));
        // A waypoint's target: "Points at: a point / one entity / the nearest structure / the nearest mob".
        boolean target = path.size() == 1 && "target".equals(path.getFirst()) && host.root().has("name") && Schemas.properties(variants.get(0)).containsKey("pos");
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text(target ? "waypoint.points_at" : "form.variant"));
            Node shown = variants.get(current);
            Component label = target && !Schemas.required(shown).isEmpty() ? DevUi.text("waypoint.kind." + Schemas.required(shown).iterator().next()) : variantLabel(shown);
            screen.add(Button.builder(Component.empty().append(label).append(" ▸"), b -> {
                Node next = variants.get((current + 1) % variants.size());
                put(ensureParent(path), path, path.size() - 1, Schemas.skeleton(next));
                host.changed();
            }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
        }
        objectPage(path, variants.get(current), Set.of(), null);
    }

    /**
     * A waypoint's target in words: "point 120, 64, -30", "· the Nether" when not the overworld; "nearest village";
     * "nearest Wolf · within 64"; "entity …a1b2". Before, only the kind ("Position").
     */
    private static String targetSummary(@Nullable JsonElement value) {
        if (!(value instanceof JsonObject target) || target.isEmpty()) {
            return "—";
        }
        String dimension = target.get("dimension") instanceof JsonPrimitive named && !named.getAsString().equals("minecraft:overworld")
                ? " · " + dimensionName(named.getAsString()) : "";
        if (target.get("pos") instanceof JsonArray pos && pos.size() == 3) {
            List<String> parts = new ArrayList<>();
            pos.forEach(part -> parts.add(format(part.getAsDouble())));
            return DevUi.str("waypoint.target.pos", String.join(", ", parts)) + dimension;
        }
        if (target.get("entity") instanceof JsonPrimitive uuid) {
            String id = uuid.getAsString();
            return DevUi.str("waypoint.target.entity", id.length() > 4 ? id.substring(id.length() - 4) : id);
        }
        if (target.get("structure") instanceof JsonPrimitive structure) {
            String id = structure.getAsString();
            return DevUi.str("waypoint.target.structure", id.substring(id.indexOf(':') + 1).replace('_', ' ')) + dimension;
        }
        if (target.has("nearest")) {
            JsonElement nearest = target.get("nearest");
            // A type, a #tag or a list reads as a selector of only a type.
            JsonObject selector = nearest instanceof JsonObject written ? written : new JsonObject();
            if (!(nearest instanceof JsonObject)) {
                selector.add("type", nearest);
            }
            String what = entitySummary(selector);
            int range = target.get("range") instanceof JsonPrimitive number && number.isNumber() ? number.getAsInt() : 64;
            return DevUi.str("waypoint.target.nearest", what, range);
        }
        return "—";
    }

    private static String dimensionName(String dimension) {
        String key = "storybound.dev.dimension." + dimension.substring(dimension.indexOf(':') + 1);
        return Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) : dimension;
    }

    private static Component variantLabel(Node variant) {
        Set<String> required = Schemas.required(variant);
        return required.isEmpty() ? Component.literal("?") : fieldLabel(required.iterator().next(), false);
    }

    // --- type and value names

    /**
     * The list of types to add; ready-made lines first ({@link #quick}). {@code picked} gets the new element: just its
     * type, or a ready-made one filled in.
     */
    private void pickType(Logic logic, Consumer<JsonObject> picked) {
        List<Entry> entries = new ArrayList<>();
        Map<String, JsonObject> ready = new LinkedHashMap<>();
        for (Quick line : quick(logic)) {
            String id = "quick:" + ready.size();
            ready.put(id, line.element());
            entries.add(new Entry(id, line.label(), line.icon().get()));
        }
        for (String type : Schemas.logicTypes(logic)) {
            entries.add(new Entry(type, typeName(logic, type), new ItemStack(icon(logic))));
        }
        Registry<?> registry = switch (logic) {
            case CONDITION -> LogicRegistries.CONDITION_TYPES;
            case ACTION -> LogicRegistries.ACTION_TYPES;
            case TRIGGER -> LogicRegistries.TRIGGER_TYPES;
        };
        // Types other mods and addons registered; they have no schema here, so their fields are JSON.
        registry.keySet().stream().filter(id -> !id.getNamespace().equals(Storybound.MOD_ID)).sorted().forEach(id ->
                entries.add(new Entry(id.toString(), typeName(logic, id.toString()), new ItemStack(Items.COMMAND_BLOCK))));
        screen.catalog().openList(DevUi.text("form.pick_type." + logic.name), entries, (kind, entry) -> {
            JsonObject element = ready.containsKey(entry.id()) ? ready.get(entry.id()).deepCopy() : new JsonObject();
            if (!element.has("type")) {
                element.addProperty("type", entry.id());
            }
            picked.accept(element);
        });
        screen.rebuild();
    }

    /** A ready-made line: its label in the list, its icon (made only when shown) and the element it adds. */
    record Quick(String label, Supplier<ItemStack> icon, JsonObject element) {}

    /**
     * Ready-made lines: an NPC's schedule (by day, in the evening, at night) for conditions, and for every relationship
     * scale ({@link #relationshipScales}) a change for actions and a threshold for conditions.
     */
    static List<Quick> quick(Logic logic) {
        List<Quick> lines = new ArrayList<>();
        if (logic == Logic.TRIGGER) {
            // Observing, on top: examining a clue or studying a creature is what a story asks for most after a kill.
            JsonObject observed = new JsonObject();
            observed.addProperty("type", "observed");
            lines.add(new Quick("🔍 " + DevUi.str("quick.observe"), () -> new ItemStack(Items.SPYGLASS), observed));
        }
        if (logic == Logic.CONDITION) {
            for (String period : List.of("day", "sunset", "night")) {
                JsonObject time = new JsonObject();
                time.addProperty("type", "time");
                time.addProperty("period", period);
                lines.add(new Quick("🕒 " + DevUi.str("quick.time." + period), () -> new ItemStack(Items.CLOCK), time));
            }
        }
        for (ResourceLocation scale : relationshipScales()) {
            String name = scale.getPath().substring(RELATIONSHIP_PREFIX.length());
            if (logic == Logic.ACTION) {
                for (int amount : new int[]{10, -10}) {
                    JsonObject change = new JsonObject();
                    change.addProperty("type", "add_var");
                    change.addProperty("key", scale.toString());
                    change.addProperty("amount", amount);
                    lines.add(new Quick("❤ " + DevUi.str("quick.relationship_change", name, (amount > 0 ? "+" : "") + amount), () -> new ItemStack(Items.POPPY), change));
                }
            } else if (logic == Logic.CONDITION) {
                for (int threshold : new int[]{20, -20}) {
                    JsonObject check = new JsonObject();
                    check.addProperty("type", "var");
                    check.addProperty("key", scale.toString());
                    check.addProperty("op", threshold > 0 ? ">=" : "<=");
                    check.addProperty("value", threshold);
                    lines.add(new Quick("❤ " + DevUi.str("quick.relationship_check", name, (threshold > 0 ? "≥ " : "≤ ") + threshold), () -> new ItemStack(Items.POPPY), check));
                }
            }
        }
        return lines;
    }

    /** Where relationship scales live: declared number flags named {@code <ns>:rep/<who>}. */
    static final String RELATIONSHIP_PREFIX = StoryRenames.RELATIONSHIP_PREFIX;

    /** The story's relationship scales, by id. */
    static List<ResourceLocation> relationshipScales() {
        return ClientDevStore.byType(ClientDevStore.TYPE_FLAG).keySet().stream()
                .filter(id -> id.getPath().startsWith(RELATIONSHIP_PREFIX)).sorted().toList();
    }

    private static net.minecraft.world.item.Item icon(Logic logic) {
        return switch (logic) {
            case CONDITION -> Items.COMPARATOR;
            case ACTION -> Items.REDSTONE_TORCH;
            case TRIGGER -> Items.TRIPWIRE_HOOK;
        };
    }

    static String typeName(Logic logic, String type) {
        String shortType = DevUi.shortId(type);
        String key = "storybound.dev.type." + logic.name + "." + shortType;
        if (Language.getInstance().has(key)) {
            return Component.translatable(key).getString();
        }
        ResourceLocation id = ResourceLocation.tryParse(type);
        return id != null && !id.getNamespace().equals(Storybound.MOD_ID) && type.contains(":")
                ? DevUi.humanize(id.getPath()) + " (" + id.getNamespace() + ")" : DevUi.humanize(shortType);
    }

    /** A schema description in the game's language (see {@link Schemas#localized}). */
    private static String described(String english) {
        return Schemas.localized(english, Minecraft.getInstance().getLanguageManager().getSelected());
    }

    private static String typeHelp(Logic logic, String type, Node variant) {
        String key = "storybound.dev.help." + logic.name + "." + DevUi.shortId(type);
        return Language.getInstance().has(key) ? Component.translatable(key).getString() : described(variant.description());
    }

    /**
     * A field's label where the form says more than the field's own name: {@code storybound.dev.field.<context>.<name>}
     * (on a waypoint, "conditions" is "show only if"), else the field's own.
     */
    private static Component fieldLabel(String name, boolean required, Node owner) {
        // A dialogue's line and a player's option are spoken: "Line", not "Text".
        Map<String, Node> properties = Schemas.properties(owner);
        if (name.equals("text") && (properties.containsKey("speaker") || properties.containsKey("locked_text"))) {
            Component line = DevUi.text("dialogue.line_text");
            return required ? Component.empty().append(line).append(" *") : line;
        }
        String context = context(owner);
        String key = "storybound.dev.field." + context + "." + name;
        if (context == null || !Language.getInstance().has(key)) {
            return fieldLabel(name, required);
        }
        Component label = Component.translatable(key);
        return required ? Component.empty().append(label).append(" *") : label;
    }

    /** Which form this is, where it needs its own words: a waypoint (or add_waypoint), or null. */
    private static @Nullable String context(Node owner) {
        Map<String, Node> properties = Schemas.properties(owner);
        if (properties.containsKey("show_ring")) {
            return WAYPOINT;
        }
        if (properties.containsKey("participation")) {
            return "boss";
        }
        if (properties.containsKey("time") && properties.containsKey("distance") && properties.containsKey("block")) {
            return "observed";
        }
        if (properties.containsKey("show_kills")) {
            return "codex";
        }
        if (properties.containsKey("on") && properties.containsKey("count")) {
            return "unlock";
        }
        if (properties.containsKey("unlock") && properties.containsKey("id")) {
            return "section";
        }
        if (properties.containsKey("reward") && properties.containsKey("hint") && properties.containsKey("quest")) {
            return "contract";
        }
        if (properties.containsKey("stages") && properties.containsKey("fail_when")) {
            return "quest";
        }
        if (properties.containsKey("since_stage")) {
            return "objective";
        }
        if (properties.containsKey("objectives") && properties.containsKey("on_enter")) {
            return "stage";
        }
        if (properties.containsKey("locked") && properties.containsKey("once")) {
            return "option";
        }
        // A dialogue node's own words, by its type: "storybound.dev.field.node.call.next".
        Node type = properties.get("type");
        JsonObject typeJson = type != null ? Schemas.resolve(type).json() : null;
        return typeJson != null && typeJson.get("const") instanceof JsonPrimitive constant && owner.file().startsWith("dialogue") ? "node." + constant.getAsString() : null;
    }

    /** The stages of the quest the form names, for a waypoint's {@code stage}; none until a quest is chosen. */
    private static List<String> stagesOf(@Nullable JsonObject object) {
        if (object == null || !(object.get("quest") instanceof JsonPrimitive quest)) {
            return List.of();
        }
        ResourceLocation id = ResourceLocation.tryParse(quest.getAsString());
        String raw = id == null ? null : ClientDevStore.byType(ClientDevStore.TYPE_QUEST).get(id);
        List<String> stages = new ArrayList<>();
        if (ClientDevStore.parseObject(raw).get("stages") instanceof JsonArray list) {
            list.forEach(stage -> {
                if (stage instanceof JsonObject entry && entry.get("id") instanceof JsonPrimitive stageId) {
                    stages.add(stageId.getAsString());
                }
            });
        }
        return stages;
    }

    static Component fieldLabel(String name, boolean required) {
        String key = "storybound.dev.field." + name;
        Component label = Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(DevUi.humanize(name));
        return required ? Component.empty().append(label).append(" *") : label;
    }

    /** A value in the words of its field where it has some ("scope": "each player's own"), else in general ones. */
    private static Component enumLabel(String field, String value) {
        String key = "storybound.dev.value." + field + "." + value;
        return Language.getInstance().has(key) ? Component.translatable(key) : valueLabel(value);
    }

    private static Component valueLabel(String value) {
        String key = "storybound.dev.value." + value;
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(value);
    }

    /**
     * A part's value in words, for the button under its label: a list by its elements ("Quest: demo:hunt · Flag set"), a
     * map by its keys, an object by how many of its fields are set; "None" when empty (before, a count: "2", "—").
     */
    private Component summary(Kind kind, Node node, @Nullable JsonElement value) {
        return switch (kind) {
            case CONDITIONS, ACTIONS, LIST -> {
                Node items = kind == Kind.CONDITIONS ? Schemas.logic("condition") : kind == Kind.ACTIONS ? Schemas.logic("action") : Schemas.items(node);
                List<JsonElement> elements = value == null ? List.of() : value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
                if (elements.isEmpty()) {
                    yield DevUi.text("none");
                }
                if (items == null) {
                    yield Component.literal(String.valueOf(elements.size()));
                }
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < elements.size(); i++) {
                    parts.add(itemSummary(elements.get(i), items, i));
                }
                yield Component.literal(String.join(" · ", parts));
            }
            case MAP -> value != null && value.isJsonObject() && !value.getAsJsonObject().isEmpty()
                    ? Component.literal(String.join(" · ", value.getAsJsonObject().keySet())) : DevUi.text("none");
            case TRIGGER, CONDITION, ACTION -> {
                Logic logic = kind == Kind.TRIGGER ? Logic.TRIGGER : kind == Kind.CONDITION ? Logic.CONDITION : Logic.ACTION;
                yield value != null && value.isJsonObject() && value.getAsJsonObject().has("type")
                        ? Component.literal(typeName(logic, value.getAsJsonObject().get("type").getAsString())) : DevUi.text("none");
            }
            case VARIANTS -> value != null ? variantLabel(Schemas.variants(node).get(Schemas.variantOf(node, value))) : DevUi.text("none");
            default -> value != null && value.isJsonObject() && !value.getAsJsonObject().isEmpty()
                    ? DevUi.text("form.fields_set", value.getAsJsonObject().size()) : DevUi.text("none");
        };
    }

    /** A list element in one line: a logic element by its type and main value, an object by its id or text. */
    private static String itemSummary(JsonElement element, Node items, int index) {
        Kind kind = Schemas.kind(items);
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (!element.isJsonObject()) {
            return "#" + (index + 1);
        }
        JsonObject object = element.getAsJsonObject();
        if (isEntitySelector(items)) {
            return entitySummary(object);
        }
        if (Schemas.properties(items).containsKey("weight") && object.get("next") instanceof JsonPrimitive next) {
            int weight = object.get("weight") instanceof JsonPrimitive value && value.isNumber() ? value.getAsInt() : 1;
            return DevUi.str("node.random_branch", weight, choiceLabel(next.getAsString()));
        }
        if ((kind == Kind.CONDITION || kind == Kind.ACTION || kind == Kind.TRIGGER) && object.has("type")) {
            Logic logic = kind == Kind.CONDITION ? Logic.CONDITION : kind == Kind.ACTION ? Logic.ACTION : Logic.TRIGGER;
            String main = mainValue(object);
            return typeName(logic, object.get("type").getAsString()) + (main.isEmpty() ? "" : ": " + main);
        }
        if (Schemas.properties(items).containsKey("count") && (object.get("item") instanceof JsonPrimitive || object.get("id") instanceof JsonPrimitive)) {
            String item = (object.get("item") instanceof JsonPrimitive named ? named : object.get("id").getAsJsonPrimitive()).getAsString();
            ResourceLocation id = ResourceLocation.tryParse(item);
            String name = id != null && BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id).getDescription().getString() : item;
            int count = object.get("count") instanceof JsonPrimitive written && written.isNumber() ? written.getAsInt() : 1;
            return count > 1 ? name + " × " + count : name;
        }
        if (Schemas.properties(items).containsKey("unlock") && object.get("id") instanceof JsonPrimitive id) {
            String title = ClientDevStore.readStoryText(object, "title", "");
            return title.isBlank() ? id.getAsString() : title + " (" + id.getAsString() + ")";
        }
        if (object.has("id") && object.get("id").isJsonPrimitive()) {
            return object.get("id").getAsString();
        }
        if (object.has("text")) {
            String text = ClientDevStore.readStoryText(object, "text", "");
            if (!text.isBlank()) {
                return text;
            }
        }
        String main = mainValue(object);
        return main.isEmpty() ? "#" + (index + 1) : main;
    }

    private static String mainValue(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getKey().equals("type") && entry.getValue().isJsonPrimitive()) {
                return entry.getValue().getAsString();
            }
        }
        return "";
    }

    // --- pickers

    /**
     * What a field can be picked from: a catalog, the story's nodes of a kind, plain choices or prepared rows.
     *
     * @param empty what to say when there is nothing to pick (a stage before the quest is chosen); null: no button then
     */
    private record Picker(@Nullable Catalog.Kind kind, @Nullable ResourceLocation storyType, List<String> choices, Component title,
                          List<Entry> entries, @Nullable Component empty) {
        Picker(@Nullable Catalog.Kind kind, @Nullable ResourceLocation storyType, List<String> choices, Component title) {
            this(kind, storyType, choices, title, List.of(), null);
        }

        static Picker rows(List<Entry> entries, Component title, Component empty) {
            return new Picker(null, null, List.of(), title, entries, empty);
        }

        static Picker choices(List<String> choices, Component title, Component empty) {
            return new Picker(null, null, choices, title, List.of(), empty);
        }
    }

    private @Nullable Picker picker(String field, Node owner, List<Object> path) {
        if (field.equals("next") && Schemas.properties(owner).containsKey("objectives") && "stage".equals(insideQuest(path))) {
            List<String> choices = new ArrayList<>();
            if (host.root().get("stages") instanceof JsonArray stages) {
                stages.forEach(stage -> {
                    if (stage instanceof JsonObject object && object.get("id") instanceof JsonPrimitive id) {
                        choices.add(id.getAsString());
                    }
                });
            }
            choices.add("@complete");
            choices.add("@fail");
            return new Picker(null, null, choices, DevUi.text("quest.pick_next"));
        }
        if (WAYPOINT.equals(context(owner))) {
            List<String> choices = switch (field) {
                case "stage" -> stagesOf(objectAt(path));
                default -> List.of();
            };
            if (!choices.isEmpty()) {
                return new Picker(null, null, choices, fieldLabel(field, false, owner));
            }
        }
        Picker dependent = dependentPicker(field, owner, objectAt(path));
        return dependent != null ? dependent : picker(field, owner);
    }

    /**
     * What depends on another field of the same object (October 30, 2026; before, typed): a quest's stage (the condition
     * "quest at stage", set_quest_stage, the trigger "stage changed"), a dialogue's node (the trigger "dialogue ended",
     * a jump or call to another dialogue), a statistic of its kind, a script's handler, a KubeJS handler, a waypoint
     * added by an action, a Journal entry's section. Without the field it depends on, the button says to pick that first.
     */
    private @Nullable Picker dependentPicker(String field, Node owner, @Nullable JsonObject object) {
        Map<String, Node> properties = Schemas.properties(owner);
        String type = object != null && object.get("type") instanceof JsonPrimitive written ? written.getAsString() : "";
        if (field.equals("stage") && properties.containsKey("quest")) {
            boolean quest = object != null && object.get("quest") instanceof JsonPrimitive named && !named.getAsString().isBlank();
            return Picker.choices(stagesOf(object), DevUi.text("pick.title.stage"), DevUi.text(quest ? "pick.no_stages" : "pick.need_quest"));
        }
        if (field.equals("node") && properties.containsKey("dialogue")) {
            ResourceLocation dialogue = object != null && object.get("dialogue") instanceof JsonPrimitive named ? ResourceLocation.tryParse(named.getAsString()) : null;
            if (dialogue != null) {
                List<String> nodes = new ArrayList<>(ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_DIALOGUE, dialogue)).get("nodes")
                        instanceof JsonObject all ? all.keySet() : java.util.Set.of());
                return Picker.choices(nodes, DevUi.text("pick.title.node"), DevUi.text("pick.no_nodes"));
            }
            // A jump within the dialogue being edited: its own nodes.
            if (host.dialogue() == null) {
                return Picker.choices(List.of(), DevUi.text("pick.title.node"), DevUi.text("pick.need_dialogue"));
            }
        }
        if (field.equals("stat") && properties.containsKey("stat_type")) {
            ResourceLocation statType = object != null && object.get("stat_type") instanceof JsonPrimitive named ? ResourceLocation.tryParse(named.getAsString()) : null;
            return statPicker(statType);
        }
        if (field.equals("hook") && properties.containsKey("file")) {
            String file = object != null && object.get("file") instanceof JsonPrimitive named ? named.getAsString() : "";
            return Picker.rows(handlers(file), DevUi.text("pick.title.handler"), DevUi.text("pick.no_handlers"));
        }
        if (field.equals("id") && type.equals("kubejs")) {
            return Picker.rows(handlers(""), DevUi.text("pick.title.handler"), DevUi.text("pick.no_handlers"));
        }
        if (field.equals("id") && type.equals("remove_waypoint")) {
            List<String> added = new ArrayList<>(new java.util.TreeSet<>(addedWaypoints()));
            return Picker.choices(added, DevUi.text("pick.title.added_waypoint"), DevUi.text("pick.no_added_waypoints"));
        }
        if (field.equals("category") && type.equals("journal_entry")) {
            return new Picker(null, null, journalSections(), DevUi.text("document.pick_section"));
        }
        return null;
    }

    /** A statistic of its kind: an item or a mob from their catalogs, the rest (the custom ones: jumps, time played) listed. */
    private static Picker statPicker(@Nullable ResourceLocation statType) {
        net.minecraft.stats.StatType<?> kind = statType == null ? null : BuiltInRegistries.STAT_TYPE.get(statType);
        if (kind == null) {
            return Picker.rows(List.of(), DevUi.text("pick.title.stat"), DevUi.text("pick.need_stat_type"));
        }
        if (kind.getRegistry() == BuiltInRegistries.ITEM) {
            return new Picker(Catalog.Kind.ITEM, null, List.of(), DevUi.text("pick.title.stat"));
        }
        if (kind.getRegistry() == BuiltInRegistries.ENTITY_TYPE) {
            return new Picker(Catalog.Kind.MOB, null, List.of(), DevUi.text("pick.title.stat"));
        }
        List<Entry> rows = new ArrayList<>();
        kind.getRegistry().keySet().stream().sorted(java.util.Comparator.comparing(ResourceLocation::toString)).forEach(id -> {
            String key = "stat." + id.toLanguageKey();
            String name = Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) + " (" + id + ")" : id.toString();
            rows.add(new Entry(id.toString(), name, new ItemStack(Items.PAPER)));
        });
        return Picker.rows(rows, DevUi.text("pick.title.stat"), DevUi.text("pick.empty"));
    }

    /** The KubeJS handlers the server found, of one file or all: conditions, then actions, under headings. */
    private static List<Entry> handlers(String file) {
        List<Entry> conditions = new ArrayList<>();
        List<Entry> actions = new ArrayList<>();
        for (StoryLinks.ScriptFile script : ClientDevStore.scripts().files().values()) {
            if (!file.isBlank() && !script.path().equals(file)) {
                continue;
            }
            script.conditions().forEach(hook -> conditions.add(new Entry(hook, hook + "  (" + script.path() + ")", new ItemStack(Items.COMPARATOR))));
            script.actions().forEach(hook -> actions.add(new Entry(hook, hook + "  (" + script.path() + ")", new ItemStack(Items.REDSTONE_TORCH))));
        }
        List<Entry> rows = new ArrayList<>();
        if (!conditions.isEmpty()) {
            rows.add(new Entry(Catalog.SECTION + "conditions", DevUi.str("pick.section.conditions"), ItemStack.EMPTY));
            rows.addAll(conditions);
        }
        if (!actions.isEmpty()) {
            rows.add(new Entry(Catalog.SECTION + "actions", DevUi.str("pick.section.actions"), ItemStack.EMPTY));
            rows.addAll(actions);
        }
        return rows;
    }

    /** The ids the story's add_waypoint actions give, which remove_waypoint names. */
    private static List<String> addedWaypoints() {
        List<String> ids = new ArrayList<>();
        ClientDevStore.all().values().forEach(definitions -> definitions.values().forEach(raw -> collectAdded(ClientDevStore.parseObject(raw), ids)));
        return ids;
    }

    private static void collectAdded(JsonElement element, List<String> ids) {
        if (element instanceof JsonArray array) {
            array.forEach(child -> collectAdded(child, ids));
        } else if (element instanceof JsonObject object) {
            if (object.get("type") instanceof JsonPrimitive type && type.getAsString().equals("add_waypoint")
                    && object.get("id") instanceof JsonPrimitive id && !ids.contains(id.getAsString())) {
                ids.add(id.getAsString());
            }
            object.asMap().values().forEach(child -> collectAdded(child, ids));
        }
    }

    /** The Journal's sections: the document kinds' own, then those the story's documents name. */
    private static List<String> journalSections() {
        java.util.Set<String> sections = new java.util.LinkedHashSet<>(List.of("storybound:books", "storybound:scrolls", "storybound:notes",
                "storybound:letters", "storybound:contracts"));
        ClientDevStore.byType(ClientDevStore.TYPE_DOCUMENT).values().forEach(raw -> {
            if (ClientDevStore.parseObject(raw).get("journal") instanceof JsonObject settings && settings.get("category") instanceof JsonPrimitive category) {
                ResourceLocation id = ResourceLocation.tryParse(category.getAsString().contains(":") ? category.getAsString() : "storybound:" + category.getAsString());
                if (id != null) {
                    sections.add(id.toString());
                }
            }
        });
        return List.copyOf(sections);
    }

    private @Nullable Picker picker(String field, Node owner) {
        Catalog.Kind kind = switch (field) {
            case "item" -> Catalog.Kind.ITEM;
            case "entity" -> Catalog.Kind.MOB;
            case "biome" -> Catalog.Kind.BIOME;
            case "structure" -> Catalog.Kind.STRUCTURE;
            case "poi" -> Catalog.Kind.POI;
            case "type" -> "entity_selector".equals(Schemas.resolve(owner).ref()) ? Catalog.Kind.MOB : null;
            default -> null;
        };
        if (kind != null) {
            return new Picker(kind, null, List.of(), DevUi.text("form.pick_title." + kind.name().toLowerCase(java.util.Locale.ROOT)));
        }
        if (field.equals("style") && Schemas.properties(owner).containsKey("journal")) {
            List<String> styles = dev.dzenthai.storybound.client.document.DocumentStyles.INSTANCE.ids().stream().map(ResourceLocation::toString).sorted().toList();
            return new Picker(null, null, styles, DevUi.text("document.pick_style"));
        }
        if (field.equals("category") && Schemas.properties(owner).containsKey("add_on_read")) {
            return new Picker(null, null, journalSections(), DevUi.text("document.pick_section"));
        }
        Picker known = knownPicker(field, owner);
        if (known != null) {
            return known;
        }
        ResourceLocation story = switch (field) {
            case "content" -> Schemas.properties(owner).containsKey("journal") ? ClientDevStore.TYPE_DOCUMENT : null;
            case "quest" -> ClientDevStore.TYPE_QUEST;
            case "dialogue", "busy_dialogue" -> ClientDevStore.TYPE_DIALOGUE;
            case "document" -> ClientDevStore.TYPE_DOCUMENT;
            case "boss" -> ClientDevStore.TYPE_BOSS;
            case "entry" -> ClientDevStore.TYPE_CODEX;
            case "waypoint" -> ClientDevStore.TYPE_WAYPOINT;
            case "key", "variant_by" -> ClientDevStore.TYPE_FLAG;
            default -> null;
        };
        if (story != null) {
            return new Picker(null, story, List.of(), DevUi.text("form.pick_title.story", fieldLabel(field.equals("key") ? "flag" : field, false)));
        }
        List<String> choices = host.choices(field);
        return choices.isEmpty() ? null : new Picker(null, null, choices, fieldLabel(field, false));
    }

    /** Whether the list holds item stacks (a quest's or a boss's rewards): "+ Add" opens the items at once. */
    private static boolean stackList(String field, @Nullable Node items) {
        if (items == null || !(field.equals("items") || field.equals("choice"))) {
            return false;
        }
        if (Schemas.properties(items).containsKey("count")) {
            return true;
        }
        return Schemas.variants(items).stream().anyMatch(variant -> Schemas.properties(variant).containsKey("count"));
    }

    /**
     * The picked item as a list element: a quest's reward {@code {"item": …}}, a boss's an id; with the held stack's
     * count and components when it has them (a boss's then becomes the object form).
     */
    private static JsonElement stack(Node items, Entry entry) {
        Node object = Schemas.properties(items).containsKey("count") ? items
                : Schemas.variants(items).stream().filter(variant -> Schemas.properties(variant).containsKey("count")).findFirst().orElse(items);
        boolean named = Schemas.properties(object).containsKey("item");
        JsonObject element = new JsonObject();
        element.addProperty(named ? "item" : "id", entry.id());
        if (entry.held()) {
            held(element, object, entry.icon());
        }
        if (element.has("count") && element.get("count").getAsInt() == 1) {
            element.remove("count");
        }
        // A boss's plain item stays a plain id, as such lists are written by hand.
        return !named && element.size() == 1 && object != items ? new JsonPrimitive(entry.id()) : element;
    }

    /**
     * Fields naming something the game or the server knows (October 30, 2026; before, typed): a dimension, an effect, an
     * item tag, a kind of statistic, an advancement and a loot table (both listed by the server), a script file, a
     * chapter's icon.
     */
    private static @Nullable Picker knownPicker(String field, Node owner) {
        Map<String, Node> properties = Schemas.properties(owner);
        boolean dimension = field.equals("dimension") || (field.equals("from") || field.equals("to")) && properties.containsKey("from") && properties.containsKey("to");
        if (dimension) {
            List<Entry> rows = new ArrayList<>();
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.levels().stream().map(level -> level.location()).sorted(java.util.Comparator.comparing(ResourceLocation::toString))
                        .forEach(id -> rows.add(new Entry(id.toString(), id.toString(), new ItemStack(id.getPath().contains("nether") ? Items.NETHERRACK
                                : id.getPath().contains("end") ? Items.END_STONE : Items.GRASS_BLOCK))));
            }
            return Picker.rows(rows, DevUi.text("pick.title.dimension"), DevUi.text("pick.empty"));
        }
        if (field.equals("block")) {
            // Kinds of blocks, then the blocks; "…" adds to what is written.
            List<Entry> rows = new ArrayList<>();
            rows.add(new Entry(Catalog.SECTION + "tags", DevUi.str("pick.section.block_tags"), ItemStack.EMPTY));
            BuiltInRegistries.BLOCK.getTagNames().map(tag -> tag.location().toString()).sorted()
                    .forEach(tag -> rows.add(new Entry("#" + tag, "#" + tag, new ItemStack(Items.NAME_TAG))));
            rows.add(new Entry(Catalog.SECTION + "blocks", DevUi.str("pick.section.blocks"), ItemStack.EMPTY));
            BuiltInRegistries.BLOCK.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getKey().location().toString()))
                    .forEach(entry -> rows.add(new Entry(entry.getKey().location().toString(),
                            entry.getValue().getName().getString() + " (" + entry.getKey().location() + ")", new ItemStack(entry.getValue().asItem()))));
            return Picker.rows(rows, DevUi.text("pick.title.block"), DevUi.text("pick.empty"));
        }
        if (field.equals("effect")) {
            List<Entry> rows = new ArrayList<>();
            BuiltInRegistries.MOB_EFFECT.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getKey().location().toString()))
                    .forEach(entry -> rows.add(new Entry(entry.getKey().location().toString(),
                            Component.translatable(entry.getValue().getDescriptionId()).getString() + " (" + entry.getKey().location() + ")", new ItemStack(Items.POTION))));
            return Picker.rows(rows, DevUi.text("pick.title.effect"), DevUi.text("pick.empty"));
        }
        if (field.equals("tag") && properties.containsKey("item")) {
            List<Entry> rows = new ArrayList<>();
            BuiltInRegistries.ITEM.getTagNames().map(tag -> tag.location().toString()).sorted()
                    .forEach(tag -> rows.add(new Entry(tag, "#" + tag, new ItemStack(Items.NAME_TAG))));
            return Picker.rows(rows, DevUi.text("pick.title.item_tag"), DevUi.text("pick.empty"));
        }
        if (field.equals("stat_type")) {
            List<Entry> rows = new ArrayList<>();
            BuiltInRegistries.STAT_TYPE.keySet().stream().sorted(java.util.Comparator.comparing(ResourceLocation::toString)).forEach(id -> {
                net.minecraft.stats.StatType<?> kind = BuiltInRegistries.STAT_TYPE.get(id);
                String name = kind != null ? kind.getDisplayName().getString() + " (" + id + ")" : id.toString();
                rows.add(new Entry(id.toString(), name, new ItemStack(Items.PAPER)));
            });
            return Picker.rows(rows, DevUi.text("pick.title.stat_type"), DevUi.text("pick.empty"));
        }
        if (field.equals("advancement") || field.equals("loot")) {
            String list = field.equals("loot") ? "loot_table" : "advancement";
            List<Entry> rows = new ArrayList<>();
            ClientDevStore.serverList(list).forEach(id -> rows.add(new Entry(id, id, new ItemStack(field.equals("loot") ? Items.CHEST : Items.KNOWLEDGE_BOOK))));
            return Picker.rows(rows, DevUi.text("pick.title." + list), DevUi.text("pick.no_server_list"));
        }
        if (field.equals("file") && properties.containsKey("hook")) {
            List<Entry> rows = new ArrayList<>();
            ClientDevStore.scripts().files().keySet().stream().sorted().forEach(file -> rows.add(new Entry(file, file, new ItemStack(Items.PAPER))));
            return Picker.rows(rows, DevUi.text("script.pick_file_title"), DevUi.text("pick.no_scripts"));
        }
        if (field.equals("icon") && properties.containsKey("order")) {
            return new Picker(Catalog.Kind.ITEM, null, List.of(), DevUi.text("form.pick_title.item"));
        }
        return null;
    }

    private void pick(Picker picker, Consumer<Entry> picked) {
        if (picker.kind() == null && picker.storyType() == null && picker.entries().isEmpty() && picker.choices().isEmpty()) {
            // Nothing to pick yet: say why rather than open an empty list.
            screen.status(picker.empty() != null ? picker.empty() : DevUi.text("pick.empty"), DevUi.WARN);
            return;
        }
        if (!picker.entries().isEmpty()) {
            screen.catalog().openList(picker.title(), picker.entries(), (kind, entry) -> picked.accept(entry));
        } else if (picker.kind() != null) {
            screen.catalog().open(picker.title(), picker.kind(), (kind, entry) -> picked.accept(entry));
        } else {
            List<Entry> entries = new ArrayList<>();
            if (picker.storyType() != null) {
                ClientDevStore.byType(picker.storyType()).forEach((id, raw) -> {
                    JsonObject json = ClientDevStore.parseObject(raw);
                    String title = json.has("title") ? ClientDevStore.readStoryText(json, "title", id.getPath())
                            : json.has("name") ? ClientDevStore.readStoryText(json, "name", id.getPath()) : id.getPath();
                    entries.add(new Entry(id.toString(), title, new ItemStack(Items.WRITABLE_BOOK)));
                });
            } else {
                picker.choices().forEach(choice -> entries.add(new Entry(choice, choiceLabel(choice), new ItemStack(Items.PAPER))));
            }
            screen.catalog().openList(picker.title(), entries, (kind, entry) -> picked.accept(entry));
        }
        screen.rebuild();
    }

    // --- drawing

    void render(GuiGraphics graphics, Font font) {
        for (Label label : labels) {
            if (label.line() != null) {
                graphics.drawString(font, label.line(), label.x(), label.y(), label.color(), false);
            } else if (label.text() != null) {
                graphics.drawString(font, font.plainSubstrByWidth(label.text().getString(), w), label.x(), label.y(), label.color(), false);
            }
        }
    }
}
