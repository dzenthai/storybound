package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.Catalog.Entry;
import dev.dzenthai.storybound.client.dev.Schemas.Kind;
import dev.dzenthai.storybound.client.dev.Schemas.Logic;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryRenames;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * The base of the form ({@link FormView}): what it edits, the frames it shows,
 * the scroll, the JSON paths, the rows
 * and labels a page is laid out with, and the words fields and values are named
 * with.
 * <p>
 * One layer of the form, split by topic, each seeing the ones below it; the
 * rest of the editor uses FormView:
 * FormCore &larr; FormPickers &larr; FormInputs &larr; FormEntities &larr;
 * FormNpc &larr; FormWaypoints &larr; FormDialogues &larr; FormStory &larr;
 * FormView.
 */
abstract class FormCore {
    static final int LABEL_H = 10;
    static final int CONTROL_H = 18;
    private static final int ROW_GAP = 4;
    /** The radius a waypoint gets when it becomes a search area in the form. */
    static final int NEW_AREA_RADIUS = 16;
    static final String WAYPOINT = "waypoint";
    static final String CHATTER = "chatter";

    /** What the form edits and how its changes are kept. */
    interface Host {
        /** The object edited now; read again on every build, as saving replaces it. */
        JsonObject root();

        /**
         * The translation key of a text field at that path ({@code stages.hunt.}) of
         * the object.
         */
        String textKey(String path, String field);

        /**
         * A text box changed the JSON: saved later (Enter, another selection, closing).
         */
        void edited();

        /** A button changed the JSON: saved now. */
        void changed();

        /**
         * The NPC binding the form edits, for the fields that belong to the NPC rather
         * than its file; null otherwise.
         */
        default @Nullable ResourceLocation npcBinding() {
            return null;
        }

        /**
         * The dialogue whose node the form edits (its presentation and camera decide
         * what a line shows); null otherwise.
         */
        default @Nullable JsonObject dialogue() {
            return null;
        }

        /**
         * Shows the dialogue's node on its graph, selected (an arrow's target, clicked
         * in the form).
         */
        default void revealNode(String node) {
        }

        /**
         * The quest the form edits, for its stages' and objectives' names; null
         * otherwise.
         */
        default @Nullable ResourceLocation quest() {
            return null;
        }

        /**
         * Renames a stage of the quest ({@code objective} empty) or an objective of a
         * stage, with what names it.
         */
        default void renameInQuest(String stage, String objective, String to) {
        }

        /**
         * Renames a section of the Codex entry the form edits, with its text and what
         * names it.
         */
        default void renameCodexSection(String from, String to) {
        }

        /**
         * The story node the form edits (a boss's and a Codex entry's long texts are
         * named after it); null otherwise.
         */
        default @Nullable StoryLinks.NodeKey node() {
            return null;
        }

        /**
         * Values for a field that names something inside the definition (a dialogue's
         * nodes, its speakers); usually none.
         */
        default List<String> choices(String field) {
            return List.of();
        }
    }

    /**
     * A page of the form: a nested object, list or logic element.
     *
     * @param removable an optional field: the page offers to remove it
     */
    record Frame(List<Object> path, Node schema, String label, boolean removable) {
    }

    /**
     * A line of text on the form: a label (cut to the width) or a line of wrapped
     * help.
     */
    record Label(@Nullable Component text, @Nullable FormattedCharSequence line, int x, int y, int color) {
        Label(Component text, int x, int y, int color) {
            this(text, null, x, y, color);
        }
    }

    final Host host;
    private @Nullable Node rootSchema;
    Set<String> hidden = Set.of();
    private String rootLabel = "";
    final Deque<Frame> frames = new ArrayDeque<>();
    final List<Label> labels = new ArrayList<>();
    /**
     * The rows' shift as laid out now: the glide's position, rounded (see
     * {@link #animateScroll}).
     */
    int scroll;
    int contentHeight;
    /** Where the wheel sends the form, and where its glide there is. */
    private double scrollTarget;
    double scrollShown;
    private long scrollFrame;
    /**
     * The scroll bar, when the rows do not fit: the area it runs along (set when
     * laid out).
     */
    int barX = -1;
    int barTop;
    int barBottom;

