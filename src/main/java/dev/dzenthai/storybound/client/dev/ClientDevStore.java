package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.StoryIds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public final class ClientDevStore {
    public static final ResourceLocation TYPE_QUEST = Storybound.id("quest");
    public static final ResourceLocation TYPE_DIALOGUE = Storybound.id("dialogue");
    public static final ResourceLocation TYPE_NPC = Storybound.id("npc_binding");
    public static final ResourceLocation TYPE_DOCUMENT = Storybound.id("document");
    public static final ResourceLocation TYPE_WAYPOINT = Storybound.id("waypoint");
    public static final ResourceLocation TYPE_BOSS = Storybound.id("boss");
    public static final ResourceLocation TYPE_CODEX = Storybound.id("codex");
    public static final ResourceLocation TYPE_EVENT = Storybound.id("event");
    public static final ResourceLocation TYPE_FLAG = Storybound.id("flag");
    public static final ResourceLocation TYPE_CHAPTER = Storybound.id("chapter");

    private static volatile Map<ResourceLocation, Map<ResourceLocation, String>> definitions = Map.of();
    private static volatile List<String> issues = List.of();
    private static @Nullable Runnable listener;

    private ClientDevStore() {}

    public static void setSnapshot(Map<ResourceLocation, Map<ResourceLocation, String>> newDefinitions, List<String> newIssues) {
        Map<ResourceLocation, Map<ResourceLocation, String>> copy = new LinkedHashMap<>();
        newDefinitions.forEach((type, map) -> copy.put(type, Collections.unmodifiableMap(new LinkedHashMap<>(map))));
        definitions = Collections.unmodifiableMap(copy);
        issues = List.copyOf(newIssues);
        Runnable callback = listener;
        if (callback != null) {
            callback.run();
        }
    }

    public static void setListener(@Nullable Runnable callback) {
        listener = callback;
    }

    public static Map<ResourceLocation, Map<ResourceLocation, String>> all() {
        return definitions;
    }

    public static Map<ResourceLocation, String> byType(ResourceLocation type) {
        return definitions.getOrDefault(type, Map.of());
    }

    public static @Nullable String getJson(ResourceLocation type, ResourceLocation id) {
        return byType(type).get(id);
    }

    public static void putLocal(ResourceLocation type, ResourceLocation id, String json) {
        Map<ResourceLocation, Map<ResourceLocation, String>> copy = new LinkedHashMap<>();
        definitions.forEach((t, map) -> copy.put(t, new LinkedHashMap<>(map)));
        copy.computeIfAbsent(type, k -> new LinkedHashMap<>()).put(id, json);
        Map<ResourceLocation, Map<ResourceLocation, String>> immutable = new LinkedHashMap<>();
        copy.forEach((t, map) -> immutable.put(t, Collections.unmodifiableMap(map)));
        definitions = Collections.unmodifiableMap(immutable);
    }

    public static List<String> issues() {
        return issues;
    }

    public static List<String> issuesFor(ResourceLocation type, ResourceLocation id) {
        String needle = type + " " + id + ":";
        return issues.stream().filter(line -> line.contains(needle)).toList();
    }

    public static JsonObject parseObject(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    public static String readStoryText(JsonObject obj, String field, String fallback) {
        if (!obj.has(field)) {
            return fallback;
        }
        JsonElement el = obj.get(field);
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        if (el.isJsonObject()) {
            JsonObject textObj = el.getAsJsonObject();
            if (textObj.has("literal") && textObj.get("literal").isJsonPrimitive()) {
                return textObj.get("literal").getAsString();
            }
            if (textObj.has("key") && textObj.get("key").isJsonPrimitive()) {
                return textObj.get("key").getAsString();
            }
        }
        return fallback;
    }

    public static void writeStoryText(JsonObject obj, String field, String value) {
        JsonObject literal = new JsonObject();
        literal.addProperty("literal", value);
        obj.add(field, literal);
    }

    public static List<Edge> collectEdges() {
        Set<Edge> edges = new LinkedHashSet<>();
        byType(TYPE_NPC).forEach((id, raw) -> {
            NodeKey from = new NodeKey(TYPE_NPC, id);
            JsonObject obj = parseObject(raw);
            if (obj.has("dialogues") && obj.get("dialogues").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("dialogues")) {
                    if (el.isJsonObject() && el.getAsJsonObject().has("dialogue")) {
                        parseId(el.getAsJsonObject().get("dialogue").getAsString())
                                .ifPresent(target -> edges.add(new Edge(from, new NodeKey(TYPE_DIALOGUE, target), "talk")));
                    }
                }
            }
        });
        byType(TYPE_DIALOGUE).forEach((id, raw) -> {
            NodeKey from = new NodeKey(TYPE_DIALOGUE, id);
            JsonObject obj = parseObject(raw);
            collectLogicEdges(from, obj, edges);
        });
        byType(TYPE_DOCUMENT).forEach((id, raw) -> {
            NodeKey from = new NodeKey(TYPE_DOCUMENT, id);
            JsonObject obj = parseObject(raw);
            if (obj.has("contract") && obj.get("contract").isJsonObject()) {
                JsonObject contract = obj.getAsJsonObject("contract");
                if (contract.has("quest") && contract.get("quest").isJsonPrimitive()) {
                    parseId(contract.get("quest").getAsString())
                            .ifPresent(quest -> edges.add(new Edge(from, new NodeKey(TYPE_QUEST, quest), "contract")));
                }
            }
            collectLogicEdges(from, obj, edges);
        });
        byType(TYPE_QUEST).forEach((id, raw) -> {
            NodeKey from = new NodeKey(TYPE_QUEST, id);
            JsonObject obj = parseObject(raw);
            if (obj.has("stages") && obj.get("stages").isJsonArray()) {
                for (JsonElement stageEl : obj.getAsJsonArray("stages")) {
                    if (!stageEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject stage = stageEl.getAsJsonObject();
                    if (stage.has("waypoints") && stage.get("waypoints").isJsonArray()) {
                        for (JsonElement wpEl : stage.getAsJsonArray("waypoints")) {
                            if (wpEl.isJsonPrimitive()) {
                                parseId(wpEl.getAsString())
                                        .ifPresent(wp -> edges.add(new Edge(from, new NodeKey(TYPE_WAYPOINT, wp), "stage")));
                            }
                        }
                    }
                }
            }
            collectLogicEdges(from, obj, edges);
        });
        byType(TYPE_WAYPOINT).forEach((id, raw) -> {
            NodeKey wpKey = new NodeKey(TYPE_WAYPOINT, id);
            JsonObject obj = parseObject(raw);
            if (obj.has("quest") && obj.get("quest").isJsonPrimitive()) {
                parseId(obj.get("quest").getAsString())
                        .ifPresent(quest -> edges.add(new Edge(new NodeKey(TYPE_QUEST, quest), wpKey, "quest")));
            }
        });
        byType(TYPE_EVENT).forEach((id, raw) -> {
            NodeKey from = new NodeKey(TYPE_EVENT, id);
            JsonObject obj = parseObject(raw);
            collectLogicEdges(from, obj, edges);
        });
        return new ArrayList<>(edges);
    }

    private static void collectLogicEdges(NodeKey owner, JsonElement element, Set<Edge> edges) {
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            for (JsonElement child : arr) {
                collectLogicEdges(owner, child, edges);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("type") && obj.get("type").isJsonPrimitive()) {
            String type = parseId(obj.get("type").getAsString()).map(ResourceLocation::getPath).orElse("");
            switch (type) {
                case "set_quest_state", "set_quest_stage", "track_quest" -> {
                    if (obj.has("quest") && obj.get("quest").isJsonPrimitive()) {
                        parseId(obj.get("quest").getAsString())
                                .ifPresent(q -> edges.add(new Edge(owner, new NodeKey(TYPE_QUEST, q), type)));
                    }
                }
                case "start_dialogue" -> {
                    if (obj.has("dialogue") && obj.get("dialogue").isJsonPrimitive()) {
                        parseId(obj.get("dialogue").getAsString())
                                .ifPresent(d -> edges.add(new Edge(owner, new NodeKey(TYPE_DIALOGUE, d), "start")));
                    }
                }
                case "give_document" -> {
                    if (obj.has("document") && obj.get("document").isJsonPrimitive()) {
                        parseId(obj.get("document").getAsString())
                                .ifPresent(d -> edges.add(new Edge(owner, new NodeKey(TYPE_DOCUMENT, d), "give")));
                    }
                }
                case "document_read" -> {
                    if (obj.has("document") && obj.get("document").isJsonPrimitive()) {
                        parseId(obj.get("document").getAsString())
                                .ifPresent(d -> edges.add(new Edge(new NodeKey(TYPE_DOCUMENT, d), owner, "read")));
                    }
                }
                case "dialogue_ended" -> {
                    if (obj.has("dialogue") && obj.get("dialogue").isJsonPrimitive()) {
                        parseId(obj.get("dialogue").getAsString())
                                .ifPresent(d -> edges.add(new Edge(new NodeKey(TYPE_DIALOGUE, d), owner, "ended")));
                    }
                }
                case "boss_killed", "unlock_boss_section" -> {
                    if (obj.has("boss") && obj.get("boss").isJsonPrimitive()) {
                        parseId(obj.get("boss").getAsString())
                                .ifPresent(b -> edges.add(new Edge(owner, new NodeKey(TYPE_BOSS, b), type)));
                    }
                }
                case "unlock_codex", "codex_unlocked" -> {
                    if (obj.has("entry") && obj.get("entry").isJsonPrimitive()) {
                        parseId(obj.get("entry").getAsString())
                                .ifPresent(c -> edges.add(new Edge(owner, new NodeKey(TYPE_CODEX, c), type)));
                    }
                }
                default -> {}
            }
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            collectLogicEdges(owner, entry.getValue(), edges);
        }
    }

    private static java.util.Optional<ResourceLocation> parseId(String raw) {
        return java.util.Optional.ofNullable(ResourceLocation.tryParse(raw));
    }

    public record NodeKey(ResourceLocation type, ResourceLocation id) {}

    public record Edge(NodeKey from, NodeKey to, String label) {}
}

