package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.Catalog.Entry;
import dev.dzenthai.storybound.client.dev.Schemas.Kind;
import dev.dzenthai.storybound.client.dev.Schemas.Logic;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryRenames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * The form's pickers ({@link FormView}): lists to choose an item, a mob, a place or a story node from, and the
 * ready-made lines of the logic.
 * <p>
 * One layer of the form, split by topic, each seeing the ones below it; the rest of the editor uses FormView:
 * FormCore &larr; FormPickers &larr; FormInputs &larr; FormEntities &larr; FormNpc &larr; FormWaypoints &larr; FormDialogues &larr; FormStory &larr; FormView.
 */
abstract class FormPickers extends FormCore {
    FormPickers(Host host) {
        super(host);
    }

    /** The held stack's count and components go to the fields next to the item, when the object has them. */
    static void held(JsonObject target, Node owner, ItemStack stack) {
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

    // --- type and value names

    /**
     * The list of types to add; ready-made lines first ({@link #quick}). {@code picked} gets the new element: just its
     * type, or a ready-made one filled in.
     */
    void pickType(Logic logic, Consumer<JsonObject> picked) {
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

    static net.minecraft.world.item.Item icon(Logic logic) {
        return switch (logic) {
            case CONDITION -> Items.COMPARATOR;
            case ACTION -> Items.REDSTONE_TORCH;
            case TRIGGER -> Items.TRIPWIRE_HOOK;
        };
    }

    // --- pickers

    /**
     * What a field can be picked from: a catalog, the story's nodes of a kind, plain choices or prepared rows.
     *
     * @param empty what to say when there is nothing to pick (a stage before the quest is chosen); null: no button then
     */
    record Picker(@Nullable Catalog.Kind kind, @Nullable ResourceLocation storyType, List<String> choices, Component title,
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

    @Nullable Picker picker(String field, Node owner, List<Object> path) {
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
        if (field.equals("structure") && properties.containsKey("dimension") && properties.containsKey("search_radius")) {
            // A waypoint's structure: those of its dimension, picked first (November 9, 2026).
            ResourceLocation dimension = object != null && object.get("dimension") instanceof JsonPrimitive written
                    ? ResourceLocation.tryParse(written.getAsString()) : null;
            ResourceLocation in = dimension != null ? dimension : ResourceLocation.withDefaultNamespace("overworld");
            return Picker.rows(Catalog.structuresIn(in), DevUi.text("form.pick_title.structure"), DevUi.text("waypoint.no_structures", in.toString()));
        }
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

    @Nullable Picker picker(String field, Node owner) {
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
            case "dialogue" -> ClientDevStore.TYPE_DIALOGUE;
            case "document" -> ClientDevStore.TYPE_DOCUMENT;
            case "boss" -> ClientDevStore.TYPE_BOSS;
            case "phrases", "warning", "busy" -> ClientDevStore.TYPE_PHRASES;
            case "entry" -> ClientDevStore.TYPE_CODEX;
            case "waypoint" -> ClientDevStore.TYPE_WAYPOINT;
            case "key", "variant_by" -> ClientDevStore.TYPE_FLAG;
            case "npc" -> Schemas.properties(owner).containsKey("range") ? ClientDevStore.TYPE_NPC : null;
            default -> null;
        };
        if (story != null) {
            return new Picker(null, story, List.of(), DevUi.text("form.pick_title.story", fieldLabel(field.equals("key") ? "flag" : field, false)));
        }
        List<String> choices = host.choices(field);
        return choices.isEmpty() ? null : new Picker(null, null, choices, fieldLabel(field, false));
    }

    /** Whether the list holds item stacks (a quest's or a boss's rewards): "+ Add" opens the items at once. */
    static boolean stackList(String field, @Nullable Node items) {
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
    static JsonElement stack(Node items, Entry entry) {
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

    void pick(Picker picker, Consumer<Entry> picked) {
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
}
