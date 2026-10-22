package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dzenthai.storybound.client.dev.ClientDevStore.NodeKey;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The NPC who gives a quest and what it says around it, as games do: one dialogue before the quest (the one that
 * gives it), another while it is active, another after it. These are entries of the NPC binding with a condition on
 * the quest's state and a priority (after above during above before), so the binding picks the right one; nothing
 * else in the story changes. Pure JSON, no GUI.
 */
final class QuestGiver {
    private QuestGiver() {}

    /** A part of the quest's life, with the quest state its entry asks for and its priority. */
    enum Phase {
        BEFORE("unknown", 0),
        DURING("active", 10),
        AFTER("completed", 20);

        final String state;
        final int priority;

        Phase(String state, int priority) {
            this.state = state;
            this.priority = priority;
        }
    }

    /**
     * @param npc       the binding whose dialogue gives the quest
     * @param dialogues what it says in each phase, where it has an entry for it
     */
    record Giver(NodeKey npc, Map<Phase, ResourceLocation> dialogues) {}

    /** The first NPC binding with a dialogue that starts the quest, and its dialogues by phase; null when none. */
    static @Nullable Giver find(ResourceLocation quest) {
        for (Map.Entry<ResourceLocation, String> binding : ClientDevStore.byType(ClientDevStore.TYPE_NPC).entrySet()) {
            JsonObject json = ClientDevStore.parseObject(binding.getValue());
            if (offering(json, quest).isPresent()) {
                return new Giver(new NodeKey(ClientDevStore.TYPE_NPC, binding.getKey()), phases(json, quest));
            }
        }
        return null;
    }

    /** The binding's entries by phase: those asking for the quest's state alone, and the one giving the quest as "before". */
    static Map<Phase, ResourceLocation> phases(JsonObject binding, ResourceLocation quest) {
        Map<Phase, ResourceLocation> phases = new EnumMap<>(Phase.class);
        for (JsonObject entry : entries(binding)) {
            Phase phase = phaseOf(entry, quest);
            ResourceLocation dialogue = dialogueOf(entry);
            if (phase != null && dialogue != null) {
                phases.putIfAbsent(phase, dialogue);
            }
        }
        offering(binding, quest).flatMap(entry -> Optional.ofNullable(dialogueOf(entry))).ifPresent(dialogue -> phases.putIfAbsent(Phase.BEFORE, dialogue));
        return phases;
    }

    /**
     * Adds the entry saying the dialogue in that phase. The entry giving the quest, when it asks for nothing yet, gets
     * "the quest is unknown": otherwise it would offer the quest again whenever no other entry speaks.
     */
    static void addEntry(JsonObject binding, ResourceLocation dialogue, ResourceLocation quest, Phase phase) {
        JsonArray entries = StoryEdits.array(binding, "dialogues");
        offering(binding, quest).filter(entry -> !entry.has("conditions")).ifPresent(entry -> entry.add("conditions", questIs(quest, Phase.BEFORE)));
        JsonObject entry = new JsonObject();
        entry.addProperty("dialogue", dialogue.toString());
        entry.addProperty("priority", phase.priority);
        entry.add("conditions", questIs(quest, phase));
        entries.add(entry);
    }

    private static JsonObject questIs(ResourceLocation quest, Phase phase) {
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "quest");
        condition.addProperty("quest", quest.toString());
        condition.addProperty("state", phase.state);
        return condition;
    }

    /** The phase an entry stands for: its conditions are the quest's state alone (one condition, or a list of one). */
    static @Nullable Phase phaseOf(JsonObject entry, ResourceLocation quest) {
        JsonElement conditions = entry.get("conditions");
        if (conditions != null && conditions.isJsonArray() && conditions.getAsJsonArray().size() == 1) {
            conditions = conditions.getAsJsonArray().get(0);
        }
        if (conditions == null || !conditions.isJsonObject()) {
            return null;
        }
        JsonObject condition = conditions.getAsJsonObject();
        if (!isType(condition, "quest") || !quest.toString().equals(string(condition, "quest")) || condition.has("stage")) {
            return null;
        }
        String state = string(condition, "state");
        for (Phase phase : Phase.values()) {
            if (phase.state.equals(state)) {
                return phase;
            }
        }
        return null;
    }

    /** The binding's entry whose dialogue starts the quest. */
    private static Optional<JsonObject> offering(JsonObject binding, ResourceLocation quest) {
        for (JsonObject entry : entries(binding)) {
            ResourceLocation dialogue = dialogueOf(entry);
            if (dialogue != null && startsQuest(ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_DIALOGUE, dialogue)), quest)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Whether the JSON has an action starting the quest: {@code set_quest_state} active or {@code set_quest_stage}. */
    static boolean startsQuest(JsonElement element, ResourceLocation quest) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (startsQuest(child, quest)) {
                    return true;
                }
            }
            return false;
        }
        if (!element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        if (quest.toString().equals(string(object, "quest"))
                && (isType(object, "set_quest_state") && "active".equals(string(object, "state")) || isType(object, "set_quest_stage"))) {
            return true;
        }
        for (JsonElement child : object.asMap().values()) {
            if (startsQuest(child, quest)) {
                return true;
            }
        }
        return false;
    }

    private static Iterable<JsonObject> entries(JsonObject binding) {
        List<JsonObject> entries = new ArrayList<>();
        if (binding.has("dialogues") && binding.get("dialogues").isJsonArray()) {
            binding.getAsJsonArray("dialogues").forEach(element -> {
                if (element.isJsonObject()) {
                    entries.add(element.getAsJsonObject());
                }
            });
        }
        return entries;
    }

    private static @Nullable ResourceLocation dialogueOf(JsonObject entry) {
        String id = string(entry, "dialogue");
        return id != null ? ResourceLocation.tryParse(id) : null;
    }

    private static boolean isType(JsonObject object, String type) {
        String value = string(object, "type");
        return value != null && DevUi.shortId(value).equals(type);
    }

    private static @Nullable String string(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonPrimitive() ? object.get(field).getAsString() : null;
    }
}