    // While building: where the next row goes and what is visible.
    DevModeScreen screen;
    int x;
    int w;
    int y;
    int top;
    int bottom;

    FormCore(Host host) {
        this.host = host;
    }

    /** Shows another object: back to its first page. */
    void reset(@Nullable Node schema, String label, Set<String> hiddenFields) {
        rootSchema = schema;
        rootLabel = label;
        hidden = hiddenFields;
        frames.clear();
        resetScroll();
        // A switch's way chosen for a field not written yet belongs to this node, not
        // to the next one shown at the same
        // path.
        entityModes.clear();
        switchedAway.clear();
    }

    /**
     * The wheel moves where the form goes; the rows glide there: see
     * {@link #animateScroll}.
     */
    void scroll(double amount) {
        scrollTarget = Math.max(0, scrollTarget - amount * 28);
    }

    /** Back to the top at once: another node, another page. */
    void resetScroll() {
        scroll = 0;
        scrollTarget = 0;
        scrollShown = 0;
    }

    /**
     * One frame of the glide to where the wheel sent the form, about a seventh of a
     * second, frame-rate independent;
     * whether the rows moved by a pixel, so the form must be laid out again.
     */
    boolean animateScroll() {
        long now = System.nanoTime();
        double seconds = scrollFrame == 0 ? 0 : Math.min(0.1, (now - scrollFrame) / 1e9);
        scrollFrame = now;
        if (scrollShown == scrollTarget) {
            return false;
        }
        scrollShown += (scrollTarget - scrollShown) * (1 - Math.exp(-seconds * 20));
        if (Math.abs(scrollTarget - scrollShown) < 0.5) {
            scrollShown = scrollTarget;
        }
        int next = (int) Math.round(scrollShown);
        if (next == scroll) {
            return false;
        }
        scroll = next;
        return true;
    }

    // --- JSON access by path, so pages survive the object being replaced by a
    // fresh copy after saving

