package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.client.dev.Catalog.Entry;
import dev.dzenthai.storybound.client.dev.Schemas.Kind;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import dev.dzenthai.storybound.module.quest.DifficultyScale;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * The form's plain inputs ({@link FormView}): text, choices, yes/no, colours, icons, sounds, positions, ids.
 * <p>
 * One layer of the form, split by topic, each seeing the ones below it; the rest of the editor uses FormView:
 * FormCore &larr; FormPickers &larr; FormInputs &larr; FormEntities &larr; FormNpc &larr; FormWaypoints &larr; FormDialogues &larr; FormStory &larr; FormView.
 */
abstract class FormInputs extends FormPickers {
    FormInputs(Host host) {
        super(host);
    }

    /**
     * A list of ids (mobs, biomes, blocks, places; each an id or a #tag): a row each by its name, which picks another
     * in its place, ✕ to take it out, "+ Add" from the list. One is written alone, several as a list, as the files have
     * them.
     *
     * @param label above the rows; null where the page already says it (an entity's type under its switch)
     */
    void idListRows(List<Object> path, String name, Picker picker, @Nullable Component label) {
        idListRows(path, name, picker, picker, label, 0, id -> null);
    }

    /**
     * As {@link #idListRows(List, String, Picker, Component)}, picking from {@code choose} and naming by {@code names};
     * a row whose id no longer fits ({@code problem} says why) shows red with the reason, to be taken out or picked
     * again.
     *
     * @param max at most so many (1: a field of one, as a location's structure); 0 for any
     */
    void idListRows(List<Object> path, String name, Picker choose, Picker names, @Nullable Component label, int max,
                            java.util.function.Function<String, Component> problem) {
        JsonObject owner = objectAt(path);
        List<String> ids = owner == null ? List.of() : stringsOf(owner.get(name) instanceof JsonPrimitive one ? asList(one) : owner.get(name));
        if (label != null && row(LABEL_H)) {
            labels.add(new Label(label, x, rowY(LABEL_H), DevUi.LABEL));
        }
        Picker picker = choose;
        for (int i = 0; i < ids.size(); i++) {
            int index = i;
            if (!row(CONTROL_H)) {
                continue;
            }
            int rowTop = rowY(CONTROL_H);
            Component wrong = problem.apply(ids.get(i));
            String shown = idName(ids.get(i), names);
            Component text = wrong == null ? Component.literal(shown) : Component.literal("⚠ " + shown).withColor(DevUi.ERROR);
            Component tip = wrong == null ? Component.literal(ids.get(i)) : Component.empty().append(wrong).append("\n" + ids.get(i));
            screen.add(Button.builder(text, b -> pick(picker, entry -> {
                List<String> next = new ArrayList<>(ids);
                next.set(index, entry.id());
                writeIds(path, name, next);
            })).tooltip(Tooltip.create(tip)).bounds(x, rowTop, w - 20, CONTROL_H).build());
            screen.add(removeButton(rowTop, DevUi.text("form.remove"), b -> {
                List<String> next = new ArrayList<>(ids);
                next.remove(index);
                writeIds(path, name, next);
            }));
        }
        if ((max == 0 || ids.size() < max) && row(CONTROL_H)) {
            screen.add(Button.builder(Component.literal("+ ").append(DevUi.text("form.add")), b -> pick(picker, entry -> {
                if (!ids.contains(entry.id())) {
                    List<String> next = new ArrayList<>(ids);
                    next.add(entry.id());
                    writeIds(path, name, next);
                }
            })).tooltip(Tooltip.create(DevUi.text("form.add_from_list"))).bounds(x, rowY(CONTROL_H), w, CONTROL_H).build());
        }
    }

