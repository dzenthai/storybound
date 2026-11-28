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
     * A dialogue's own settings (its lines are on the graph) in the order its author asks: how it shows, with the
     * camera only on the dialogue screen; who speaks; what an interruption does.
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

    /** Shows fields relevant to the participant's kind: bound, live entity nearby, or virtual narrator. */
    static boolean participantFieldShown(String name, @Nullable JsonObject participant) {
        String type = participant != null && participant.get("type") instanceof JsonPrimitive written ? written.getAsString() : "bound";
        boolean virtual = type.equals("virtual");
        boolean entity = type.equals("entity");
        return switch (name) {
            case "npc", "entity", "radius" -> entity;
            case "name" -> virtual || entity || participant != null && participant.has("name");
            case "portrait" -> virtual || participant != null && participant.has("portrait");
            default -> true;
        };
    }

    /**
     * Participant kind switcher ({@code bound}, {@code entity}, {@code virtual}): stashes kind-specific fields when
     * switching away and restores them if switched back, so the JSON stays clean and valid.
     */
    void participantTypeField(List<Object> path, Node node, @Nullable JsonObject participant) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(fieldLabel("type", true));
        List<String> types = List.of("bound", "entity", "virtual");
        String current = participant != null && participant.get("type") instanceof JsonPrimitive written ? written.getAsString() : "bound";
        Button button = Button.builder(enumLabel("type", current), b -> {
            JsonObject target = ensureObject(path);
            String next = nextChoice(types, current, "bound");
            if (current.equals("entity")) {
                putAway(with(path, "type_entity"), target, "npc");
                putAway(with(path, "type_entity"), target, "entity");
                putAway(with(path, "type_entity"), target, "radius");
            } else if (current.equals("virtual")) {
                putAway(with(path, "type_virtual"), target, "portrait");
            }
            target.addProperty("type", next);
            if (next.equals("entity")) {
                giveBack(with(path, "type_entity"), target, "npc");
                giveBack(with(path, "type_entity"), target, "entity");
                giveBack(with(path, "type_entity"), target, "radius");
            } else if (next.equals("virtual")) {
                giveBack(with(path, "type_virtual"), target, "portrait");
            }
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        String description = !node.description().isEmpty() ? node.description() : Schemas.resolve(node).description();
        if (!description.isEmpty()) {
            button.setTooltip(Tooltip.create(Component.literal(described(description))));
        }
        screen.add(button);
    }

    /**
     * Source switcher for a live {@code entity} participant: either an existing Storybound NPC binding ({@code npc}) or
     * a custom entity selector ({@code entity}). Keeps them mutually exclusive while preserving cleared values.
     */
    void participantEntityFields(List<Object> path, Node schema, @Nullable JsonObject participant) {
        String modeKey = with(path, "participant_source").toString();
        boolean hasNpc = participant != null && participant.has("npc");
        boolean hasEntity = participant != null && participant.has("entity");
        if (hasEntity && !hasNpc) {
            entityModes.put(modeKey, "entity");
        } else if (hasNpc) {
            entityModes.put(modeKey, "npc");
        }
        String mode = entityModes.getOrDefault(modeKey, "npc");
        boolean byEntity = mode.equals("entity");
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("dialogue.participant.source"));
            Button switcher = Button.builder(Component.literal(DevUi.str("dialogue.participant.source." + mode) + " \u25B8"), b -> {
                JsonObject target = ensureObject(path);
                if (byEntity) {
                    putAway(with(path, "src_entity"), target, "entity");
                    giveBack(with(path, "src_npc"), target, "npc");
                    entityModes.put(modeKey, "npc");
                } else {
                    putAway(with(path, "src_npc"), target, "npc");
                    giveBack(with(path, "src_entity"), target, "entity");
                    entityModes.put(modeKey, "entity");
                }
                host.changed();
            }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
            switcher.setTooltip(Tooltip.create(DevUi.text("dialogue.participant.source.tooltip")));
            screen.add(switcher);
        }
        Map<String, Node> props = Schemas.properties(schema);
        if (byEntity && props.containsKey("entity")) {
            field(path, "entity", props.get("entity"), true, schema);
        } else if (!byEntity && props.containsKey("npc")) {
            field(path, "npc", props.get("npc"), true, schema);
        }
    }

    /**
     * Who speaks besides the player, in words: "npc (started with) · guard (NPC nearby: elder) · narrator «Narrator»".
     * Without any written, the default: the entity the dialogue was started with.
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
        String type = participant.get("type") instanceof JsonPrimitive written ? written.getAsString() : "bound";
        String name = ClientDevStore.readStoryText(participant, "name", "");
        if (type.equals("entity")) {
            String target = participant.get("npc") instanceof JsonPrimitive npc && !npc.getAsString().isBlank()
                    ? DevUi.shortId(npc.getAsString())
                    : participant.get("entity") instanceof JsonObject selector ? entitySummary(selector) : "";
            String detail = target.isBlank() || target.equals("—")
                    ? DevUi.str("dialogue.participant.entity")
                    : DevUi.str("dialogue.participant.entity") + ": " + target;
            return key + " (" + detail + (name.isBlank() ? "" : " «" + name + "»") + ")";
        }
        boolean virtual = type.equals("virtual");
        return key + " (" + DevUi.str(virtual ? "dialogue.participant.virtual" : "dialogue.participant.bound")
                + (name.isBlank() ? "" : " «" + name + "»") + ")";
    }

    /**
     * The nodes of a dialogue's graph and a choice's options, in the order their author asks: what is said and where it
     * goes first; then, in groups, the rest. A line's camera shows only where it turns (the dialogue screen with the
     * camera on).
     */
    static final Map<String, List<String>> NODE_ORDER = Map.ofEntries(
            Map.entry("participant", List.of("type", "npc", "entity", "radius", "name", "portrait")),
            Map.entry("node.line", List.of("speaker", "to", "text", "next", "duration", "resume", "shot", "transition", "look_at", "fade", "sound")),
            Map.entry("node.jump", List.of("dialogue", "node")),
            Map.entry("option", List.of("text", "next", "conditions", "locked", "locked_text", "actions", "once", "think", "id")),
            // A document.
            Map.entry("document", List.of("title", "kind", "custom_model_data", "style", "journal", "loot", "on_read", "contract", "content")),
            Map.entry("document.loot", List.of("dimension", "biome", "structure", "tables", "chance", "once", "conditions")),
            Map.entry("journal", List.of("add_on_read", "category")),
            // A quest: what it is; how it goes; what it gives and changes; for whom. Then its stages and their
            // objectives.
            Map.entry("quest", List.of("title", "description", "text", "screen_text", "category", "difficulty", "start", "stages", "time_limit", "fail_when",
                    "rewards", "on_start", "on_complete", "on_failed", "scope", "repeatable", "notify")),
            Map.entry("stage", List.of("id", "log", "objectives", "next", "waypoints", "on_enter")),
            Map.entry("objective", List.of("id", "text", "trigger", "conditions", "count", "items", "since_stage", "optional", "hidden", "hint", "hints")),
            // A boss and a Codex entry: who or what it is; its sections in the tab, each with its text and when it
            // opens; what a victory does.
            Map.entry("boss", List.of("title", "entity", "notify", "lore", "tactics", "rewards", "participation", "on_first_kill", "on_kill")),
            Map.entry("codex", List.of("title", "category", "entity", "place", "image", "photo", "show_kills", "notify", "sections")),
            Map.entry("codex.place", List.of("dimension", "biome", "structure", "poi", "radius", "area")),
            Map.entry("codex.section", List.of("title", "text", "unlock", "id")),
            Map.entry("unlock", List.of("on", "count", "conditions")),
            // A story event, a script block, a flag: when and what; what the block is and where it leads, its file
            // last; what the flag holds, then where.
            Map.entry("event", List.of("trigger", "conditions", "once", "cooldown", "actions", "on_fail")),
            // A scene: its shots; how it is watched; what follows. A shot: against what, the flight, what happens.
            Map.entry("scene", List.of("shots", "letterbox", "music", "skippable", "world", "invulnerable", "on_end")),
            Map.entry("scene.shot", List.of("anchor", "fallback", "dialogue", "camera", "during", "transition")),
            Map.entry("scene.camera", List.of("from", "via", "to", "look", "rotations", "look_offset", "duration", "speed", "ease", "fov", "fov_to", "roll", "roll_to", "shake", "above_ground", "safe_angle")),
            Map.entry("script", List.of("title", "description", "hidden", "leads_to", "file", "hook")),
            Map.entry("flag", List.of("description", "type", "default", "scope", "sync")),
            // The trigger "observed": what is looked at, how long and from how far, what the bar says.
            Map.entry("observed", List.of("entity", "block", "pos", "item", "dimension", "time", "distance", "text")),
            // World Setup rules.
            Map.entry("world.item", List.of("title", "items", "unlock", "block_use", "block_attack", "block_equip",
                    "block_craft", "block_pickup", "hide_in_jei", "message")),
            Map.entry("world.mob", List.of("title", "entities", "dimension", "biome", "structure", "unlock",
                    "despawn_existing", "block_spawners", "replace_with")),
            Map.entry("world.place", List.of("title", "dimension", "biome", "structure", "unlock", "mode",
                    "effects", "damage", "message")),
            Map.entry("world.loot", List.of("title", "operation", "target", "item", "min_count", "max_count",
                    "components", "dimension", "biome", "structure", "tables", "entities", "chance", "once", "conditions")));

    /**
     * The headings of a form's groups, each at its first field. A small page (a dialogue's jump, a condition) has none:
     * its title says what it is. A lone field joins a neighbouring group rather than standing under a heading of its
     * own, except a technical one at the end ("Other").
     */
    static final Map<String, Map<String, String>> NODE_GROUPS = Map.ofEntries(
            Map.entry("boss", Map.of("title", "boss.group.who", "participation", "boss.group.victory")),
            Map.entry("codex", Map.of("title", "codex.group.entry")),
            Map.entry("node.line", Map.of("speaker", "node.group.line", "duration", "node.group.time", "shot", "node.group.camera")),
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
                    "loot", "document.group.loot", "on_read", "document.group.read", "contract", "document.group.contract", "content", "document.group.other")),
            Map.entry("document.loot", Map.of("chance", "document.loot.group.how")),
            Map.entry("quest", Map.of("title", "quest.group.about", "start", "quest.group.flow", "rewards", "quest.group.reward", "scope", "quest.group.who")),
            Map.entry("stage", Map.of("id", "stage.group.stage", "objectives", "stage.group.objectives", "waypoints", "stage.group.during")),
            Map.entry("objective", Map.of("id", "objective.group.what", "trigger", "objective.group.counts", "optional", "objective.group.shown")),
            Map.entry("event", Map.of("trigger", "event.group.when", "actions", "event.group.does")),
            Map.entry("scene", Map.of("shots", "scene.group.shots", "letterbox", "scene.group.watching", "on_end", "scene.group.after")),
            Map.entry("script", Map.of("title", "script.group.block", "leads_to", "script.group.leads", "file", "script.group.file")),
            Map.entry("flag", Map.of("description", "flag.group.what", "scope", "flag.group.where")),
            Map.entry("chapter", Map.of("title", "chapter.group.chapter")),
            Map.entry("world.item", Map.of("title", "world.group.what", "unlock", "world.group.unlock",
                    "block_use", "world.group.restrictions", "message", "world.group.feedback")),
            Map.entry("world.mob", Map.of("title", "world.group.what", "dimension", "world.group.where",
                    "unlock", "world.group.unlock", "despawn_existing", "world.group.behavior")),
            Map.entry("world.place", Map.of("title", "world.group.where", "unlock", "world.group.unlock",
                    "mode", "world.group.behavior", "message", "world.group.feedback")),
            Map.entry("world.loot", Map.of("title", "world.group.loot_what", "dimension", "world.group.where",
                    "chance", "world.group.loot_how")));

    static boolean nodeFieldShown(String form, String name, @Nullable JsonObject object, @Nullable JsonObject dialogue) {
        if (form.equals("node.line") && name.equals("sound")) {
            // The voice-over is the 🎤 beside the line's text.
            return false;
        }
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
        if (form.equals("codex") && name.equals("photo")) {
            // Automatic for place entries; only reveal in the form if explicitly set in JSON.
            return object != null && object.has("photo");
        }
        if (form.equals("codex.place") && name.equals("radius")) {
            // Radius only applies to points of interest.
            return object != null && (object.has("poi") || object.has("radius"));
        }
        if (form.equals("unlock") && name.equals("count")) {
            // A read counts once, an action opens at once: only sightings and kills are counted.
            List<String> sources = stringsOf(object != null ? object.get("on") : null);
            return sources.isEmpty() || sources.contains("seen") || sources.contains("killed") || object.has("count");
        }
        if (form.equals("world.place") && (name.equals("effects") || name.equals("damage"))) {
            boolean hazard = object != null && object.get("mode") instanceof JsonPrimitive m && m.getAsString().equals("hazard");
            return hazard || object != null && object.has(name);
        }
        if (form.equals("world.loot")) {
            String op = object != null && object.get("operation") instanceof JsonPrimitive p ? p.getAsString() : "add";
            if (name.equals("target")) {
                return op.equals("replace") || op.equals("remove");
            }
            if (name.equals("item") || name.equals("min_count") || name.equals("max_count") || name.equals("components")) {
                return op.equals("add") || op.equals("replace");
            }
        }
        return true;
    }

    /**
     * One line of a phrases node: its text, with \u270E for the text window. Text only: an NPC starts a dialogue itself.
     */
    /** A phrase's text: the line itself, or its {@code text} when the line has a voice-over. */
    private static String readPhrase(JsonArray array, int index, String language) {
        if (array.get(index) instanceof JsonObject voiced && voiced.has("text")) {
            return ClientDevStore.readStoryText(voiced, "text", "", language);
        }
        return ClientDevStore.readStoryTextAt(array, index, language);
    }

    private static void writePhrase(JsonArray array, int index, String text, String key) {
        if (array.get(index) instanceof JsonObject voiced && voiced.has("text")) {
            String existing = ClientDevStore.getKey(voiced, "text");
            ClientDevStore.writeStoryText(voiced, "text", text, existing != null && !existing.isBlank() ? existing : key);
        } else {
            ClientDevStore.writeStoryTextAt(array, index, text, key);
        }
    }

    /** The phrase as an object holding its voice-over: the line becomes {@code { "text": ... }} if it is a text alone. */
    private JsonObject phraseHolder(List<Object> path, int index) {
        JsonArray list = ensureArray(path);
        if (index < 0 || index >= list.size()) {
            return new JsonObject();
        }
        if (list.get(index) instanceof JsonObject object && object.has("text")) {
            return object;
        }
        JsonObject voiced = new JsonObject();
        voiced.add("text", list.get(index));
        list.set(index, voiced);
        return voiced;
    }

    /** A phrase whose voice-over went is its text alone again. */
    private void unvoiceIfBare(List<Object> path, int index) {
        JsonArray list = ensureArray(path);
        if (index < list.size() && list.get(index) instanceof JsonObject object && object.has("text") && !object.has("sound") && object.size() == 1) {
            list.set(index, object.get("text"));
        }
    }

    void phraseRow(List<Object> path, String field, JsonArray array, int index, int rowY, int width) {
        int right = x;
        int rightW = width;
        String key = listKey(path, field) + "." + index;
        JsonObject voiced = array.get(index) instanceof JsonObject object && object.has("text") ? object : null;
        int voiceCols = hasVoiceOver(voiced) ? 40 : 20;
        EditBox box = new EditBox(screen.font(), right, rowY + 1, rightW - 20 - voiceCols, CONTROL_H - 2, Component.empty());
        box.setMaxLength(1024);
        box.setValue(readPhrase(array, index, ClientDevStore.activeLanguage()));
        String source = readPhrase(array, index, ClientDevStore.sourceLanguage());
        boolean translating = !ClientDevStore.activeLanguage().equalsIgnoreCase(ClientDevStore.sourceLanguage());
        if (translating) {
            box.setHint(Component.literal(source));
        }
        box.setResponder(text -> {
            JsonArray list = ensureArray(path);
            if (index < list.size()) {
                writePhrase(list, index, text, key);
                host.edited();
            }
        });
        screen.add(box);
        voiceOverButton(voiced, () -> phraseHolder(path, index),
                () -> {
                    unvoiceIfBare(path, index);
                    host.changed();
                }, right + rightW - 38, rowY);
        screen.add(Button.builder(Component.literal("\u270E"), b -> {
            JsonArray list = ensureArray(path);
            String current = index < list.size() ? readPhrase(list, index, ClientDevStore.activeLanguage()) : "";
            screen.textEditor().openPlain(fieldLabel(field, false), current,
                    translating && !source.isBlank() ? Component.literal(source) : DevUi.text("text.hint_plain"), edited -> {
                        JsonArray target = ensureArray(path);
                        if (index < target.size()) {
                            writePhrase(target, index, edited, key);
                            host.changed();
                        }
                    });
        }).tooltip(Tooltip.create(DevUi.text("text.open_window"))).bounds(right + rightW - 18, rowY, 18, CONTROL_H).build());
    }

    /**
     * An NPC's phrases, for its chatter, a click or when it is hit, as an entity's "known by": at the left a switch,
     * "own text" or "phrases node"; at its right, a text written there (with \u270E for the text window) or a button
     * that picks one of the story's phrases nodes. Where a dialogue may stand instead (a click entry, a hit) the switch
     * has a third way, "dialogue", which writes the owner's {@code dialogue} in place of its phrases; a click entry
     * starts on it. Switching empties the field; it is written once filled.
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
                        JsonObject target = ensureObject(path);
                        if (orDialogue) {
                            target.remove("dialogue");
                        }
                        target.addProperty(name, entry.id());
                        host.changed();
                    });
                }
            }).bounds(right, rowTop, rightW - 20, CONTROL_H).build();
            choose.setTooltip(Tooltip.create(DevUi.text("phrases.choose_tooltip")));
            screen.add(choose);
            if (id != null) {
                net.minecraft.resources.ResourceLocation parsed = net.minecraft.resources.ResourceLocation.tryParse(id);
                Button open = Button.builder(Component.literal("↗"), b -> {
                    if (parsed != null) {
                        host.openNode(new dev.dzenthai.storybound.core.story.StoryLinks.NodeKey(ClientDevStore.TYPE_PHRASES, parsed));
                    }
                }).bounds(right + rightW - 18, rowTop, 18, CONTROL_H)
                        .tooltip(Tooltip.create(DevUi.text("phrases.open_node"))).build();
                open.active = parsed != null && ClientDevStore.getJson(ClientDevStore.TYPE_PHRASES, parsed) != null;
                screen.add(open);
            } else {
                screen.add(Button.builder(Component.literal("+"), b -> {
                    net.minecraft.resources.ResourceLocation created = host.createPhrases();
                    if (created != null) {
                        JsonObject target = ensureObject(path);
                        if (orDialogue) {
                            target.remove("dialogue");
                        }
                        target.addProperty(name, created.toString());
                        host.changed();
                        host.openNode(new dev.dzenthai.storybound.core.story.StoryLinks.NodeKey(ClientDevStore.TYPE_PHRASES, created));
                    }
                }).bounds(right + rightW - 18, rowTop, 18, CONTROL_H)
                        .tooltip(Tooltip.create(DevUi.text("phrases.create_tooltip"))).build());
            }
            return;
        }
        // An own text: { "text": ..., "sound": ... } in the field, the text under a key of its own, in the active language.
        String key = host.textKey(keyPath(at), "text");
        JsonObject own = value instanceof JsonObject object ? object : null;
        int voiceCols = hasVoiceOver(own) ? 40 : 20;
        EditBox box = new EditBox(screen.font(), right, rowTop + 1, Math.max(36, rightW - 20 - voiceCols), CONTROL_H - 2, label);
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
        voiceOverButton(own, () -> ensureObject(at), host::changed, right + rightW - 38, rowTop);
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
        }).tooltip(Tooltip.create(DevUi.text("text.open_window"))).bounds(right + rightW - 18, rowTop, 18, CONTROL_H).build());
    }

    /**
     * Where a jump goes, chosen first: "Within this dialogue ▸" leaves the node alone to pick (its arrow on the graph),
     * "To another dialogue ▸" asks for the dialogue, then the node in it (empty: its start). Switching empties both, as
     * a node of one dialogue means nothing in another.
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