    @Nullable
    JsonElement get(List<Object> path) {
        JsonElement current = host.root();
        for (Object step : path) {
            if (step instanceof String key) {
                current = current.isJsonObject() ? current.getAsJsonObject().get(key) : null;
            } else {
                int index = (Integer) step;
                // One thing written alone where a list may stand (an action, an NPC binding's
                // entity) is its first element.
                current = current.isJsonArray() && index < current.getAsJsonArray().size()
                        ? current.getAsJsonArray().get(index)
                        : current.isJsonObject() && index == 0 ? current : null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @Nullable
    JsonObject objectAt(List<Object> path) {
        JsonElement element = get(path);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /**
     * The object at the path, made (with the containers above it) when missing. A
     * step into a list keeps the list;
     * a single object where a list is expected (one action written alone) becomes
     * its first element.
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

    /**
     * The array at the path, made when missing; a single object (one action written
     * alone) becomes its element.
     */
    JsonArray ensureArray(List<Object> path) {
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
     * What holds the element at the path: the list it is an element of, or the
     * object it is a field of, made when
     * missing. Never the object in place of a list, so replacing one action of a
     * list keeps the others.
     */
    JsonElement ensureParent(List<Object> path) {
        List<Object> parent = path.subList(0, path.size() - 1);
        if (parent.isEmpty()) {
            return host.root();
        }
        return path.getLast() instanceof Integer ? ensureArray(parent) : ensureObject(parent);
    }

    static @Nullable JsonElement child(JsonElement parent, List<Object> path, int i) {
        Object step = path.get(i);
        if (step instanceof String key) {
            return parent.isJsonObject() ? parent.getAsJsonObject().get(key) : null;
        }
        int index = (Integer) step;
        return parent.isJsonArray() && index < parent.getAsJsonArray().size() ? parent.getAsJsonArray().get(index)
                : null;
    }

    static void put(JsonElement parent, List<Object> path, int i, JsonElement value) {
        Object step = path.get(i);
        if (step instanceof String key) {
            parent.getAsJsonObject().add(key, value);
        } else if ((Integer) step < parent.getAsJsonArray().size()) {
            parent.getAsJsonArray().set((Integer) step, value);
        } else {
            parent.getAsJsonArray().add(value);
        }
    }

    static List<Object> with(List<Object> path, Object step) {
        List<Object> longer = new ArrayList<>(path);
        longer.add(step);
        return List.copyOf(longer);
    }

    /**
     * {@code stages.hunt.objectives.wolves.}: list elements by their id when they
     * have one, for stable text keys.
     */
    /**
     * The key of a list's texts, before the item's index: the list's path already
     * ends with its field, named once
     * ("npc_50.names.0"; keys already written stay as they are).
     */
    String listKey(List<Object> path, String field) {
        boolean named = !path.isEmpty() && field.equals(path.getLast());
        return host.textKey(keyPath(named ? path.subList(0, path.size() - 1) : path), field);
    }

    String keyPath(List<Object> path) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            Object step = path.get(i);
            if (step instanceof Integer index) {
                JsonObject element = objectAt(path.subList(0, i + 1));
                out.append(element != null && element.has("id") && element.get("id").isJsonPrimitive()
                        ? element.get("id").getAsString()
                        : index);
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
                resetScroll();
                screen.rebuild();
            }).tooltip(Tooltip.create(DevUi.text("form.back"))).bounds(x, y0, 16, 14).build());
            if (frame.removable()) {
                screen.add(Button.builder(Component.literal("✕"), b -> remove(frame))
                        .tooltip(Tooltip.create(DevUi.text("form.remove_field"))).bounds(x + width - 16, y0, 16, 14)
                        .build());
            }
            // Where the page is (greyed), then what it is, as a heading: every page goes on
            // under one, as the node's
            // form under its name.
            labels.add(new Label(Component.literal(crumbs(screen.font(), width - 40)), x + 20, y0 + 3, DevUi.MUTED));
            labels.add(new Label(Component.literal(screen.font().plainSubstrByWidth(frame.label(), width)), x, y0 + 18,
                    DevUi.ACCENT));
            y0 += 32;
        }
        this.top = y0;
        this.bottom = bottom;
        int most = Math.max(0, contentHeight - (bottom - y0));
        scroll = Math.max(0, Math.min(scroll, most));
        scrollTarget = Math.min(scrollTarget, most);
        scrollShown = Math.min(scrollShown, most);
        this.y = y0 - scroll;
        if (frames.isEmpty()) {
            objectPage(List.of(), rootSchema, hidden, null);
        } else {
            Frame frame = frames.peek();
            page(frame.path(), frame.schema());
        }
        contentHeight = y + scroll - y0;
        // A thin bar along the form's right edge when its rows do not fit.
        barX = contentHeight > bottom - y0 ? x + width + 3 : -1;
        barTop = y0;
        barBottom = bottom;
    }

    /**
     * The way to the page from the node: the node and the pages above this one (its
     * own name is its heading).
     */
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

    /**
     * Opens a field of the node on its own page at once: a new node's first
     * question ("Who is this NPC?").
     */
    void openRoot(String name, String label) {
        Node node = rootSchema == null ? null : Schemas.properties(rootSchema).get(name);
        if (node == null) {
            return;
        }
        Node items = entityListItems(node);
        frames.clear();
        frames.push(new Frame(List.of(name), items != null ? items : node, label, false));
        resetScroll();
    }

    /**
     * Lists the triggers at once for the node's trigger: a new story event's first
     * question.
     */
    void askTrigger(String name) {
        if (screen == null) {
            return;
        }
        pickType(Logic.TRIGGER, element -> {
            host.root().add(name, element);
            host.changed();
        });
    }

    void push(List<Object> path, Node schema, String label, boolean removable) {
        frames.push(new Frame(path, schema, label, removable));
        resetScroll();
        screen.rebuild();
    }

    void remove(Frame frame) {
        List<Object> path = frame.path();
        JsonElement parent = get(path.subList(0, path.size() - 1));
        if (parent != null && parent.isJsonObject() && path.getLast() instanceof String key) {
            parent.getAsJsonObject().remove(key);
        }
        frames.pop();
        resetScroll();
        host.changed();
    }

    /**
     * Is the next row of that height inside the visible part? Moves past it either
     * way.
     */
    boolean row(int height) {
        boolean visible = y >= top && y + height <= bottom;
        y += height + ROW_GAP;
        return visible;
    }

    int rowY(int height) {
        return y - height - ROW_GAP;
    }

    void text(Component text, int color) {
        for (FormattedCharSequence line : screen.font().split(text, w)) {
            if (y >= top && y + LABEL_H <= bottom) {
                labels.add(new Label(null, line, x, y, color));
            }
            y += LABEL_H;
        }
        y += 2;
    }

    static String tail(String uuid) {
        return uuid.length() > 4 ? uuid.substring(uuid.length() - 4) : uuid;
    }

    /** How long the "forget the one picked" button waits for its second click. */
    static final long RESET_CONFIRM_MILLIS = 4000;
    /** Until when the button asks "Sure?"; 0 when it does not. */
    long resetArmedUntil;

    /**
     * The choice "Other value..." in a field's list, and the fields being typed
     * into ("selector path#field path").
     */
    static final String OTHER_VALUE = "\u0000other";
    /**
     * A value written that no mob met has, in a field's list: this prefix and its
     * place among the values written.
     */
    static final String WRITTEN_VALUE = "\u0000written:";
    final Set<String> typingOther = new java.util.HashSet<>();

    static boolean flag(@Nullable JsonObject object, String name, boolean byDefault) {
        return object != null && object.get(name) instanceof JsonPrimitive value && value.isBoolean()
                ? value.getAsBoolean()
                : byDefault;
    }

    static List<String> stringsOf(@Nullable JsonElement value) {
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

    static String capitalized(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + text.substring(1);
    }

    static JsonArray asList(JsonPrimitive one) {
        JsonArray list = new JsonArray();
        list.add(one);
        return list;
    }

    /**
     * The properties with {@code first} in front, in that order; the others after,
     * as the schema has them.
     */
    static List<Map.Entry<String, Node>> ordered(Map<String, Node> properties, List<String> first) {
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
     * A choice from a list in words where it has some: "@end" is "the end of the
     * dialogue"; a style or a section by its name.
     */
    static String choiceLabel(String choice) {
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
        return id != null && Language.getInstance().has(questSection)
                ? Language.getInstance().getOrDefault(questSection) + " (" + choice + ")"
                : choice;
    }

    /** The ✕ at a row's right end, which takes out what the row shows. */
    Button removeButton(int top, Component tooltip, Button.OnPress action) {
        return Button.builder(Component.literal("✕"), action).tooltip(Tooltip.create(tooltip))
                .bounds(x + w - 18, top, 18, CONTROL_H).build();
    }

    /** A heading over a group of fields. */
    void group(Component title) {
        y += 4;
        text(title, DevUi.ACCENT);
    }

    /**
     * A lighter heading within a group, over the fields of one question: golden
     * sections, light questions in them, grey
     * labels over each field.
     */
    void subgroup(Component title) {
        y += 2;
        text(title, DevUi.TEXT);
    }

    static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    static boolean isEntitySelector(Node node) {
        return "entity_selector".equals(Schemas.resolve(node).ref());
    }

    /**
     * The selector of a field written as one entity selector or a list of them (an
     * NPC binding's); null for others.
     */
    static @Nullable Node entityListItems(Node node) {
        List<Node> variants = Schemas.variants(node);
        if (variants.size() != 2 || !isEntitySelector(variants.get(0))) {
            return null;
        }
        Node items = Schemas.items(variants.get(1));
        return items != null && isEntitySelector(items) ? variants.get(0) : null;
    }

    /**
     * "Villager · tag demo_elder", "Cat · name «Murka»", "entity …a1b2"; a dash when
     * nothing is set.
     */
    static String entitySummary(@Nullable JsonObject selector) {
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
                if (id.startsWith("#")) {
                    // As the list names it ("Raiders"), else made readable.
                    String listed = Catalog.label(Catalog.Kind.MOB, id);
                    ResourceLocation tag = ResourceLocation.tryParse(id.substring(1));
                    names.add(listed != null ? listed : tag != null ? DevUi.humanize(tag.getPath()) : id);
                    continue;
                }
                names.add(key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key)
                        ? BuiltInRegistries.ENTITY_TYPE.get(key).getDescription().getString()
                        : id);
            }
            parts.add(String.join(", ", names));
        }
        if (selector.has("tag")) {
            parts.add(DevUi.str("entity.summary.tag", selector.get("tag").getAsString()));
        }
        return String.join(" · ", parts);
    }

