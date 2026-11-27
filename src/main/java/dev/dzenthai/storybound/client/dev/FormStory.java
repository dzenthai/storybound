package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.Schemas.Logic;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import dev.dzenthai.storybound.client.document.DocumentTexts;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryRenames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The forms of the story's other kinds ({@link FormView}): quests, the Codex, bosses, documents.
 * <p>
 * One layer of the form, split by topic, each seeing the ones below it; the rest of the editor uses FormView:
 * FormCore &larr; FormPickers &larr; FormInputs &larr; FormEntities &larr; FormNpc &larr; FormWaypoints &larr; FormDialogues &larr; FormStory &larr; FormView.
 */
abstract class FormStory extends FormDialogues {
    FormStory(Host host) {
        super(host);
    }

    /** The forms known by the node they edit rather than by their fields. */
    static final Map<ResourceLocation, String> ROOT_FORMS = Map.of(
            ClientDevStore.TYPE_EVENT, "event",
            ClientDevStore.TYPE_SCRIPT, "script",
            ClientDevStore.TYPE_FLAG, "flag",
            ClientDevStore.TYPE_CHAPTER, "chapter",
            ClientDevStore.TYPE_SCENE, "scene");

    /** A boss's sections, each shown in its form under a heading of its own, with whether the entry has it. */
    static final Set<String> BOSS_PARTS = Set.of("lore", "tactics", "rewards");

