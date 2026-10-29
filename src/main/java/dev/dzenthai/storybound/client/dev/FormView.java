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

        /** The quest the form edits, for its stages' and objectives' names; null otherwise. */
        default @Nullable ResourceLocation quest() {
            return null;
        }

        /** Renames a stage of the quest ({@code objective} empty) or an objective of a stage, with what names it. */
        default void renameInQuest(String stage, String objective, String to) {}

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
            labels.add(new Label(Component.literal(crumbs(screen.font(), width - 40)), x + 20, y0 + 3, DevUi.MUTED));
            y0 += 18;
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

    private String crumbs(Font font, int width) {
        StringBuilder out = new StringBuilder(rootLabel);
        List<Frame> list = new ArrayList<>(frames);
        for (int i = list.size() - 1; i >= 0; i--) {
            out.append(" › ").append(list.get(i).label());
        }
        String text = out.toString();
        // The deepest part matters most: cut from the left.
        while (font.width(text) > width && text.length() > 4) {
            text = "…" + text.substring(2);
        }
        return text;
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
        JsonObject object = objectAt(path);
        for (Map.Entry<String, Node> property : Schemas.properties(schema).entrySet()) {
            String name = property.getKey();
            if (skip.contains(name) || name.equals(typeField) || npc && !npcFieldShown(name, object)) {
                continue;
            }
            if (npc && NPC_GROUPS.containsKey(name)) {
                group(DevUi.text(NPC_GROUPS.get(name)));
            }
            if (npc && name.equals("proximity")) {
                proximityField(path, property.getValue(), object);
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
            screen.add(switcher);
            boolean button = !mode.equals("name");
            int boxX = x + modeW + 4;
            int boxW = w - modeW - 4 - (button ? 20 : 0);
            String field = mode;
            EditBox box = new EditBox(screen.font(), boxX, rowTop + 1, boxW, CONTROL_H - 2, DevUi.text("entity.mode." + field));
            box.setMaxLength(256);
            box.setValue(selector != null && selector.has(field) ? display(field.equals("type") ? Kind.ID_LIST : Kind.STRING, selector.get(field)) : "");
            box.setResponder(text -> {
                JsonObject target = ensureObject(path);
                if (text.isBlank()) {
                    target.remove(field);
                    box.setTextColor(0xFFE0E0E0);
                    host.edited();
                    return;
                }
                JsonElement parsed = field.equals("type") ? parse(Kind.ID_LIST, text)
                        : field.equals("uuid") && !text.trim().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ? null
                        : new JsonPrimitive(text.trim());
                box.setTextColor(parsed != null ? 0xFFE0E0E0 : DevUi.ERROR);
                if (parsed != null) {
                    target.add(field, parsed);
                    host.edited();
                }
            });
            box.setTooltip(Tooltip.create(DevUi.text("entity.field." + field)));
            screen.add(box);
            if (field.equals("type")) {
                screen.add(Button.builder(Component.literal("…"), b -> screen.catalog().open(DevUi.text("entity.pick"), Catalog.Kind.MOB, (kind, entry) -> {
                    ensureObject(path).addProperty("type", entry.id());
                    host.changed();
                })).tooltip(Tooltip.create(DevUi.text("form.pick"))).bounds(x + w - 18, rowTop - 1, 18, CONTROL_H).build());
            } else if (field.equals("uuid")) {
                screen.add(Button.builder(Component.literal("🎯"), b -> DevWorldPicker.pickEntity(screen, DevUi.text("picker.entity_prompt"), entity -> {
                    JsonObject target = ensureObject(path);
                    target.addProperty("uuid", entity.getUUID().toString());
                    // The type goes along, so the file reads well and survives a typo in the uuid.
                    target.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                    host.changed();
                })).tooltip(Tooltip.create(DevUi.text("form.pick_entity"))).bounds(x + w - 18, rowTop - 1, 18, CONTROL_H).build());
            }
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
        if (!row(LABEL_H + CONTROL_H + LABEL_H)) {
            return;
        }
        boolean objective = kind.equals("objective");
        JsonObject quest = host.root();
        JsonObject stageObject = objectAt(path.subList(0, 2));
        String stage = stageObject != null && stageObject.get("id") instanceof JsonPrimitive id ? id.getAsString() : "";
        JsonObject own = objectAt(path);
        boolean named = own != null && own.get("id") instanceof JsonPrimitive;
        String current = objective ? (named ? own.get("id").getAsString() : String.valueOf(path.get(3))) : stage;
        labelRow(DevUi.text(objective ? "rename.objective" : "rename.stage"));
        int rowTop = rowY(LABEL_H + CONTROL_H + LABEL_H) + LABEL_H;
        String key = path.toString();
        EditBox box = new EditBox(screen.font(), x, rowTop + 1, w - 22, CONTROL_H - 2, DevUi.text(objective ? "rename.objective" : "rename.stage"));
        box.setMaxLength(64);
        box.setValue(insideNames.getOrDefault(key, objective && !named ? "" : current));
        if (objective && !named) {
            box.setHint(DevUi.text("rename.by_position", current));
        }
        // Why the typed name cannot be, under the box; replaced in place as it is typed (the form is not rebuilt).
        int reason = labels.size();
        int reasonY = rowTop + CONTROL_H + 1;
        labels.add(new Label(Component.empty(), x, reasonY, DevUi.ERROR));
        Button apply = Button.builder(Component.literal("✓"), b -> {
            String to = insideNames.getOrDefault(key, "").trim();
            if (problem(quest, objective, stage, current, to).isEmpty()) {
                insideNames.remove(key);
                host.renameInQuest(stage, objective ? current : "", to);
            }
        }).tooltip(Tooltip.create(DevUi.text(objective ? "rename.objective_apply" : "rename.stage_apply"))).bounds(x + w - 20, rowTop, 20, CONTROL_H).build();
        java.util.function.Consumer<String> check = text -> {
            Optional<StoryRenames.Problem> problem = problem(quest, objective, stage, current, text);
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
        box.setTooltip(Tooltip.create(DevUi.text(objective ? "rename.objective_tooltip" : "rename.stage_tooltip", current)));
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
        if (entityListItems(node) != null) {
            entityListField(path, name, node, value, required);
            return;
        }
        if (name.equals("id") && host.quest() != null && insideQuest(path) != null) {
            insideNameField(path, insideQuest(path));
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
        switch (kind) {
            case TEXT -> textField(path, name, label, tooltip);
            case ENUM -> enumField(path, name, node, required, value, label, tooltip);
            case BOOLEAN -> booleanField(path, name, node, value, needsKubeJs(path, name) ? DevUi.text("form.needs_kubejs", label) : label, tooltip);
            case ID, ID_LIST, STRING, INTEGER, NUMBER, LITERAL, POSITION, RAW -> inputField(path, name, node, kind, value, label, tooltip, owner);
            default -> {
                if (row(CONTROL_H)) {
                    Component summary = Component.empty().append(label).append(": ").append(summary(kind, node, value)).append(" ▸");
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
                            .bounds(x, rowY(CONTROL_H), w, CONTROL_H).build();
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

    private void textField(List<Object> path, String name, Component label, @Nullable Tooltip tooltip) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        JsonObject object = objectAt(path);
        EditBox box = new EditBox(screen.font(), x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H - 2, label);
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
                ? DevUi.text("form.unset", fallback != null ? valueLabel(fallback.getAsString()) : DevUi.text("none"))
                : valueLabel(current);
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
        if (fallback != null) {
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
        screen.add(Button.builder(Component.literal("+ ").append(DevUi.text("form.add")), b -> {
            if (entities) {
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
                if (Schemas.required(items).contains("id") && !element.has("id")) {
                    element.addProperty("id", freeId(list, field));
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
                screen.add(Button.builder(Component.literal(key + " ▸"), b -> push(with(path, key), values, key, false))
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
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("form.variant"));
            screen.add(Button.builder(variantLabel(variants.get(current)), b -> {
                Node next = variants.get((current + 1) % variants.size());
                put(ensureParent(path), path, path.size() - 1, Schemas.skeleton(next));
                host.changed();
            }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
        }
        objectPage(path, variants.get(current), Set.of(), null);
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
        return Schemas.properties(owner).containsKey("show_ring") ? WAYPOINT : null;
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

    private static Component valueLabel(String value) {
        String key = "storybound.dev.value." + value;
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(value);
    }

    private Component summary(Kind kind, Node node, @Nullable JsonElement value) {
        int count = value == null ? 0 : value.isJsonArray() ? value.getAsJsonArray().size() : value.isJsonObject() ? 1 : 0;
        return switch (kind) {
            case CONDITIONS, ACTIONS, LIST -> count == 0 ? Component.literal("—") : Component.literal(String.valueOf(count));
            case MAP -> Component.literal(value != null && value.isJsonObject() ? String.valueOf(value.getAsJsonObject().size()) : "—");
            case TRIGGER, CONDITION, ACTION -> {
                Logic logic = kind == Kind.TRIGGER ? Logic.TRIGGER : kind == Kind.CONDITION ? Logic.CONDITION : Logic.ACTION;
                yield value != null && value.isJsonObject() && value.getAsJsonObject().has("type")
                        ? Component.literal(typeName(logic, value.getAsJsonObject().get("type").getAsString())) : Component.literal("—");
            }
            case VARIANTS -> value != null ? variantLabel(Schemas.variants(node).get(Schemas.variantOf(node, value))) : Component.literal("—");
            default -> value != null && value.isJsonObject() && !value.getAsJsonObject().isEmpty()
                    ? Component.literal(String.valueOf(value.getAsJsonObject().size())) : Component.literal("—");
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
        if ((kind == Kind.CONDITION || kind == Kind.ACTION || kind == Kind.TRIGGER) && object.has("type")) {
            Logic logic = kind == Kind.CONDITION ? Logic.CONDITION : kind == Kind.ACTION ? Logic.ACTION : Logic.TRIGGER;
            String main = mainValue(object);
            return typeName(logic, object.get("type").getAsString()) + (main.isEmpty() ? "" : ": " + main);
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

    /** What a field can be picked from. */
    private record Picker(@Nullable Catalog.Kind kind, @Nullable ResourceLocation storyType, List<String> choices, Component title) {}

    private @Nullable Picker picker(String field, Node owner, List<Object> path) {
        if (WAYPOINT.equals(context(owner))) {
            List<String> choices = switch (field) {
                case "stage" -> stagesOf(objectAt(path));
                default -> List.of();
            };
            if (!choices.isEmpty()) {
                return new Picker(null, null, choices, fieldLabel(field, false, owner));
            }
        }
        return picker(field, owner);
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
        ResourceLocation story = switch (field) {
            case "quest" -> ClientDevStore.TYPE_QUEST;
            case "dialogue", "busy_dialogue" -> ClientDevStore.TYPE_DIALOGUE;
            case "document" -> ClientDevStore.TYPE_DOCUMENT;
            case "boss" -> ClientDevStore.TYPE_BOSS;
            case "entry" -> ClientDevStore.TYPE_CODEX;
            case "waypoint" -> ClientDevStore.TYPE_WAYPOINT;
            case "key" -> ClientDevStore.TYPE_FLAG;
            default -> null;
        };
        if (story != null) {
            return new Picker(null, story, List.of(), DevUi.text("form.pick_title.story", fieldLabel(field.equals("key") ? "flag" : field, false)));
        }
        List<String> choices = host.choices(field);
        return choices.isEmpty() ? null : new Picker(null, null, choices, fieldLabel(field, false));
    }

    private void pick(Picker picker, Consumer<Entry> picked) {
        if (picker.kind() != null) {
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
                picker.choices().forEach(choice -> entries.add(new Entry(choice, choice, new ItemStack(Items.PAPER))));
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
