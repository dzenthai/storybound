package dev.dzenthai.storybound.core.story;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.StoryIds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The links between a story's definitions, read from their JSON: an NPC talks with a dialogue, a dialogue starts a
 * quest, a quest waits for another, a script block leads somewhere. The dev mode's canvas draws them; {@link StoryFlow}
 * folds them into "which quest follows which" for the story map and the players' chapter map. Needs no game: only the
 * definitions as JSON (and, for script blocks, what reading their files showed).
 */
public final class StoryLinks {
    public static final ResourceLocation QUEST = Storybound.id("quest");
    public static final ResourceLocation DIALOGUE = Storybound.id("dialogue");
    public static final ResourceLocation NPC = Storybound.id("npc_binding");
    public static final ResourceLocation DOCUMENT = Storybound.id("document");
    public static final ResourceLocation WAYPOINT = Storybound.id("waypoint");
    public static final ResourceLocation BOSS = Storybound.id("boss");
    public static final ResourceLocation CODEX = Storybound.id("codex");
    public static final ResourceLocation EVENT = Storybound.id("event");
    public static final ResourceLocation SCRIPT = Storybound.id("script");

    /** A definition: its type and id. */
    public record NodeKey(ResourceLocation type, ResourceLocation id) {}

    public record Edge(NodeKey from, NodeKey to, String label) {}

    /** A script file as the server read it: the handlers it registers, the story events it listens to, the ids it mentions. */
    public record ScriptFile(String path, List<String> actions, List<String> conditions, List<ScriptListener> listeners, List<String> ids,
                             List<ScriptListener> leadsTo) {}

    /** A story event a script listens to with its id; for {@code leadsTo}, the type and the id. */
    public record ScriptListener(String event, String id) {}

    /** Where the links are read from. */
    public interface Source {
        /** A type's definitions, raw JSON by id. */
        Map<ResourceLocation, String> byType(ResourceLocation type);

        /** A script file under {@code kubejs/server_scripts/} as read; {@code null} when unknown. */
        @Nullable ScriptFile scriptFile(String path);
    }

    private StoryLinks() {}