    /**
     * The way an entity is known by, chosen on its page before its field is filled
     * (per path; the form's own state).
     */
    final Map<String, String> entityModes = new java.util.HashMap<>();
    /**
     * What a switch cleared, per path and field, given back when it is switched to
     * that way again. For this node only.
     */
    final Map<String, JsonElement> switchedAway = new java.util.HashMap<>();

    /** Clears the field, keeping its value to give back. */
    void putAway(List<Object> path, JsonObject target, String field) {
        JsonElement value = target.remove(field);
        if (value != null) {
            switchedAway.put(path + "#" + field, value);
        }
    }

    /** Gives back what a switch cleared from the field, if the field is empty. */
    void giveBack(List<Object> path, JsonObject target, String field) {
        JsonElement value = switchedAway.remove(path + "#" + field);
        if (value != null && !target.has(field)) {
            target.add(field, value);
        }
    }

    /**
     * "stage" for a quest stage's page ({@code stages/<i>}), "objective" for an
     * objective's, null elsewhere.
     */
    static @Nullable String insideQuest(List<Object> path) {
        boolean stage = path.size() >= 2 && "stages".equals(path.get(0)) && path.get(1) instanceof Integer;
        if (stage && path.size() == 2) {
            return "stage";
        }
        return stage && path.size() == 4 && "objectives".equals(path.get(2)) && path.get(3) instanceof Integer
                ? "objective"
                : null;
    }