    void writeIds(List<Object> path, String name, List<String> ids) {
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
    static String idName(String id, Picker picker) {
        for (Entry entry : picker.entries()) {
            if (entry.id().equals(id)) {
                return entry.label();
            }
        }
        if (id.startsWith("#") && picker.kind() != null) {
            // A #tag as the list names it ("Raiders"), else its name made readable ("Skeletons").
            String listed = Catalog.label(picker.kind(), id);
            ResourceLocation tag = ResourceLocation.tryParse(id.substring(1));
            return listed != null ? listed : tag != null ? DevUi.humanize(tag.getPath().substring(tag.getPath().lastIndexOf('/') + 1)) : id;
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
    static @Nullable String observedSource(List<String> sources) {
        return sources.stream().filter(source -> source.equals("observed") || source.startsWith("observed:")).findFirst().orElse(null);
    }

    void writeSources(List<Object> path, List<String> sources) {
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
     * A share written as a fraction, in percent: "5" for 0.05. Empty leaves it out (the default); a number off 0–100
     * turns red and is not written.
     */
    void percentField(List<Object> path, String name, Node node, @Nullable JsonObject object, Component label) {
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
                    .copy().withStyle(style -> style.withColor(DevUi.HINT)));
        }
        box.setResponder(text -> {
            JsonObject owner = ensureObject(path);
            if (text.isBlank()) {
                owner.remove(name);
                box.setTextColor(DevUi.TEXT);
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
            box.setTextColor(percent != null ? DevUi.TEXT : DevUi.ERROR);
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
     * Where an arrow of a dialogue node leads ({@code next}, {@code then}, {@code else}, an answer's, a branch's, the
     * timer's): not typed but shown, as the graph's arrows are the way to draw them. A click shows the node on the
     * graph; ▾ picks another (the nodes by their name and first words, the end of the dialogue, leaving the whole
     * conversation); ✕ unlinks it, so it ends the dialogue.
     */
    void targetField(List<Object> path, String name, @Nullable JsonElement value, Component label, boolean required) {
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
        Button unlink = removeButton(rowTop, DevUi.text("target.unlink"), b -> {
            // A required arrow (a condition's yes and no, the timer's) ends the dialogue explicitly; another one is left out.
            if (required) {
                ensureObject(path).addProperty(name, DialogueGraph.END);
            } else {
                ensureObject(path).remove(name);
            }
            host.changed();
        });
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
     * Vorbis or is wrongly named, or none at all.
     */
    void soundField(List<Object> path, String name, @Nullable JsonElement value, Component label) {
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
            box.setTextColor(known ? DevUi.TEXT : DevUi.ERROR);
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
        // A track (a dialogue's, a stage's, a place's) is picked from the music only.
        boolean music = name.equals("music") || name.equals("track");
        screen.add(Button.builder(Component.literal("…"), b -> {
            screen.catalog().open(DevUi.text(music ? "music.pick" : "sound.pick"),
                    music ? Catalog.Kind.MUSIC : Catalog.Kind.SOUND, box.getValue(), (kind, entry) -> {
                if (entry.id().startsWith(Catalog.PROBLEM)) {
                    screen.status(DevUi.text("sound.problem." + entry.id().substring(Catalog.PROBLEM.length())), DevUi.BAD);
                    return;
                }
                if (entry.id().equals(Catalog.CLEAR)) {
                    ensureObject(path).remove(name);
                } else {
                    ensureObject(path).addProperty(name, entry.id());
                }
                host.changed();
            });
            screen.rebuild();
        }).tooltip(Tooltip.create(DevUi.text(music ? "music.pick" : "sound.pick"))).bounds(x + w - 38, rowTop, 18, CONTROL_H).build());
        screen.add(DevSoundPreview.button(box::getValue, x + w - 18, rowTop, 18, CONTROL_H));
    }

    /**
     * A document's icon in the inventory, its {@code custom_model_data}: the story's icons
     * ({@code config/storybound/icons}) by name, picked from a list that shows them; empty, the kind's own icon. A
     * number no icon of the story has stays, said to come from a resource pack.
     */
    void iconField(List<Object> path, String name, @Nullable JsonObject document) {
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
        Button clear = removeButton(rowTop, DevUi.text("document.icon_clear"), b -> {
            ensureObject(path).remove(name);
            host.changed();
        });
        clear.active = number != null;
        screen.add(clear);
    }

    /**
     * Whether a story text is written at length, so its field has the text window's ✎: a dialogue's line and answer, a
     * quest's description, its texts per state and its stages' log, an objective's hint and hints by circumstance. The
     * rest (titles, names, a contract's lines, an objective's line) is one line.
     */
    static boolean longText(String name, Node owner) {
        Map<String, Node> properties = Schemas.properties(owner);
        return switch (name) {
            case "description", "log", "active", "completed", "failed" -> true;
            // An objective's hint and its hints by circumstance are sentences too; a contract's hint is one line.
            case "hint" -> properties.containsKey("since_stage");
            case "text" -> properties.containsKey("speaker") || properties.containsKey("locked_text") || properties.containsKey("after");
            default -> false;
        };
    }

    /**
     * 🎤 beside a text that is spoken: its voice-over ({@code sound} of {@code holder}), picked from the story's sounds as a
     * sound field picks one. While it has one: 🎙 (a microphone with a tick), gold, its id in the tooltip; the picker marks
     * it and offers "No sound" to take it away.
     *
     * @param holder the object holding {@code sound}, made when missing
     * @param picked after it is set or taken away
     */
    static boolean hasVoiceOver(@Nullable JsonObject current) {
        return current != null && current.get("sound") instanceof JsonPrimitive id && !id.getAsString().isBlank();
    }

    void voiceOverButton(@Nullable JsonObject current, java.util.function.Supplier<JsonObject> holder, Runnable picked, int buttonX, int rowY) {
        String sound = hasVoiceOver(current) ? current.get("sound").getAsString() : null;
        if (sound != null) {
            screen.add(DevSoundPreview.button(() -> sound, buttonX - 20, rowY, 18, CONTROL_H));
        }
        // A microphone with a tick while it has one.
        Button button = Button.builder(Component.literal(sound != null ? "🎙" : "🎤").withColor(sound != null ? DevUi.ACCENT : DevUi.MUTED), b -> {
            screen.catalog().open(DevUi.text("sound.pick"), Catalog.Kind.SOUND, sound, (kind, entry) -> {
                if (entry.id().startsWith(Catalog.PROBLEM)) {
                    screen.status(DevUi.text("sound.problem." + entry.id().substring(Catalog.PROBLEM.length())), DevUi.BAD);
                    return;
                }
                if (entry.id().equals(Catalog.CLEAR)) {
                    holder.get().remove("sound");
                } else {
                    holder.get().addProperty("sound", entry.id());
                }
                picked.run();
            });
            screen.rebuild();
        }).tooltip(Tooltip.create(sound != null ? DevUi.text("voiceover.set", sound) : DevUi.text("voiceover.empty")))
                .bounds(buttonX, rowY, 18, CONTROL_H).build();
        screen.add(button);
    }

    void textField(List<Object> path, String name, Component label, @Nullable Tooltip tooltip, boolean window) {
        textField(path, name, label, tooltip, window, false);
    }

    /** With {@code voiceOver}, a 🎤 beside the text for the voice-over of the object holding it (a dialogue's line). */
    void textField(List<Object> path, String name, Component label, @Nullable Tooltip tooltip, boolean window, boolean voiceOver) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        JsonObject object = objectAt(path);
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        int voiceCols = voiceOver ? (hasVoiceOver(object) ? 40 : 20) : 0;
        int buttons = (window ? 20 : 0) + voiceCols;
        EditBox box = new EditBox(screen.font(), x, rowTop, w - buttons, CONTROL_H - 2, label);
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
        if (voiceOver) {
            voiceOverButton(object, () -> ensureObject(path), host::changed, x + w - (window ? 38 : 18), rowTop - 1);
        }
        if (!window) {
            return;
        }
        // The same text in a window of its own, for a long line.
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

    void enumField(List<Object> path, String name, Node node, boolean required, @Nullable JsonElement value, Component label, @Nullable Tooltip tooltip) {
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
    void difficultyField(String name, @Nullable JsonElement value, Component label) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        JsonObject options = ClientDevStore.projectOptions();
        DifficultyScale scale = options.get("difficulty") instanceof JsonObject difficulty
                ? DifficultyScale.parse(difficulty) : DifficultyScale.DEFAULT;
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
     * A waypoint's colour: a swatch and its code. Typing a code recolours the swatch as soon as it is one; the swatch
     * opens a list of the colours that look the same everywhere ({@link WaypointDefinition#MARKER_COLORS}), with an own
     * colour written before on top. Empty, or the default's code, leaves the field out (the marker's own look).
     */
    void colorField(List<Object> path, @Nullable JsonObject object) {
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
        box.setHint(DevUi.text("form.waypoint_color.default").copy().withStyle(style -> style.withColor(DevUi.HINT)));
        box.setResponder(text -> {
            JsonObject owner = ensureObject(path);
            if (text.isBlank()) {
                owner.remove("color");
                shown[0] = WaypointDefinition.DEFAULT_COLOR;
                box.setTextColor(DevUi.TEXT);
                host.edited();
                return;
            }
            Integer parsed = parseColor(text);
            box.setTextColor(parsed != null ? DevUi.TEXT : DevUi.ERROR);
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
            graphics.fill(getX(), getY(), getX() + width, getY() + height, isHovered() ? DevUi.ACCENT : DevUi.CARD_EDGE);
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, DevUi.BACKGROUND);
            graphics.fill(getX() + 3, getY() + 3, getX() + width - 3, getY() + height - 3, 0xFF000000 | color.get());
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * An NPC binding's {@code custom} where the server has no KubeJS: its event would reach no script, so the field says
     * so, greyed (it can still be switched, to take it back).
     */
    boolean needsKubeJs(List<Object> path, String name) {
        ClientDevStore.Scripts scripts = ClientDevStore.scripts();
        return path.isEmpty() && name.equals("custom") && host.npcBinding() != null && scripts.known() && !scripts.kubejs();
    }

    void booleanField(List<Object> path, String name, Node node, @Nullable JsonElement value, Component label, @Nullable Tooltip tooltip) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        if (needsKubeJs(path, name)) {
            labels.add(new Label(label, x, rowY(LABEL_H + CONTROL_H), DevUi.MUTED));
        } else {
            labelRow(label);
        }
        JsonElement fallback = node.defaultValue();
        boolean byDefault = fallback != null && fallback.isJsonPrimitive() && fallback.getAsBoolean();
        // An NPC's switches add up with the nodes below it (layers): not written, it is theirs.
        boolean layered = path.isEmpty() && host.npcBinding() != null;
        if (layered && name.equals("respawn") && flag(host.root(), "one", false)) {
            // A "one" NPC comes back unless it says otherwise.
            byDefault = true;
        }
        boolean current = value != null && value.isJsonPrimitive() ? value.getAsBoolean() : byDefault;
        Component shown = value == null ? DevUi.text(layered ? "form.from_below" : "form.unset", DevUi.yesNo(byDefault)) : Component.literal(DevUi.yesNo(current));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        boolean reset = layered && value != null;
        Button button = Button.builder(shown, b -> {
            ensureObject(path).addProperty(name, !current);
            host.changed();
        }).bounds(x, rowTop, reset ? w - 20 : w, CONTROL_H).build();
        if (tooltip != null) {
            button.setTooltip(tooltip);
        }
        screen.add(button);
        if (reset) {
            screen.add(Button.builder(Component.literal("↺"), b -> {
                ensureObject(path).remove(name);
                host.changed();
            }).tooltip(Tooltip.create(DevUi.text("form.give_below"))).bounds(x + w - 18, rowTop, 18, CONTROL_H).build());
        }
    }

    /**
     * Writes a position, whole or to a tenth as the schema wants; the dimension goes along when the object has a field
     * for it (an area, a waypoint target, a place of an action).
     */
    void writePosition(List<Object> path, String name, Node node, Node owner, double[] coordinates, ResourceLocation dimension) {
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

    static String display(Kind kind, @Nullable JsonElement value) {
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
}