    public static List<Edge> collect(Source source) {
        Set<Edge> edges = new LinkedHashSet<>();
        Map<String, NodeKey> hooks = new LinkedHashMap<>();
        source.byType(SCRIPT).forEach((id, raw) -> hooks.putIfAbsent(scriptHook(id, parseObject(raw)).toString(), new NodeKey(SCRIPT, id)));
        source.byType(NPC).forEach((id, raw) -> {
            NodeKey from = new NodeKey(NPC, id);
            JsonObject obj = parseObject(raw);
            if (obj.has("dialogues") && obj.get("dialogues").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("dialogues")) {
                    if (el.isJsonObject() && el.getAsJsonObject().has("dialogue")) {
                        parseId(el.getAsJsonObject().get("dialogue").getAsString())
                                .ifPresent(target -> edges.add(new Edge(from, new NodeKey(DIALOGUE, target), "talk")));
                    }
                }
            }
        });
        source.byType(DIALOGUE).forEach((id, raw) -> collectLogicEdges(new NodeKey(DIALOGUE, id), parseObject(raw), edges, hooks));
        source.byType(DOCUMENT).forEach((id, raw) -> {
            NodeKey from = new NodeKey(DOCUMENT, id);
            JsonObject obj = parseObject(raw);
            if (obj.has("contract") && obj.get("contract").isJsonObject()) {
                JsonObject contract = obj.getAsJsonObject("contract");
                if (contract.has("quest") && contract.get("quest").isJsonPrimitive()) {
                    parseId(contract.get("quest").getAsString())
                            .ifPresent(quest -> edges.add(new Edge(from, new NodeKey(QUEST, quest), "contract")));
                }
            }
            collectLogicEdges(from, obj, edges, hooks);
        });
        source.byType(QUEST).forEach((id, raw) -> {
            NodeKey from = new NodeKey(QUEST, id);
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
                                        .ifPresent(wp -> edges.add(new Edge(from, new NodeKey(WAYPOINT, wp), "stage")));
                            }
                        }
                    }
                }
            }
            collectLogicEdges(from, obj, edges, hooks);
        });
        source.byType(WAYPOINT).forEach((id, raw) -> {
            NodeKey wpKey = new NodeKey(WAYPOINT, id);
            JsonObject obj = parseObject(raw);
            if (obj.has("quest") && obj.get("quest").isJsonPrimitive()) {
                parseId(obj.get("quest").getAsString())
                        .ifPresent(quest -> edges.add(new Edge(new NodeKey(QUEST, quest), wpKey, "quest")));
            }
        });
        source.byType(EVENT).forEach((id, raw) -> collectLogicEdges(new NodeKey(EVENT, id), parseObject(raw), edges, hooks));
        for (ResourceLocation type : List.of(NPC, BOSS, CODEX)) {
            source.byType(type).forEach((id, raw) -> collectLogicEdges(new NodeKey(type, id), parseObject(raw), edges, hooks));
        }
        source.byType(SCRIPT).forEach((id, raw) -> scriptEdges(source, new NodeKey(SCRIPT, id), parseObject(raw), edges));
        return new ArrayList<>(edges);
    }

    /** The handler a script block stands for: its {@code hook}, or the block's own id. */
    public static ResourceLocation scriptHook(ResourceLocation script, JsonObject json) {
        ResourceLocation hook = json.has("hook") && json.get("hook").isJsonPrimitive() ? ResourceLocation.tryParse(json.get("hook").getAsString()) : null;
        return hook != null ? hook : script;
    }

    /** What reading a script shows: the events it listens to (arrows into it) and the story ids it mentions (arrows out). */
    private static void scriptEdges(Source source, NodeKey script, JsonObject json, Set<Edge> edges) {
        if (json.has("leads_to") && json.get("leads_to").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("leads_to")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("type") && element.getAsJsonObject().has("id")) {
                    ResourceLocation type = StoryIds.parse(element.getAsJsonObject().get("type").getAsString());
                    Optional<ResourceLocation> id = parseId(element.getAsJsonObject().get("id").getAsString());
                    if (type != null && id.isPresent()) {
                        edges.add(new Edge(script, new NodeKey(type, id.get()), "leads"));
                    }
                }
            }
        }
        String path = json.has("file") && json.get("file").isJsonPrimitive() && !json.get("file").getAsString().isBlank() ? json.get("file").getAsString() : null;
        ScriptFile file = path != null ? source.scriptFile(path) : null;
        if (file == null) {
            return;
        }
        for (ScriptListener lead : file.leadsTo()) {
            ResourceLocation type = StoryIds.parse(lead.event());
            Optional<ResourceLocation> id = parseId(lead.id());
            if (type != null && id.isPresent()) {
                edges.add(new Edge(script, new NodeKey(type, id.get()), "leads"));
            }
        }
        Set<String> skip = new LinkedHashSet<>(file.actions());
        skip.addAll(file.conditions());
        for (ScriptListener listener : file.listeners()) {
            ResourceLocation type = switch (listener.event()) {
                case "questStateChanged", "questStageChanged" -> QUEST;
                case "dialogueStarted", "dialogueEnded" -> DIALOGUE;
                case "codexUnlocked" -> CODEX;
                case "bossDefeated" -> BOSS;
                case "npcInteract" -> NPC;
                default -> null;
            };
            Optional<ResourceLocation> id = parseId(listener.id());
            if (type != null && id.isPresent() && source.byType(type).containsKey(id.get())) {
                edges.add(new Edge(new NodeKey(type, id.get()), script, "event"));
                skip.add(listener.id());
            }
        }
        for (String mentioned : file.ids()) {
            Optional<ResourceLocation> id = parseId(mentioned);
            if (skip.contains(mentioned) || id.isEmpty()) {
                continue;
            }
            for (ResourceLocation type : List.of(QUEST, DIALOGUE, DOCUMENT, WAYPOINT, BOSS, CODEX)) {
                if (source.byType(type).containsKey(id.get())) {
                    edges.add(new Edge(script, new NodeKey(type, id.get()), "uses"));
                }
            }
        }
    }

    private static void collectLogicEdges(NodeKey owner, JsonElement element, Set<Edge> edges, Map<String, NodeKey> hooks) {
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            for (JsonElement child : arr) {
                collectLogicEdges(owner, child, edges, hooks);
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
                                .ifPresent(q -> edges.add(new Edge(owner, new NodeKey(QUEST, q), type)));
                    }
                }
                case "quest", "quest_state_changed", "quest_stage_changed" -> {
                    // A condition or trigger waiting for another quest: that quest comes first.
                    if (obj.has("quest") && obj.get("quest").isJsonPrimitive()) {
                        parseId(obj.get("quest").getAsString()).map(q -> new NodeKey(QUEST, q)).filter(q -> !q.equals(owner))
                                .ifPresent(q -> edges.add(new Edge(q, owner, "after")));
                    }
                }
                case "start_dialogue" -> {
                    if (obj.has("dialogue") && obj.get("dialogue").isJsonPrimitive()) {
                        parseId(obj.get("dialogue").getAsString())
                                .ifPresent(d -> edges.add(new Edge(owner, new NodeKey(DIALOGUE, d), "start")));
                    }
                }
                case "give_document" -> {
                    if (obj.has("document") && obj.get("document").isJsonPrimitive()) {
                        parseId(obj.get("document").getAsString())
                                .ifPresent(d -> edges.add(new Edge(owner, new NodeKey(DOCUMENT, d), "give")));
                    }
                }
                case "document_read" -> {
                    if (obj.has("document") && obj.get("document").isJsonPrimitive()) {
                        parseId(obj.get("document").getAsString())
                                .ifPresent(d -> edges.add(new Edge(new NodeKey(DOCUMENT, d), owner, "read")));
                    }
                }
                case "dialogue_ended" -> {
                    if (obj.has("dialogue") && obj.get("dialogue").isJsonPrimitive()) {
                        parseId(obj.get("dialogue").getAsString())
                                .ifPresent(d -> edges.add(new Edge(new NodeKey(DIALOGUE, d), owner, "ended")));
                    }
                }
                case "boss_killed", "unlock_boss_section" -> {
                    if (obj.has("boss") && obj.get("boss").isJsonPrimitive()) {
                        parseId(obj.get("boss").getAsString())
                                .ifPresent(b -> edges.add(new Edge(owner, new NodeKey(BOSS, b), type)));
                    }
                }
                case "unlock_codex", "codex_unlocked" -> {
                    if (obj.has("entry") && obj.get("entry").isJsonPrimitive()) {
                        parseId(obj.get("entry").getAsString())
                                .ifPresent(c -> edges.add(new Edge(owner, new NodeKey(CODEX, c), type)));
                    }
                }
                case "kubejs" -> {
                    // The action or condition "Script": an arrow to the script block of that handler.
                    NodeKey script = obj.has("id") && obj.get("id").isJsonPrimitive() ? hooks.get(obj.get("id").getAsString()) : null;
                    if (script != null && !script.equals(owner)) {
                        edges.add(new Edge(owner, script, "script"));
                    }
                }
                default -> {}
            }
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            collectLogicEdges(owner, entry.getValue(), edges, hooks);
        }
    }

    private static Optional<ResourceLocation> parseId(String raw) {
        return Optional.ofNullable(ResourceLocation.tryParse(raw));
    }

    private static JsonObject parseObject(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }
}