    /**
     * The names being typed for stages and objectives, by path, so rebuilding the
     * form loses nothing.
     */
    final Map<String, String> insideNames = new java.util.HashMap<>();

    static Optional<StoryRenames.Problem> problem(JsonObject quest, boolean objective, String stage, String current,
            String typed) {
        return objective ? StoryRenames.objectiveProblem(quest, stage, current, typed)
                : StoryRenames.stageProblem(quest, current, typed);
    }

    void labelRow(Component label) {
        labels.add(new Label(label, x, rowY(LABEL_H + CONTROL_H), DevUi.LABEL));
    }

    /**
     * The choice after the current one. Unset counts as its default: the server
     * saves definitions in canonical form,
     * which leaves a default value out, so writing the default would come back
     * unset and the button would never get
     * past it (a dialogue's presentation stuck on "screen").
     *
     * @param values  the choices, "" (unset) first when the field is optional
     * @param current the value written, "" when unset
     */
    static String nextChoice(List<String> values, String current, @Nullable String byDefault) {
        String effective = current.isEmpty() && byDefault != null && values.contains(byDefault) ? byDefault : current;
        return values.get((values.indexOf(effective) + 1) % values.size());
    }

    static Component variantLabel(Node variant) {
        Set<String> required = Schemas.required(variant);
        return required.isEmpty() ? Component.literal("?") : fieldLabel(required.iterator().next(), false);
    }

