package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The dialogue's forms ({@link FormView}): its fields, its participants, its nodes' fields and order, phrases,
 * jumps.
 * <p>
 * One layer of the form, split by topic, each seeing the ones below it; the rest of the editor uses FormView:
 * FormCore &larr; FormPickers &larr; FormInputs &larr; FormEntities &larr; FormNpc &larr; FormWaypoints &larr; FormDialogues &larr; FormStory &larr; FormView.
 */
abstract class FormDialogues extends FormWaypoints {
    FormDialogues(Host host) {
        super(host);
    }

    /**
     * A dialogue's own settings (its lines are on the graph) in the order its author asks (October 29, 2026; before, the
     * file's order, who speaks first and the camera after what an interruption does): how it shows, with the camera
     * only on the dialogue screen; who speaks; what an interruption does.
     */
    static final Map<String, String> DIALOGUE_GROUPS = Map.of(
            "presentation", "dialogue.group.show",
            "participants", "dialogue.group.who",
            "closable", "dialogue.group.abort");

    static boolean isDialogue(Node schema) {
        Map<String, Node> properties = Schemas.properties(schema);
        return properties.containsKey("presentation") && properties.containsKey("nodes") && properties.containsKey("participants");
    }

    /** The camera turns only on the dialogue screen (subtitles and bubbles keep the player's view). */
    static boolean dialogueFieldShown(String name, @Nullable JsonObject dialogue) {
        boolean screen = !(dialogue != null && dialogue.get("presentation") instanceof JsonPrimitive shown) || shown.getAsString().equals("screen");
        return !name.equals("camera") || screen || dialogue != null && dialogue.has("camera");
    }

    /** A portrait is for a speaker without an entity; one with an entity shows the entity. */
    static boolean participantFieldShown(String name, @Nullable JsonObject participant) {
        boolean virtual = participant != null && participant.get("type") instanceof JsonPrimitive type && type.getAsString().equals("virtual");
        if (name.equals("name")) {
            // The NPC talked to is named by its NPC (November 6, 2026): a name here only for a voice without an entity,
            // or when the file still writes one.
            return virtual || participant != null && participant.has("name");
        }
        return !name.equals("portrait") || virtual || participant != null && participant.has("portrait");
    }

    /**
     * Who speaks besides the player, in words: "npc (started with) · narrator «Narrator»". Without any written, the
     * default: the entity the dialogue was started with. Before, a count.
     */
    static String participantsSummary(@Nullable JsonElement value) {
        if (!(value instanceof JsonObject participants) || participants.isEmpty()) {
            return DevUi.str("dialogue.participants.default");
        }
        List<String> parts = new ArrayList<>();
        participants.entrySet().forEach(entry -> parts.add(participantSummary(entry.getKey(), entry.getValue())));
        return String.join(" · ", parts);
    }

