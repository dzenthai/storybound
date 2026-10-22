package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * JSON edits the dev mode makes outside the inspector's forms: the links drawn between nodes on the story canvas
 * (and taking them out again), and what templates need. Texts go through {@link ClientDevStore#writeStoryText}.
 */
final class StoryEdits {
    private StoryEdits() {}

    /** The {@code entity} selector, attached to the object. */
    static JsonObject entity(JsonObject owner) {
        if (!owner.has("entity") || !owner.get("entity").isJsonObject()) {
            owner.add("entity", new JsonObject());
        }
        return owner.getAsJsonObject("entity");
    }

    /** Sets the entity type; an entity without a tag gets {@code defaultTag}, so the selector matches only the tagged one. */
    static void setEntityType(JsonObject owner, String type, String defaultTag) {
        JsonObject entity = entity(owner);
        entity.addProperty("type", type);
        if (!entity.has("tag") || entity.get("tag").getAsString().isBlank()) {
            entity.addProperty("tag", defaultTag);
        }
    }

    /** A waypoint's search radius; 0 for a point. */
    static double radius(JsonObject waypoint) {
        return waypoint.has("radius") && waypoint.get("radius").isJsonPrimitive() ? waypoint.get("radius").getAsDouble() : 0;
    }

    /** {@code [x, y, z]} in whole blocks; the origin at sea level without a position. */
    static JsonArray position(double @Nullable [] here) {
        JsonArray pos = new JsonArray();
        if (here == null) {
            pos.add(0);
            pos.add(64);
            pos.add(0);
        } else {
            for (double value : here) {
                pos.add(Math.round(value));
            }
        }
        return pos;
    }

    // --- dialogues

    private static boolean isType(JsonElement node, String type) {
        return node.isJsonObject() && node.getAsJsonObject().has("type") && type.equals(node.getAsJsonObject().get("type").getAsString());
    }

    /** The dialogue ends by starting the quest. */
    static void addQuestStart(JsonObject dialogue, ResourceLocation quest) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "set_quest_state");
        action.addProperty("quest", quest.toString());
        action.addProperty("state", "active");
        addEnding(dialogue, "start_quest_" + quest.getPath(), action);
    }

    /** The dialogue ends by handing over the document. */
    static void addGiveDocument(JsonObject dialogue, ResourceLocation document) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "give_document");
        action.addProperty("document", document.toString());
        addEnding(dialogue, "give_doc_" + document.getPath(), action);
    }

    /**
     * The conversation ends doing the action: an end node the start reaches gets it in its actions (so a second link
     * adds to the ending instead of replacing the first one), else a new end node comes after the last line.
     */
    private static void addEnding(JsonObject dialogue, String nodeId, JsonObject action) {
        JsonObject reached = reachedEnd(dialogue);
        if (reached != null) {
            JsonArray actions = array(reached, "actions");
            if (!actions.contains(action)) {
                actions.add(action);
            }
            return;
        }
        JsonObject end = new JsonObject();
        end.addProperty("type", "end");
        JsonArray actions = new JsonArray();
        actions.add(action);
        end.add("actions", actions);
        continueAt(dialogue, nodeId, end);
    }

    /** The first end node on the way from the start (breadth first); null when the start reaches none. */
    private static @Nullable JsonObject reachedEnd(JsonObject dialogue) {
        JsonObject nodes = DialogueGraph.nodes(dialogue);
        Deque<String> queue = new ArrayDeque<>(List.of(DialogueGraph.start(dialogue)));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id) || !nodes.has(id) || !nodes.get(id).isJsonObject()) {
                continue;
            }
            JsonObject node = nodes.getAsJsonObject(id);
            if (isType(node, "end")) {
                return node;
            }
            DialogueGraph.targets(node, queue::add);
        }
        return null;
    }

    /**
     * Adds the node and leads the conversation's end to it: the first line or action that ends the dialogue now
     * points to the new node, so the rest of the conversation stays as it was. Without one, the start node does.
     */
    private static void continueAt(JsonObject dialogue, String nodeId, JsonObject node) {
        JsonObject nodes = DialogueGraph.nodes(dialogue);
        JsonObject from = null;
        for (JsonElement candidate : nodes.asMap().values()) {
            if ((isType(candidate, "line") || isType(candidate, "action")) && DialogueGraph.END.equals(DialogueGraph.next(candidate.getAsJsonObject()))) {
                from = candidate.getAsJsonObject();
                break;
            }
        }
        String start = DialogueGraph.start(dialogue);
        if (from == null && nodes.has(start) && nodes.get(start).isJsonObject()) {
            from = nodes.getAsJsonObject(start);
        }
        nodes.add(nodeId, node);
        if (from != null && from != node) {
            from.addProperty("next", nodeId);
        }
    }

    // --- links on the story canvas

    /**
     * Writes the link drawn from one node to another into the first one's JSON: an NPC talks with a dialogue, a
     * document gives a quest, a quest stage shows a waypoint, a quest leads to the next one or opens a boss entry, a
     * dialogue starts a quest or hands over a document, a node runs a script, a script leads to a node.
     *
     * @return false for a pair that has no such link (it takes conditions or actions written in the inspector)
     */
    static boolean link(JsonObject from, NodeKey source, NodeKey target) {
        ResourceLocation a = source.type();
        ResourceLocation b = target.type();
        String id = target.id().toString();
        if (a.equals(ClientDevStore.TYPE_NPC) && b.equals(ClientDevStore.TYPE_DIALOGUE)) {
            JsonArray entries = array(from, "dialogues");
            JsonObject entry = new JsonObject();
            entry.addProperty("dialogue", id);
            entry.addProperty("priority", entries.size() * 10);
            entries.add(entry);
        } else if (a.equals(ClientDevStore.TYPE_DOCUMENT) && b.equals(ClientDevStore.TYPE_QUEST)) {
            JsonObject contract = from.has("contract") && from.get("contract").isJsonObject() ? from.getAsJsonObject("contract") : new JsonObject();
            contract.addProperty("quest", id);
            from.add("contract", contract);
        } else if (a.equals(ClientDevStore.TYPE_QUEST) && b.equals(ClientDevStore.TYPE_WAYPOINT)) {
            JsonArray stages = array(from, "stages");
            if (stages.isEmpty()) {
                JsonObject stage = new JsonObject();
                stage.addProperty("id", "start");
                stages.add(stage);
            }
            array(stages.get(0).getAsJsonObject(), "waypoints").add(id);
        } else if (a.equals(ClientDevStore.TYPE_QUEST) && b.equals(ClientDevStore.TYPE_QUEST)) {
            JsonObject action = new JsonObject();
            action.addProperty("type", "set_quest_state");
            action.addProperty("quest", id);
            action.addProperty("state", "active");
            array(from, "on_complete").add(action);
        } else if (a.equals(ClientDevStore.TYPE_QUEST) && b.equals(ClientDevStore.TYPE_BOSS)) {
            JsonObject action = new JsonObject();
            action.addProperty("type", "unlock_boss_section");
            action.addProperty("boss", id);
            action.addProperty("section", "lore");
            array(from, "on_start").add(action);
        } else if (a.equals(ClientDevStore.TYPE_DIALOGUE) && b.equals(ClientDevStore.TYPE_QUEST)) {
            addQuestStart(from, target.id());
        } else if (a.equals(ClientDevStore.TYPE_DIALOGUE) && b.equals(ClientDevStore.TYPE_DOCUMENT)) {
            addGiveDocument(from, target.id());
        } else if (b.equals(ClientDevStore.TYPE_SCRIPT)) {
            return runScript(from, a, ClientDevStore.scriptHook(target.id()));
        } else if (a.equals(ClientDevStore.TYPE_SCRIPT)) {
            JsonArray leadsTo = array(from, "leads_to");
            for (JsonElement element : leadsTo) {
                if (element.isJsonObject() && sameTarget(element.getAsJsonObject(), target)) {
                    return true;
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("type", DevUi.shortId(b.toString()));
            entry.addProperty("id", id);
            leadsTo.add(entry);
        } else {
            return false;
        }
        return true;
    }

    /**
     * The node runs the script: the dialogue at its end, the quest on completion, the document on its first read, the
     * story event with its actions, the boss on every victory.
     */
    private static boolean runScript(JsonObject from, ResourceLocation type, ResourceLocation hook) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "kubejs");
        action.addProperty("id", hook.toString());
        if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            addEnding(from, "script_" + hook.getPath().replace('/', '_'), action);
            return true;
        }
        String field = type.equals(ClientDevStore.TYPE_QUEST) ? "on_complete" : type.equals(ClientDevStore.TYPE_DOCUMENT) ? "on_read"
                : type.equals(ClientDevStore.TYPE_EVENT) ? "actions" : type.equals(ClientDevStore.TYPE_BOSS) ? "on_kill" : null;
        if (field == null) {
            return false;
        }
        array(from, field).add(action);
        return true;
    }

    /**
     * Points the actions and conditions "Script" of {@code from} to {@code to}: a script block's handler changed, so
     * what ran the old one runs the new one.
     *
     * @return whether anything changed
     */
    static boolean renameScriptHook(JsonElement element, String from, String to) {
        boolean changed = false;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                changed |= renameScriptHook(child, from, to);
            }
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("type") && object.get("type").isJsonPrimitive() && DevUi.shortId(object.get("type").getAsString()).equals("kubejs")
                    && isId(object.get("id"), from)) {
                object.addProperty("id", to);
                changed = true;
            }
            for (JsonElement child : object.asMap().values()) {
                changed |= renameScriptHook(child, from, to);
            }
        }
        return changed;
    }

    /** The handler a script block's JSON names: its {@code hook}, else the block's id. */
    static String hookOf(ResourceLocation block, JsonObject json) {
        return json.has("hook") && json.get("hook").isJsonPrimitive() && !json.get("hook").getAsString().isBlank()
                ? json.get("hook").getAsString() : block.toString();
    }

    /** A {@code leads_to} entry naming that node. */
    private static boolean sameTarget(JsonObject entry, NodeKey target) {
        ResourceLocation type = entry.has("type") ? DevUi.normalizeType(entry.get("type").getAsString()) : null;
        return target.type().equals(type) && isId(entry.get("id"), target.id().toString());
    }

    /** Actions that point at a definition of that type, by type and the field holding the id. */
    private static final Map<ResourceLocation, Set<String>> ACTIONS_TO = Map.of(
            ClientDevStore.TYPE_QUEST, Set.of("set_quest_state", "set_quest_stage", "track_quest"),
            ClientDevStore.TYPE_DIALOGUE, Set.of("start_dialogue"),
            ClientDevStore.TYPE_DOCUMENT, Set.of("give_document"),
            ClientDevStore.TYPE_BOSS, Set.of("unlock_boss_section"),
            ClientDevStore.TYPE_CODEX, Set.of("unlock_codex"),
            ClientDevStore.TYPE_SCRIPT, Set.of("kubejs"));
    private static final Map<ResourceLocation, String> ID_FIELD = Map.of(
            ClientDevStore.TYPE_QUEST, "quest",
            ClientDevStore.TYPE_DIALOGUE, "dialogue",
            ClientDevStore.TYPE_DOCUMENT, "document",
            ClientDevStore.TYPE_BOSS, "boss",
            ClientDevStore.TYPE_CODEX, "entry",
            ClientDevStore.TYPE_SCRIPT, "id");
    /** Fields holding conditions: a condition naming the target stays, even of a type that is also an action ("Script"). */
    private static final Set<String> CONDITION_FIELDS = Set.of("conditions", "condition", "fail_when");
    /** Fields holding a list of actions: a matching single action becomes an empty list, which every one of them takes. */
    private static final Set<String> ACTION_LISTS = Set.of("actions", "on_start", "on_complete", "on_failed", "on_read", "on_enter",
            "on_kill", "on_first_kill", "on_abort", "rewards");

    /**
     * Removes what in this definition points at the target, where that can go without breaking the definition:
     * the actions that start, give or unlock it, an NPC's entry for the dialogue, a stage's waypoint, a contract's
     * quest, and the objectives and quest starts that wait for something about it. Conditions and story event
     * triggers stay (they would change what the story means); the validator keeps reporting them.
     *
     * @return whether anything was removed
     */
    static boolean unlink(JsonObject definition, NodeKey target) {
        return unlinkIn(definition, target, idOf(target));
    }

    /** Whether the definition still points at the target anywhere (also where {@link #unlink} leaves it). */
    static boolean refersTo(JsonElement element, NodeKey target) {
        String field = ID_FIELD.get(target.type());
        String id = idOf(target);
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (refersTo(child, target)) {
                    return true;
                }
            }
            return false;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals(field) && isId(entry.getValue(), id) || refersTo(entry.getValue(), target)) {
                    return true;
                }
                if (target.type().equals(ClientDevStore.TYPE_WAYPOINT) && entry.getKey().equals("waypoints") && contains(entry.getValue(), id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean unlinkIn(JsonObject object, NodeKey target, String id) {
        boolean changed = false;
        String field = ID_FIELD.get(target.type());
        if (target.type().equals(ClientDevStore.TYPE_QUEST) && object.has("contract") && object.get("contract").isJsonObject()
                && isId(object.getAsJsonObject("contract").get("quest"), id)) {
            object.getAsJsonObject("contract").remove("quest");
            changed = true;
        }
        for (String key : List.copyOf(object.keySet())) {
            JsonElement value = object.get(key);
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                for (int i = array.size() - 1; i >= 0; i--) {
                    JsonElement element = array.get(i);
                    if (removable(key, element, target, field, id)) {
                        array.remove(i);
                        changed = true;
                    } else if (element.isJsonObject()) {
                        changed |= unlinkIn(element.getAsJsonObject(), target, id);
                    }
                }
            } else if (value.isJsonObject()) {
                if (removable(key, value, target, field, id)) {
                    if (ACTION_LISTS.contains(key)) {
                        object.add(key, new JsonArray());
                    } else {
                        object.remove(key);
                    }
                    changed = true;
                } else {
                    changed |= unlinkIn(value.getAsJsonObject(), target, id);
                }
            }
        }
        return changed;
    }

    /** How the definition is named in others: a script block by its handler. */
    private static String idOf(NodeKey target) {
        return target.type().equals(ClientDevStore.TYPE_SCRIPT) ? ClientDevStore.scriptHook(target.id()).toString() : target.id().toString();
    }

    /** Whether the element can go from the field it is in, because it is about the target. */
    private static boolean removable(String fieldName, JsonElement element, NodeKey target, @Nullable String idField, String id) {
        if (fieldName.equals("leads_to")) {
            return element.isJsonObject() && sameTarget(element.getAsJsonObject(), target);
        }
        if (target.type().equals(ClientDevStore.TYPE_WAYPOINT)) {
            return fieldName.equals("waypoints") && isId(element, id);
        }
        if (!element.isJsonObject() || idField == null) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("type") && object.get("type").isJsonPrimitive() && !CONDITION_FIELDS.contains(fieldName)
                && ACTIONS_TO.getOrDefault(target.type(), Set.of()).contains(DevUi.shortId(object.get("type").getAsString()))) {
            return isId(object.get(idField), id);
        }
        if (fieldName.equals("dialogues") && target.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            return isId(object.get("dialogue"), id);
        }
        // An objective or a quest start waiting for something about the target can never happen.
        return (fieldName.equals("objectives") || fieldName.equals("start")) && refersTo(object, target);
    }

    private static boolean isId(@Nullable JsonElement value, String id) {
        return value != null && value.isJsonPrimitive() && id.equals(value.getAsString());
    }

    private static boolean contains(JsonElement value, String id) {
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (isId(element, id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The array in that field, attached to the object; a single action object written by hand becomes the first
     * element, so nothing is lost.
     */
    static JsonArray array(JsonObject owner, String field) {
        JsonElement value = owner.get(field);
        if (value != null && value.isJsonArray()) {
            return value.getAsJsonArray();
        }
        JsonArray array = new JsonArray();
        if (value != null && value.isJsonObject()) {
            array.add(value);
        }
        owner.add(field, array);
        return array;
    }
}