    static String typeName(Logic logic, String type) {
        String shortType = DevUi.shortId(type);
        String key = "storybound.dev.type." + logic.name + "." + shortType;
        if (Language.getInstance().has(key)) {
            return Component.translatable(key).getString();
        }
        ResourceLocation id = ResourceLocation.tryParse(type);
        return id != null && !id.getNamespace().equals(Storybound.MOD_ID) && type.contains(":")
                ? DevUi.humanize(id.getPath()) + " (" + id.getNamespace() + ")"
                : DevUi.humanize(shortType);
    }

    /**
     * A schema description in the game's language (see {@link Schemas#localized}).
     */
    static String described(String english) {
        return Schemas.localized(english, Minecraft.getInstance().getLanguageManager().getSelected());
    }

    static String typeHelp(Logic logic, String type, Node variant) {
        String key = "storybound.dev.help." + logic.name + "." + DevUi.shortId(type);
        return Language.getInstance().has(key) ? Component.translatable(key).getString()
                : described(variant.description());
    }

    /**
     * A field's label where the form says more than the field's own name:
     * {@code storybound.dev.field.<context>.<name>}
     * (on a waypoint, "conditions" is "show only if"), else the field's own.
     */
    static Component fieldLabel(String name, boolean required, Node owner) {
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

    /**
     * Which form this is, where it needs its own words: a waypoint (or
     * add_waypoint), or null.
     */
    static @Nullable String context(Node owner) {
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
        if (properties.containsKey("started") && properties.containsKey("completed")
                && properties.containsKey("failed")) {
            return "screen_text";
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
        if (properties.containsKey("variants") && properties.containsKey("min_interval")) {
            return CHATTER;
        }
        if (properties.containsKey("home_radius") && properties.containsKey("movement")) {
            return "npc";
        }
        if (properties.containsKey("teleport_distance") && properties.containsKey("distance")) {
            return "follow";
        }
        // A story event's cooldown is a wait between runs, not a quest's "Cooldown, s"; its scope is whose wait it is.
        if (properties.containsKey("on_fail") && properties.containsKey("cooldown")) {
            return "event";
        }
        if (properties.size() == 2 && properties.containsKey("seconds") && properties.containsKey("scope")) {
            return "cooldown";
        }
        // The story's settings and their difficulty scale: "style" is how quests show
        // it, not a document's look.
        if (properties.containsKey("track_given_quests")) {
            return "project";
        }
        if (properties.containsKey("style") && properties.containsKey("levels")) {
            return "difficulty";
        }
        if (properties.containsKey("from") && properties.containsKey("speed")) {
            return "camera";
        }
        // A dialogue node's own words, by its type:
        // "storybound.dev.field.node.call.next".
        Node type = properties.get("type");
        JsonObject typeJson = type != null ? Schemas.resolve(type).json() : null;
        return typeJson != null && typeJson.get("const") instanceof JsonPrimitive constant
                && owner.file().startsWith("dialogue") ? "node." + constant.getAsString() : null;
    }

    /**
     * The stages of the quest the form names, for a waypoint's {@code stage}; none
     * until a quest is chosen.
     */
    static List<String> stagesOf(@Nullable JsonObject object) {
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
        Component label = Language.getInstance().has(key) ? Component.translatable(key)
                : Component.literal(DevUi.humanize(name));
        return required ? Component.empty().append(label).append(" *") : label;
    }

    /**
     * A value in the words of its field where it has some ("scope": "each player's
     * own"), else in general ones.
     */
    static Component enumLabel(String field, String value) {
        String key = "storybound.dev.value." + field + "." + value;
        return Language.getInstance().has(key) ? Component.translatable(key) : valueLabel(value);
    }

    static Component valueLabel(String value) {
        String key = "storybound.dev.value." + value;
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(value);
    }

    /**
     * A part's value in words, for the button under its label: a list by its
     * elements ("Quest: mypack:hunt · Flag set"),
     * a map by its keys, an object by how many of its fields are set; "None" when
     * empty.
     */
    Component summary(Kind kind, Node node, @Nullable JsonElement value) {
        return switch (kind) {
            case CONDITIONS, ACTIONS, LIST -> {
                Node items = kind == Kind.CONDITIONS ? Schemas.logic("condition")
                        : kind == Kind.ACTIONS ? Schemas.logic("action") : Schemas.items(node);
                List<JsonElement> elements = value == null ? List.of()
                        : value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
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
                    ? Component.literal(String.join(" · ", value.getAsJsonObject().keySet()))
                    : DevUi.text("none");
            case TRIGGER, CONDITION, ACTION -> {
                Logic logic = kind == Kind.TRIGGER ? Logic.TRIGGER
                        : kind == Kind.CONDITION ? Logic.CONDITION : Logic.ACTION;
                yield value != null && value.isJsonObject() && value.getAsJsonObject().has("type")
                        ? Component.literal(typeName(logic, value.getAsJsonObject().get("type").getAsString()))
                        : DevUi.text("none");
            }
            case VARIANTS -> value != null ? variantLabel(Schemas.variants(node).get(Schemas.variantOf(node, value)))
                    : DevUi.text("none");
            default -> value != null && value.isJsonObject() && !value.getAsJsonObject().isEmpty()
                    ? DevUi.text("form.fields_set", value.getAsJsonObject().size())
                    : DevUi.text("none");
        };
    }

    /**
     * A list element in one line: a logic element by its type and main value, an
     * object by its id or text.
     */
    static String itemSummary(JsonElement element, Node items, int index) {
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
        if (Schemas.properties(items).containsKey("count")
                && (object.get("item") instanceof JsonPrimitive || object.get("id") instanceof JsonPrimitive)) {
            String item = (object.get("item") instanceof JsonPrimitive named ? named
                    : object.get("id").getAsJsonPrimitive()).getAsString();
            ResourceLocation id = ResourceLocation.tryParse(item);
            String name = id != null && BuiltInRegistries.ITEM.containsKey(id)
                    ? BuiltInRegistries.ITEM.get(id).getDescription().getString()
                    : item;
            int count = object.get("count") instanceof JsonPrimitive written && written.isNumber() ? written.getAsInt()
                    : 1;
            return count > 1 ? name + " × " + count : name;
        }
        if (Schemas.properties(items).containsKey("unlock") && object.get("id") instanceof JsonPrimitive id) {
            String title = ClientDevStore.readStoryText(object, "title", "");
            return title.isBlank() ? id.getAsString() : title + " (" + id.getAsString() + ")";
        }
        if (object.has("id") && object.get("id").isJsonPrimitive()) {
            return object.get("id").getAsString();
        }
        // A click entry or a chatter variant of phrases: the node, or its own text in
        // quotes.
        if (Schemas.properties(items).containsKey("priority") && object.has("phrases")) {
            JsonElement phrases = object.get("phrases");
            if (phrases instanceof JsonPrimitive node) {
                return "🗨 " + DevUi.shortId(node.getAsString());
            }
            if (phrases instanceof JsonObject own) {
                return "«" + ClientDevStore.readStoryText(own, "text", "") + "»";
            }
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

    // Drawn by FormView, which knows every kind of page and field; the layers below
    // call them.

    abstract void page(List<Object> path, Node schema);

    abstract void objectPage(List<Object> path, Node schema, Set<String> skip, @Nullable String typeField);

    abstract void field(List<Object> path, String name, Node node, boolean required, Node owner);

    /** Implemented by {@link FormPickers}. */
    abstract void pickType(Logic logic, Consumer<JsonObject> picked);
}