    /**
     * A quest's parts in words, for the rows that open them: its stages by name ("find_tracks → kill → report"), its
     * texts per state ("its own: active, completed"), its start ("when: talked to an NPC + conditions"), its rewards
     * ("2 items, 1 of 3 to choose, 50 XP"), an objective's items ("5 × wolf hide, handed in"). Null for other rows.
     */
    static @Nullable String questSummary(String name, Node owner, @Nullable JsonElement value) {
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
     * title and a dropdown of the chosen entry's sections, "The whole entry" first.
     */
    void codexSectionField(List<Object> path, String name, @Nullable JsonElement value, Component label) {
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
     * A boss's section (lore, tactics, rewards) under a heading of its own: whether the entry has it (a section left
     * out is not in the Bosses tab, whatever its text), and while it does its fields here, its text and when it opens
     * first.
     */
    void bossPartField(List<Object> path, String name, Node node, @Nullable JsonObject boss) {
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
    @Nullable ResourceLocation longTextFile(List<Object> path) {
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
    void longTextFileField(List<Object> path, String name, Component label, ResourceLocation textId) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        String current = ClientDevStore.document(textId, ClientDevStore.activeLanguage()).or(() -> DocumentTexts.load(textId, ClientDevStore.activeLanguage())).orElse("");
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
     * A Codex entry's sections as its author thinks of them: the first section is the entry's main description, right
     * here with its title, text and when it opens, under a word that the entry appears in the Codex when any section
     * opens; the others behind a row of their own. An entry without sections offers to add the main description.
     */
    void codexSectionsField(List<Object> path, Node node, @Nullable JsonObject entry) {
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
                on.add(host.root().has("place") ? "visited" : host.root().has("entity") ? "seen" : "manual");
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
    static String sectionsSummary(@Nullable JsonElement value) {
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
    void unlockField(List<Object> path, String name, Node node, @Nullable JsonElement value, Component label, @Nullable Tooltip tooltip) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        JsonObject unlock = value instanceof JsonObject object ? object : null;
        List<String> written = stringsOf(unlock != null ? unlock.get("on") : null);
        List<String> sources = written.isEmpty() ? defaultSources(path) : written;
        StringBuilder words = new StringBuilder(DevUi.str("unlock.when", String.join(DevUi.str("unlock.or"), sources.stream().map(FormStory::sourceName).toList())));
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
        if (host.root().has("place")) {
            return List.of("visited");
        }
        return List.of(host.root().has("entity") ? "seen" : "manual");
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
     * removable, and "+ Read a document…" with the story's documents. The last source cannot go: a section must open
     * somehow.
     */
    void unlockSourcesField(List<Object> path, @Nullable JsonObject unlock, Component label) {
        List<String> written = stringsOf(unlock != null ? unlock.get("on") : null);
        List<String> sources = written.isEmpty() ? defaultSources(path.subList(0, Math.max(0, path.size() - 1))) : written;
        boolean placeEntry = host.root().has("place");
        List<String> switches = placeEntry ? List.of("visited", "manual") : List.of("seen", "killed", "observed", "manual");
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(label);
            int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
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
        if (switches.size() > 2) {
            // The second row of switches.
            row(CONTROL_H);
        }
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
                box.setTextColor(seconds != null ? DevUi.TEXT : DevUi.ERROR);
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
            screen.add(removeButton(rowTop, DevUi.text("form.remove"), b -> {
                List<String> next = new ArrayList<>(sources);
                next.remove(source);
                writeSources(path, next);
            }));
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
     * A Codex entry's group in the Codex tab: a button with it by name and a dropdown of the built-in groups and those
     * other entries have.
     */
    void codexCategoryField(List<Object> path, @Nullable JsonObject entry) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("field.codex.category"));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        String current = entry != null && entry.get("category") instanceof JsonPrimitive written ? shortType(written.getAsString()) : "monsters";
        java.util.Set<String> categories = new java.util.LinkedHashSet<>(
                List.of("monsters", "animals", "characters", "companions", "structures", "biomes", "pois", "places", "lore"));
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

    private static final List<String> CODEX_MODES = List.of("creature", "place", "article");
    private static final Set<String> PLACE_CATEGORIES = Set.of("structures", "biomes", "pois", "places");

    /**
     * Subject switcher for a Codex entry: creature ({@code entity}), world place ({@code place}: structure, biome,
     * POI, dimension or area), or lore article (neither). Switching preserves cleared fields in {@link #switchedAway}
     * and adjusts the default category and initial section unlock sensibly.
     */
    void codexAboutFields(List<Object> path, Node schema, @Nullable JsonObject entry) {
        String modeKey = with(path, "codex_about").toString();
        boolean hasEntity = entry != null && entry.has("entity");
        boolean hasPlace = entry != null && entry.has("place");
        if (hasEntity) {
            entityModes.put(modeKey, "creature");
        } else if (hasPlace) {
            entityModes.put(modeKey, "place");
        }
        String fallback = defaultCodexMode(entry);
        String mode = entityModes.getOrDefault(modeKey, fallback);
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("codex.about"));
            Button switcher = Button.builder(Component.literal(DevUi.str("codex.about." + mode) + " \u25B8"), b -> {
                JsonObject target = ensureObject(path);
                String next = CODEX_MODES.get((CODEX_MODES.indexOf(mode) + 1) % CODEX_MODES.size());
                String currentCategory = target.get("category") instanceof JsonPrimitive cat ? shortType(cat.getAsString()) : "monsters";
                if (next.equals("place")) {
                    putAway(with(path, "creature"), target, "entity");
                    putAway(with(path, "creature"), target, "show_kills");
                    giveBack(with(path, "place"), target, "place");
                    if (!target.has("place")) {
                        target.add("place", new JsonObject());
                    }
                    if (!PLACE_CATEGORIES.contains(currentCategory)) {
                        target.addProperty("category", "structures");
                    }
                    updateInitialCodexUnlock(target, "visited");
                } else if (next.equals("article")) {
                    putAway(with(path, "creature"), target, "entity");
                    putAway(with(path, "creature"), target, "show_kills");
                    putAway(with(path, "place"), target, "place");
                    if (!currentCategory.equals("lore")) {
                        target.addProperty("category", "lore");
                    }
                    updateInitialCodexUnlock(target, "manual");
                } else {
                    putAway(with(path, "place"), target, "place");
                    giveBack(with(path, "creature"), target, "entity");
                    giveBack(with(path, "creature"), target, "show_kills");
                    if (PLACE_CATEGORIES.contains(currentCategory) || currentCategory.equals("lore")) {
                        target.remove("category");
                    }
                    updateInitialCodexUnlock(target, target.has("entity") ? "seen" : "manual");
                }
                entityModes.put(modeKey, next);
                host.changed();
            }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
            switcher.setTooltip(Tooltip.create(DevUi.text("codex.about.tooltip")));
            screen.add(switcher);
        }
        Map<String, Node> props = Schemas.properties(schema);
        if (mode.equals("creature") && props.containsKey("entity")) {
            field(path, "entity", props.get("entity"), false, schema);
        } else if (mode.equals("place") && props.containsKey("place")) {
            if (row(LABEL_H + CONTROL_H)) {
                labelRow(DevUi.text("codex.place_field"));
                JsonObject place = entry != null && entry.get("place") instanceof JsonObject obj ? obj : null;
                String line = codexPlaceSummary(place);
                String clipped = screen.font().plainSubstrByWidth(line, w - 16);
                Component shown = Component.literal(clipped + (screen.font().width(line) > w - 16 ? "…" : "") + " \u25B8");
                Node placeNode = props.get("place");
                Button button = Button.builder(shown, b -> push(with(path, "place"), placeNode, DevUi.str("codex.place_title"), false))
                        .tooltip(Tooltip.create(Component.literal(line)))
                        .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
                screen.add(button);
            }
        }
    }

    private static String defaultCodexMode(@Nullable JsonObject entry) {
        if (entry == null) {
            return "creature";
        }
        String category = entry.get("category") instanceof JsonPrimitive cat ? shortType(cat.getAsString()) : "monsters";
        if (PLACE_CATEGORIES.contains(category)) {
            return "place";
        }
        return category.equals("lore") ? "article" : "creature";
    }

    /** Updates the default single-source unlock of section 0 when switching a Codex entry between creature, place, and article. */
    private static void updateInitialCodexUnlock(JsonObject entry, String nextSource) {
        if (!(entry.get("sections") instanceof JsonArray sections) || sections.isEmpty()
                || !(sections.get(0) instanceof JsonObject first) || !(first.get("unlock") instanceof JsonObject unlock)) {
            return;
        }
        List<String> sources = stringsOf(unlock.get("on"));
        if (sources.size() == 1 && (sources.getFirst().equals("manual") || sources.getFirst().equals("seen") || sources.getFirst().equals("visited"))) {
            JsonArray on = new JsonArray();
            on.add(nextSource);
            unlock.add("on", on);
        }
    }

    private static String codexPlaceSummary(@Nullable JsonObject place) {
        if (place == null || place.isEmpty()) {
            return DevUi.str("codex.place_empty");
        }
        List<String> parts = new ArrayList<>();
        if (place.get("dimension") instanceof JsonPrimitive dimension) {
            parts.add(dimensionName(dimension.getAsString()));
        }
        for (String field : List.of("structure", "biome", "poi")) {
            List<String> ids = stringsOf(place.get(field) instanceof JsonPrimitive one ? asList(one) : place.get(field));
            ids.forEach(id -> parts.add(DevUi.humanize(id.substring(id.indexOf(':') + 1))));
        }
        if (place.get("area") instanceof JsonObject area && !area.isEmpty()) {
            parts.add(DevUi.str("field.area"));
        }
        return parts.isEmpty() ? DevUi.str("codex.place_empty") : "🗺 " + String.join(" · ", parts);
    }

    /**
     * Illustration shown in a book frame at the top of the Codex page: pick from {@code config/storybound/images/},
     * capture directly from the world with the camera viewfinder, or clear.
     */
    void codexImageField(List<Object> path, @Nullable JsonObject entry) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("codex.image"));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        String current = entry != null && entry.get("image") instanceof JsonPrimitive written && !written.getAsString().isBlank()
                ? written.getAsString() : null;
        String shownText = current != null ? "🖼 " + DevUi.shortId(current) : DevUi.str("codex.image_none");
        String clipped = screen.font().plainSubstrByWidth(shownText, w - 56);
        Button pick = Button.builder(Component.literal(clipped + " \u25B8"), b -> screen.catalog().open(
                DevUi.text("codex.pick_image"), Catalog.Kind.IMAGE, current, (kind, chosen) -> {
                    if (chosen.id().startsWith(Catalog.PROBLEM)) {
                        screen.status(DevUi.text("image.problem." + chosen.id().substring(Catalog.PROBLEM.length())), DevUi.BAD);
                        return;
                    }
                    if (chosen.id().equals(Catalog.CLEAR)) {
                        ensureObject(path).remove("image");
                    } else {
                        ensureObject(path).addProperty("image", chosen.id());
                    }
                    host.changed();
                }))
                .tooltip(Tooltip.create(DevUi.text("codex.image_tooltip", dev.dzenthai.storybound.client.sound.StoryAssets.imageFolder().toString())))
                .bounds(x, rowTop, w - 40, CONTROL_H).build();
        screen.add(pick);
        screen.add(Button.builder(Component.literal("📷"), b -> {
            ResourceLocation shotId = defaultShotId();
            DevFrameCapture.start(screen, shotId, savedId -> {
                ensureObject(path).addProperty("image", savedId.toString());
                host.changed();
                screen.status(DevUi.text("codex.capture.saved", savedId.toString()), DevUi.OK);
            });
        }).tooltip(Tooltip.create(DevUi.text("codex.capture.button_tooltip"))).bounds(x + w - 38, rowTop, 18, CONTROL_H).build());
        Button clear = removeButton(rowTop, DevUi.text("codex.image_clear"), b -> {
            ensureObject(path).remove("image");
            host.changed();
        });
        clear.active = current != null;
        screen.add(clear);
    }

    private ResourceLocation defaultShotId() {
        StoryLinks.NodeKey key = host.node();
        String namespace = key != null ? key.id().getNamespace() : ClientDevStore.namespace();
        String rawPath = key != null ? key.id().getPath() : "entry";
        String safe = ("codex_" + rawPath.replace('/', '_')).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return ResourceLocation.fromNamespaceAndPath(namespace, safe);
    }

    /**
     * The quest's type, its group in the Quests tab: a button with it by name and a dropdown of the types (main, side,
     * contract, and those other quests have).
     */
    void questTypeField(List<Object> path, @Nullable JsonObject quest) {
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
    static String shortType(String type) {
        return type.startsWith("storybound:") ? type.substring("storybound:".length()) : type;
    }

    /** "Main quests"; another type by its translation, or as it is. */
    private static String questTypeName(String type) {
        ResourceLocation id = ResourceLocation.tryParse(type.contains(":") ? type : "storybound:" + type);
        String key = id == null ? "" : id.getNamespace() + ".quest.category." + id.getPath();
        return Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) : type;
    }

    /**
     * Whether the quest comes again: no, or yes with how in words ("at once, without a limit", "after 600 s, at most 3
     * times"), which opens its settings.
     */
    void repeatField(List<Object> path, Node node, @Nullable JsonObject quest) {
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

    /**
     * A choice's timer: yes/no, and while yes what happens in words ("after 10 s → bye"), which opens it.
     */
    void timeoutField(List<Object> path, Node node, @Nullable JsonObject choice) {
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
     * What a document's empty style, journal section or text is, by its kind, for the box to say: a letter is on
     * parchment and goes to Letters; the text is the document's own.
     */
    @Nullable Component documentDefault(String name, List<Object> path) {
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

    /**
     * Places where a document can spawn as world loot: one row per rule with a summary and remove button, followed
     * always by "+ Add", which appends a new rule and opens its detail page right away.
     */
    void documentLootField(List<Object> path, Node node, @Nullable JsonObject document) {
        Node items = Schemas.items(node);
        List<Object> lootPath = with(path, "loot");
        JsonArray rules = document != null && document.get("loot") instanceof JsonArray list ? list : new JsonArray();
        for (int i = 0; i < rules.size(); i++) {
            int index = i;
            if (!row(CONTROL_H)) {
                continue;
            }
            int rowTop = rowY(CONTROL_H);
            JsonObject rule = rules.get(i) instanceof JsonObject obj ? obj : new JsonObject();
            String line = lootRuleSummary(rule);
            String clipped = screen.font().plainSubstrByWidth(line, w - 36);
            Component shown = Component.literal(clipped + (screen.font().width(line) > w - 36 ? "…" : "") + " ▸");
            screen.add(Button.builder(shown, b -> {
                if (items != null) {
                    push(with(lootPath, index), items, DevUi.str("document.loot.rule_title", index + 1), false);
                }
            }).tooltip(Tooltip.create(Component.literal(line))).bounds(x, rowTop, w - 20, CONTROL_H).build());
            screen.add(removeButton(rowTop, DevUi.text("form.remove"), b -> {
                JsonArray list = ensureArray(lootPath);
                if (index < list.size()) {
                    list.remove(index);
                }
                if (list.isEmpty()) {
                    ensureObject(path).remove("loot");
                }
                host.changed();
            }));
        }
        if (row(CONTROL_H)) {
            screen.add(Button.builder(Component.literal("+ ").append(DevUi.text("form.add")), b -> {
                JsonArray list = ensureArray(lootPath);
                list.add(new JsonObject());
                int index = list.size() - 1;
                host.changed();
                if (items != null) {
                    push(with(lootPath, index), items, DevUi.str("document.loot.rule_title", index + 1), false);
                }
            }).tooltip(Tooltip.create(DevUi.text("document.loot.add_tooltip"))).bounds(x, rowY(CONTROL_H), w, CONTROL_H).build());
        }
    }

    /**
     * Source section of one document loot rule: a switcher between "Chests by place" (dimension/biome/structure) and
     * "Loot table" (specific tables + optional "Restrict by place" toggle). Switching preserves cleared fields in
     * {@link #switchedAway} for this session.
     */
    void lootSourceFields(List<Object> path, Node schema, @Nullable JsonObject rule) {
        String modeKey = with(path, "loot_mode").toString();
        String limitKey = with(path, "loot_limit_place").toString();
        boolean hasTables = rule != null && rule.has("tables");
        if (hasTables) {
            entityModes.put(modeKey, "tables");
        }
        String mode = entityModes.getOrDefault(modeKey, "place");
        boolean tablesMode = mode.equals("tables");
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("document.loot.mode"));
            Button switcher = Button.builder(Component.literal(DevUi.str("document.loot.mode." + mode) + " \u25B8"), b -> {
                JsonObject target = ensureObject(path);
                if (tablesMode) {
                    putAway(with(path, "tables"), target, "tables");
                    giveBack(with(path, "place"), target, "dimension");
                    giveBack(with(path, "place"), target, "biome");
                    giveBack(with(path, "place"), target, "structure");
                    entityModes.put(modeKey, "place");
                } else {
                    putAway(with(path, "place"), target, "dimension");
                    putAway(with(path, "place"), target, "biome");
                    putAway(with(path, "place"), target, "structure");
                    giveBack(with(path, "tables"), target, "tables");
                    entityModes.remove(limitKey);
                    entityModes.put(modeKey, "tables");
                }
                host.changed();
            }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
            switcher.setTooltip(Tooltip.create(DevUi.text("document.loot.mode.tooltip")));
            screen.add(switcher);
        }
        if (!tablesMode) {
            placeRows(path, true);
            boolean hasPlace = rule != null && (rule.has("dimension") || rule.has("biome") || rule.has("structure"));
            if (!hasPlace) {
                text(DevUi.text("document.loot.place_empty"), DevUi.MUTED);
            }
            return;
        }
        Picker tablesPicker = picker("tables", schema, path);
        if (tablesPicker != null) {
            idListRows(path, "tables", tablesPicker, fieldLabel("tables", false, schema));
        }
        if (!hasTables) {
            text(DevUi.text("document.loot.tables_empty"), DevUi.MUTED);
        }
        boolean hasPlace = rule != null && (rule.has("dimension") || rule.has("biome") || rule.has("structure"));
        boolean limitPlace = hasPlace || "on".equals(entityModes.get(limitKey));
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("document.loot.limit_place"));
            Button toggle = Button.builder(Component.literal(DevUi.yesNo(limitPlace)), b -> {
                JsonObject target = ensureObject(path);
                if (limitPlace) {
                    putAway(with(path, "tables_place"), target, "dimension");
                    putAway(with(path, "tables_place"), target, "biome");
                    putAway(with(path, "tables_place"), target, "structure");
                    entityModes.remove(limitKey);
                } else {
                    giveBack(with(path, "tables_place"), target, "dimension");
                    giveBack(with(path, "tables_place"), target, "biome");
                    giveBack(with(path, "tables_place"), target, "structure");
                    entityModes.put(limitKey, "on");
                }
                host.changed();
            }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
            toggle.setTooltip(Tooltip.create(DevUi.text("document.loot.limit_place.tooltip")));
            screen.add(toggle);
        }
        if (limitPlace) {
            placeRows(path, true);
        }
    }

    /** Crisp summary of one document loot rule for the document's "Where it appears" list. */
    private static String lootRuleSummary(JsonObject rule) {
        List<String> parts = new ArrayList<>();
        List<String> tables = stringsOf(rule.get("tables") instanceof JsonPrimitive one ? asList(one) : rule.get("tables"));
        List<String> where = new ArrayList<>();
        if (rule.get("dimension") instanceof JsonPrimitive dimension) {
            where.add(dimensionName(dimension.getAsString()));
        }
        for (String field : List.of("biome", "structure")) {
            List<String> ids = stringsOf(rule.get(field) instanceof JsonPrimitive one ? asList(one) : rule.get(field));
            ids.forEach(id -> where.add(DevUi.humanize(id.substring(id.indexOf(':') + 1))));
        }
        if (tables.isEmpty()) {
            parts.add(where.isEmpty() ? DevUi.str("document.loot.any_chests")
                    : DevUi.str("document.loot.chests_in", String.join(", ", where)));
        } else {
            List<String> shortTables = new ArrayList<>();
            int shown = Math.min(2, tables.size());
            for (int i = 0; i < shown; i++) {
                String id = tables.get(i);
                shortTables.add(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
            }
            String tableText = String.join(", ", shortTables) + (tables.size() > 2 ? " +" + (tables.size() - 2) : "");
            parts.add(tableText);
            if (!where.isEmpty()) {
                parts.add(String.join(", ", where));
            }
        }
        if (rule.get("chance") instanceof JsonPrimitive chance && chance.isNumber() && chance.getAsDouble() < 1.0) {
            parts.add(format(Math.round(chance.getAsDouble() * 10000) / 100.0) + "%");
        }
        if (rule.get("once") instanceof JsonPrimitive once) {
            String value = once.getAsString();
            if (value.equals("player") || value.equals("world")) {
                parts.add(DevUi.str("document.loot.once_" + value));
            }
        }
        boolean hasConditions = rule.get("conditions") instanceof JsonArray list ? !list.isEmpty() : rule.get("conditions") instanceof JsonObject;
        return "📦 " + String.join(" · ", parts) + (hasConditions ? DevUi.str("quest.and_conditions") : "");
    }

    /** An empty "next" of a quest's stage: the following stage, or the quest completed after the last. */
    @Nullable Component stageDefault(String name, List<Object> path) {
        return name.equals("next") && "stage".equals(insideQuest(path)) && host.quest() != null ? DevUi.text("quest.next_default") : null;
    }

    /**
     * A stage's or an objective's name, like a node's own: other stages' "next", conditions, waypoints and players' place
     * and counters name it by it, so it is not typed over but renamed with ✓, with all of them (see
     * {@link StoryRenames}). An objective without a name of its own is counted by its
     * position, which the box shows greyed.
     */
    void insideNameField(List<Object> path, String kind) {
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
    void nameRow(String key, Component label, Component tooltip, Component applyTooltip, String current, @Nullable Component unnamed,
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
            box.setTextColor(bad ? DevUi.ERROR : DevUi.TEXT);
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

    /** The moments of a quest's {@code screen_text}, in the form's order. */
    private static final List<String> SCREEN_MOMENTS = List.of("started", "completed", "failed");

    /**
     * A quest's text on the screen: a light heading, and for each moment a button "Show on the screen" while it is not
     * written (nothing shows then); pressed, the moment is written empty, which shows the quest's title alone, and a
     * box for the words under it takes its place, with ✕ that takes the moment out again. Emptied, the box writes the
     * moment as {@code ""}, not a key with no text, which the game would show as the key.
     */
    void screenTextFields(List<Object> path) {
        List<Object> at = with(path, "screen_text");
        subgroup(fieldLabel("screen_text", false));
        for (String moment : SCREEN_MOMENTS) {
            if (!row(LABEL_H + CONTROL_H)) {
                continue;
            }
            labelRow(DevUi.text("field.screen_text." + moment));
            int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
            JsonObject texts = objectAt(at);
            if (texts == null || !texts.has(moment)) {
                screen.add(Button.builder(DevUi.text("screen_text.show"), b -> {
                    ensureObject(at).addProperty(moment, "");
                    host.changed();
                }).tooltip(Tooltip.create(DevUi.text("screen_text.show_tooltip"))).bounds(x, rowTop, w, CONTROL_H).build());
                continue;
            }
            EditBox box = new EditBox(screen.font(), x, rowTop, w - 20, CONTROL_H - 2, Component.empty());
            box.setMaxLength(1024);
            box.setValue(ClientDevStore.readStoryText(texts, moment, "", ClientDevStore.activeLanguage()));
            box.setHint(DevUi.text("screen_text.title_only"));
            String key = host.textKey(keyPath(at), moment);
            box.setResponder(value -> {
                JsonObject owner = ensureObject(at);
                if (value.isEmpty()) {
                    owner.addProperty(moment, "");
                } else {
                    String existing = ClientDevStore.getKey(owner, moment);
                    ClientDevStore.writeStoryText(owner, moment, value, existing != null && !existing.isBlank() ? existing : key);
                }
                host.edited();
            });
            box.setTooltip(Tooltip.create(DevUi.text("screen_text.box_tooltip")));
            screen.add(box);
            screen.add(removeButton(rowTop - 1, DevUi.text("screen_text.remove"), b -> {
                JsonObject owner = ensureObject(at);
                owner.remove(moment);
                if (owner.isEmpty()) {
                    ensureObject(path).remove("screen_text");
                }
                host.changed();
            }));
        }
    }
}