    static String participantSummary(String key, JsonElement value) {
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
    static final Map<String, List<String>> NODE_ORDER = Map.ofEntries(
            Map.entry("node.jump", List.of("dialogue", "node")),
            Map.entry("option", List.of("text", "next", "conditions", "locked", "locked_text", "actions", "once", "think", "id")),
            // A document (October 30, 2026; before, the file's order, the journal behind a row, the contract for every kind).
            Map.entry("document", List.of("title", "kind", "custom_model_data", "style", "journal", "on_read", "contract", "content")),
            Map.entry("journal", List.of("add_on_read", "category")),
            // A quest (October 30, 2026; before, the file's order: its group and scope before its title, its texts per
            // state, start, stages and rewards shown as counts): what it is; how it goes; what it gives and changes;
            // for whom. Then its stages and their objectives.
            Map.entry("quest", List.of("title", "description", "text", "screen_text", "category", "difficulty", "start", "stages", "time_limit", "fail_when",
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
            Map.entry("observed", List.of("entity", "block", "pos", "item", "dimension", "time", "distance", "text")));

    /**
     * The headings of a form's groups, each at its first field (October 30, 2026: the first group's too, so every form
     * goes on from the name under a heading; before, its first fields read as the name's). A small page (a dialogue's
     * jump, a condition) has none: its title says what it is. A lone field joins a neighbouring group rather than
     * standing under a heading of its own, except a technical one at the end ("Other").
     */
    static final Map<String, Map<String, String>> NODE_GROUPS = Map.ofEntries(
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

    static boolean nodeFieldShown(String form, String name, @Nullable JsonObject object, @Nullable JsonObject dialogue) {
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
            // An entity, a block or an item: the other kinds show only while this one is empty (or more are written).
            boolean entity = object != null && object.has("entity");
            boolean block = object != null && (object.has("block") || object.has("pos"));
            boolean item = object != null && object.has("item");
            return switch (name) {
                case "entity" -> !block && !item || entity;
                case "block", "pos" -> !entity && !item || block;
                case "item" -> !entity && !block || item;
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
     * One line of a phrases node (November 5, 2026): its text, with \u270E for the text window. Text only since November 6,
     * 2026: a switch at its left picked a dialogue instead, gone with dialogue lines (an NPC starts a dialogue itself now).
     */
    void phraseRow(List<Object> path, String field, JsonArray array, int index, int rowY, int width) {
        int right = x;
        int rightW = width;
        String key = listKey(path, field) + "." + index;
        EditBox box = new EditBox(screen.font(), right, rowY + 1, rightW - 20, CONTROL_H - 2, Component.empty());
        box.setMaxLength(1024);
        box.setValue(ClientDevStore.readStoryTextAt(array, index, ClientDevStore.activeLanguage()));
        String source = ClientDevStore.readStoryTextAt(array, index, ClientDevStore.sourceLanguage());
        boolean translating = !ClientDevStore.activeLanguage().equalsIgnoreCase(ClientDevStore.sourceLanguage());
        if (translating) {
            box.setHint(Component.literal(source));
        }
        box.setResponder(text -> {
            JsonArray list = ensureArray(path);
            if (index < list.size()) {
                ClientDevStore.writeStoryTextAt(list, index, text, key);
                host.edited();
            }
        });
        screen.add(box);
        screen.add(Button.builder(Component.literal("\u270E"), b -> {
            JsonArray list = ensureArray(path);
            String current = index < list.size() ? ClientDevStore.readStoryTextAt(list, index, ClientDevStore.activeLanguage()) : "";
            screen.textEditor().openPlain(fieldLabel(field, false), current,
                    translating && !source.isBlank() ? Component.literal(source) : DevUi.text("text.hint_plain"), edited -> {
                        JsonArray target = ensureArray(path);
                        if (index < target.size()) {
                            ClientDevStore.writeStoryTextAt(target, index, edited, key);
                            host.changed();
                        }
                    });
        }).tooltip(Tooltip.create(DevUi.text("text.open_window"))).bounds(right + rightW - 18, rowY, 18, CONTROL_H).build());
    }

    /**
     * An NPC's phrases, for its chatter, a click or when it is hit (November 5, 2026), as an entity's "known by": at the
     * left a switch, "own text" or "phrases node"; at its right, a text written there (with \u270E for the text window) or
     * a button that picks one of the story's phrases nodes. Where a dialogue may stand instead (a click entry, a hit;
     * November 6, 2026) the switch has a third way, "dialogue", which writes the owner's {@code dialogue} in place of its
     * phrases; a click entry starts on it. Switching empties the field; it is written once filled.
     */
    void phrasesField(List<Object> path, String name, @Nullable JsonElement value, Component label, Node owner) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        Map<String, Node> fields = Schemas.properties(owner);
        boolean orDialogue = name.equals("phrases") && fields.containsKey("dialogue");
        boolean clickEntry = orDialogue && fields.containsKey("priority");
        labelRow(clickEntry ? DevUi.text("phrases.label.click") : orDialogue ? DevUi.text("phrases.label.hit") : label);
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        List<Object> at = with(path, name);
        JsonObject holder = objectAt(path);
        boolean dialogueWritten = orDialogue && holder != null && holder.get("dialogue") instanceof JsonPrimitive;
        String written = dialogueWritten ? "dialogue" : value instanceof JsonPrimitive ? "node" : value instanceof JsonObject ? "text" : null;
        List<String> modes = orDialogue ? (clickEntry ? List.of("dialogue", "node", "text") : List.of("text", "node", "dialogue")) : List.of("text", "node");
        String mode = written != null ? written : entityModes.getOrDefault(at.toString(), modes.getFirst());
        int modeW = Math.min(150, w * 2 / 5);
        Button switcher = Button.builder(Component.literal(DevUi.str("phrases.mode." + mode) + " \u25B8"), b -> {
            JsonObject target = ensureObject(path);
            // What this way wrote is kept to give back when it is switched to again.
            String field = mode.equals("dialogue") ? "dialogue" : name;
            JsonElement current = target.get(field);
            if (current != null) {
                switchedAway.put(at + "#" + mode, current);
            }
            target.remove(name);
            if (orDialogue) {
                target.remove("dialogue");
            }
            String next = modes.get((modes.indexOf(mode) + 1) % modes.size());
            JsonElement back = switchedAway.remove(at + "#" + next);
            if (back != null) {
                target.add(next.equals("dialogue") ? "dialogue" : name, back);
            }
            entityModes.put(at.toString(), next);
            host.changed();
        }).bounds(x, rowTop, modeW, CONTROL_H).build();
        switcher.setTooltip(Tooltip.create(DevUi.text(orDialogue ? "phrases.mode.tooltip_dialogue" : "phrases.mode.tooltip")));
        screen.add(switcher);
        int right = x + modeW + 4;
        int rightW = w - modeW - 4;
        if (mode.equals("dialogue")) {
            String id = dialogueWritten ? holder.get("dialogue").getAsString() : null;
            Picker picker = picker("dialogue", owner);
            Component shown = id != null ? Component.literal("💬 " + DevUi.shortId(id) + " \u25B8")
                    : Component.literal("+ ").append(DevUi.text("phrases.choose_dialogue"));
            Button choose = Button.builder(shown, b -> {
                if (picker != null) {
                    pick(picker, entry -> {
                        JsonObject target = ensureObject(path);
                        target.remove(name);
                        target.addProperty("dialogue", entry.id());
                        host.changed();
                    });
                }
            }).bounds(right, rowTop, rightW, CONTROL_H).build();
            choose.setTooltip(Tooltip.create(DevUi.text("phrases.pick_dialogue")));
            screen.add(choose);
            return;
        }
        if (mode.equals("node")) {
            String id = value instanceof JsonPrimitive node && !node.getAsString().isBlank() ? node.getAsString() : null;
            Picker picker = picker(name, owner);
            Component shown = id != null ? Component.literal("🗨 " + DevUi.shortId(id) + " \u25B8")
                    : Component.literal("+ ").append(DevUi.text("phrases.choose"));
            Button choose = Button.builder(shown, b -> {
                if (picker != null) {
                    pick(picker, entry -> {
                        ensureObject(path).addProperty(name, entry.id());
                        host.changed();
                    });
                }
            }).bounds(right, rowTop, rightW, CONTROL_H).build();
            choose.setTooltip(Tooltip.create(DevUi.text("phrases.choose_tooltip")));
            screen.add(choose);
            return;
        }
        // An own text: { "text": ... } in the field, the text under a key of its own, in the active language.
        String key = host.textKey(keyPath(at), "text");
        JsonObject own = value instanceof JsonObject object ? object : null;
        EditBox box = new EditBox(screen.font(), right, rowTop + 1, rightW - 20, CONTROL_H - 2, label);
        box.setMaxLength(1024);
        box.setValue(own != null ? ClientDevStore.readStoryText(own, "text", "", ClientDevStore.activeLanguage()) : "");
        String source = own != null ? ClientDevStore.readStoryText(own, "text", "", ClientDevStore.sourceLanguage()) : "";
        boolean translating = !ClientDevStore.activeLanguage().equalsIgnoreCase(ClientDevStore.sourceLanguage());
        if (translating) {
            box.setHint(Component.literal(source));
        }
        box.setResponder(text -> {
            JsonObject target = ensureObject(at);
            if (text.isEmpty() && !target.has("text")) {
                return;
            }
            String existing = ClientDevStore.getKey(target, "text");
            ClientDevStore.writeStoryText(target, "text", text, existing != null && !existing.isBlank() ? existing : key);
            host.edited();
        });
        screen.add(box);
        screen.add(Button.builder(Component.literal("\u270E"), b -> {
            JsonObject current = objectAt(at);
            String text = current != null ? ClientDevStore.readStoryText(current, "text", "", ClientDevStore.activeLanguage()) : "";
            screen.textEditor().openPlain(label, text, translating && !source.isBlank() ? Component.literal(source) : DevUi.text("text.hint_plain"), edited -> {
                JsonObject target = ensureObject(at);
                if (edited.isEmpty() && !target.has("text")) {
                    return;
                }
                String existing = ClientDevStore.getKey(target, "text");
                ClientDevStore.writeStoryText(target, "text", edited, existing != null && !existing.isBlank() ? existing : key);
                host.changed();
            });
        }).tooltip(Tooltip.create(DevUi.text("text.open_window"))).bounds(right + rightW - 18, rowTop - 1, 18, CONTROL_H).build());
    }

    /**
     * Where a jump goes, chosen first (November 6, 2026; before, two fields side by side, "to another dialogue" and "to
     * the node", and which one counted was unclear): "Within this dialogue ▸" leaves the node alone to pick (its arrow on
     * the graph), "To another dialogue ▸" asks for the dialogue, then the node in it (empty: its start). Switching
     * empties both, as a node of one dialogue means nothing in another.
     *
     * @return whether the dialogue's field is shown (to another dialogue)
     */
    boolean jumpModeField(List<Object> path, @Nullable JsonObject node) {
        String key = with(path, "jump").toString();
        boolean written = node != null && node.get("dialogue") instanceof JsonPrimitive dialogue && !dialogue.getAsString().isBlank();
        boolean elsewhere = written || "other".equals(entityModes.get(key));
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("jump.where"));
            Button switcher = Button.builder(Component.literal(DevUi.str(elsewhere ? "jump.mode.other" : "jump.mode.here") + " \u25B8"), b -> {
                JsonObject target = ensureObject(path);
                // Each way's dialogue and node are kept to give back when it is switched to again.
                String from = elsewhere ? "other" : "here";
                String to = elsewhere ? "here" : "other";
                List<Object> fromPath = with(path, from);
                List<Object> toPath = with(path, to);
                putAway(fromPath, target, "dialogue");
                putAway(fromPath, target, "node");
                giveBack(toPath, target, "dialogue");
                giveBack(toPath, target, "node");
                entityModes.put(key, to);
                host.changed();
            }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
            switcher.setTooltip(Tooltip.create(DevUi.text("jump.mode.tooltip")));
            screen.add(switcher);
        }
        return elsewhere;
    }

    /** {@code stage_2} for the second stage, never one the list already has. */
    static String freeId(JsonArray list, String field) {
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
}
