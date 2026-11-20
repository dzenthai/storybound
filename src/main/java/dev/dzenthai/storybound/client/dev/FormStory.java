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
            ClientDevStore.TYPE_CHAPTER, "chapter");

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
     * title and a dropdown of the chosen entry's sections, "The whole entry" first (October 30, 2026; before, its id
     * typed blind).
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
     * A boss's section (lore, tactics, rewards) under a heading of its own: whether the entry has it (a section left out
     * is not in the Bosses tab, whatever its text), and while it does its fields here, its text and when it opens first
     * (October 30, 2026; before, a row each opening a page with the text as an id of a file).
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
     * A Codex entry's sections as its author thinks of them (October 30, 2026; before, one row "Sections" hiding them
     * all, the main description among them, so an entry seemed to have no "Opens" of its own): the first section is
     * the entry's main description, right here with its title, text and when it opens, under a word that the entry
     * appears in the Codex when any section opens; the others behind a row of their own. An entry without sections
     * offers to add the main description.
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
     * removable, and "+ Read a document…" with the story's documents (October 30, 2026; before, a list of strings typed
     * as "seen" or "read:storybound:diary"). The last source cannot go: a section must open somehow.
     */
    void unlockSourcesField(List<Object> path, @Nullable JsonObject unlock, Component label) {
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
     * A Codex entry's group in the Codex tab: a button with it by name and a dropdown of the built-in groups and those
     * other entries have (October 30, 2026; before, an id typed, labelled "Section").
     */
    void codexCategoryField(List<Object> path, @Nullable JsonObject entry) {
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
     * Whether the quest comes again: no, or yes with how in words ("at once, without a limit", "after 600 s, at most
     * 3 times"), which opens its settings. Before, a switch between "true" and an object.
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
     * A choice's timer: yes/no, and while yes what happens in words ("after 10 s → bye"), which opens it. Before, a row
     * "1 ▸" or "—".
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
     * What a document's empty style, journal section or text is, by its kind, for the box to say (October 30, 2026;
     * before, an empty box): a letter is on parchment and goes to Letters; the text is the document's own.
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
     * A quest's text on the screen (November 20, 2026, the author: an empty box said nothing of what shows): a light
     * heading, and for each moment a button "Show on the screen" while it is not written (nothing shows then); pressed,
     * the moment is written empty, which shows the quest's title alone, and a box for the words under it takes its place,
     * with ✕ that takes the moment out again. Emptied, the box writes the moment as {@code ""}, not a key with no text,
     * which the game would show as the key.
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
            screen.add(Button.builder(Component.literal("✕"), b -> {
                JsonObject owner = ensureObject(at);
                owner.remove(moment);
                if (owner.isEmpty()) {
                    ensureObject(path).remove("screen_text");
                }
                host.changed();
            }).tooltip(Tooltip.create(DevUi.text("screen_text.remove"))).bounds(x + w - 18, rowTop - 1, 18, CONTROL_H).build());
        }
    }
}
