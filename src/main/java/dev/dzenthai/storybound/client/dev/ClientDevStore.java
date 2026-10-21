package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.text.ClientConfigTexts;
import dev.dzenthai.storybound.core.data.StoryIds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The dev mode's copy of the story: every definition as JSON from the server's last snapshot (including server-only
 * types), the validator's issues, the story's languages, and texts typed in the editor but not yet sent.
 */
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
    public static final ResourceLocation TYPE_DIALOGUE_LAYOUT = Storybound.id("dialogue_layout");
    public static final ResourceLocation TYPE_SCRIPT = Storybound.id("script");

    private static volatile Map<ResourceLocation, Map<ResourceLocation, String>> definitions = Map.of();
    private static volatile List<String> issues = List.of();
    private static volatile String sourceLanguage = "en_us";
    private static volatile List<String> languages = List.of("en_us", "ru_ru");
    private static String activeLanguage = "en_us";
    private static final Map<String, Map<String, String>> pendingTranslations = new LinkedHashMap<>();
    private static @Nullable Runnable listener;

    private ClientDevStore() {}

    public static void setSnapshot(Map<ResourceLocation, Map<ResourceLocation, String>> newDefinitions, List<String> newIssues, String srcLang, List<String> langs) {
        Map<ResourceLocation, Map<ResourceLocation, String>> copy = new LinkedHashMap<>();
        newDefinitions.forEach((type, map) -> copy.put(type, Collections.unmodifiableMap(new LinkedHashMap<>(map))));
        definitions = Collections.unmodifiableMap(copy);
        issues = List.copyOf(newIssues);
        sourceLanguage = srcLang;
        languages = List.copyOf(langs);
        if (!languages.contains(activeLanguage) && !languages.isEmpty()) {
            activeLanguage = languages.contains(srcLang) ? srcLang : languages.get(0);
        }
        Runnable callback = listener;
        if (callback != null) {
            callback.run();
        }
    }

    public static void setListener(@Nullable Runnable callback) {
        listener = callback;
    }

    public static String sourceLanguage() {
        return sourceLanguage;
    }

    public static List<String> languages() {
        return languages;
    }

    public static String activeLanguage() {
        return activeLanguage;
    }

    public static void setActiveLanguage(String lang) {
        activeLanguage = lang;
    }

    public static Map<String, String> pendingTranslationsFor(String language) {
        return pendingTranslations.computeIfAbsent(language, k -> new LinkedHashMap<>());
    }

    public static void clearPendingTranslations(String language) {
        pendingTranslations.remove(language);
    }

    public static String canonicalKey(ResourceLocation type, ResourceLocation id, String fieldOrPath) {
        String cleanType = type.getPath();
        return id.getNamespace() + "." + cleanType + "." + id.getPath().replace('/', '.') + "." + fieldOrPath;
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

    public static @Nullable String getKey(JsonObject obj, String field) {
        if (!obj.has(field)) {
            return null;
        }
        JsonElement el = obj.get(field);
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        if (el.isJsonObject()) {
            JsonObject textObj = el.getAsJsonObject();
            if (textObj.has("key") && textObj.get("key").isJsonPrimitive()) {
                return textObj.get("key").getAsString();
            }
        }
        return null;
    }

    public static String readStoryText(JsonObject obj, String field, String fallback) {
        return readStoryText(obj, field, fallback, activeLanguage);
    }

    public static String readStoryText(JsonObject obj, String field, String fallback, String language) {
        if (!obj.has(field)) {
            return fallback;
        }
        JsonElement el = obj.get(field);
        String raw = null;
        if (el.isJsonPrimitive()) {
            raw = el.getAsString();
        } else if (el.isJsonObject()) {
            JsonObject textObj = el.getAsJsonObject();
            if (textObj.has("literal") && textObj.get("literal").isJsonPrimitive()) {
                return textObj.get("literal").getAsString();
            }
            if (textObj.has("key") && textObj.get("key").isJsonPrimitive()) {
                raw = textObj.get("key").getAsString();
            }
        }
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Map<String, String> pending = pendingTranslations.get(language);
        if (pending != null && pending.containsKey(raw)) {
            return pending.get(raw);
        }
        String translated = ClientConfigTexts.translation(raw, language);
        if (translated != null) {
            return translated;
        }
        if (Language.getInstance().has(raw)) {
            return Language.getInstance().getOrDefault(raw);
        }
        return raw;
    }

    public static boolean isBuggySharedKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (key.startsWith("storybound.custom.")) {
            String[] parts = key.split("\\.");
            if (parts.length == 4) {
                try {
                    int hash = Integer.parseInt(parts[3]);
                    if (hash == Math.abs(parts[2].hashCode())) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    public static String generateUniqueKey(String field) {
        return "storybound.custom." + field + "." + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static void writeStoryText(JsonObject obj, String field, String value) {
        String existingKey = getKey(obj, field);
        if (existingKey == null || existingKey.isBlank() || isBuggySharedKey(existingKey)) {
            existingKey = generateUniqueKey(field);
            obj.addProperty(field, existingKey);
        }
        writeStoryText(obj, field, value, existingKey);
    }

    public static void writeStoryText(JsonObject obj, String field, String value, String key) {
        String useKey = getKey(obj, field);
        if (useKey == null || useKey.isBlank() || isBuggySharedKey(useKey) || (key != null && !key.isBlank() && !key.equals(useKey) && !isBuggySharedKey(key) && useKey.startsWith("storybound.custom."))) {
            useKey = (key != null && !key.isBlank() && !isBuggySharedKey(key)) ? key : generateUniqueKey(field);
            obj.addProperty(field, useKey);
        }
        pendingTranslationsFor(activeLanguage).put(useKey, value);
    }

    public static boolean hasTranslation(String key, String language) {
        Map<String, String> pending = pendingTranslations.get(language);
        if (pending != null && pending.containsKey(key)) {
            return !pending.get(key).isBlank();
        }
        String raw = ClientConfigTexts.rawTranslation(language, key);
        return raw != null && !raw.isBlank();
    }

    public static int missingTranslationCount(String language) {
        if (language.equalsIgnoreCase(sourceLanguage)) {
            return 0;
        }
        Map<String, String> srcMap = ClientConfigTexts.texts().translations().get(sourceLanguage);
        if (srcMap == null || srcMap.isEmpty()) {
            return 0;
        }
        int missing = 0;
        for (String key : srcMap.keySet()) {
            if (!hasTranslation(key, language)) {
                missing++;
            }
        }
        return missing;
    }

    public static boolean isNodeFullyTranslated(NodeKey key, JsonObject obj, String language) {
        if (language.equalsIgnoreCase(sourceLanguage)) {
            return true;
        }
        if (obj.has("title")) {
            String k = getKey(obj, "title");
            if (k != null && !hasTranslation(k, language)) {
                return false;
            }
        }
        if (obj.has("name")) {
            String k = getKey(obj, "name");
            if (k != null && !hasTranslation(k, language)) {
                return false;
            }
        }
        if (obj.has("description")) {
            String k = getKey(obj, "description");
            if (k != null && !hasTranslation(k, language)) {
                return false;
            }
        }
        if (obj.has("lore")) {
            String k = getKey(obj, "lore");
            if (k != null && !hasTranslation(k, language)) {
                return false;
            }
        }
        if (key.type().equals(TYPE_DOCUMENT)) {
            if (ClientConfigTexts.document(key.id(), language).isEmpty()) {
                return false;
            }
        }
        if (key.type().equals(TYPE_QUEST)) {
            if (obj.has("stages") && obj.get("stages").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("stages")) {
                    if (el.isJsonObject()) {
                        JsonObject st = el.getAsJsonObject();
                        if (st.has("log")) {
                            String k = getKey(st, "log");
                            if (k != null && !hasTranslation(k, language)) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        if (key.type().equals(TYPE_DIALOGUE)) {
            if (obj.has("nodes") && obj.get("nodes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("nodes").entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        JsonObject dNode = entry.getValue().getAsJsonObject();
                        if (dNode.has("text")) {
                            String k = getKey(dNode, "text");
                            if (k != null && !hasTranslation(k, language)) {
                                return false;
                            }
                        }
                        if (dNode.has("options") && dNode.get("options").isJsonArray()) {
                            for (JsonElement rEl : dNode.getAsJsonArray("options")) {
                                if (rEl.isJsonObject()) {
                                    JsonObject resp = rEl.getAsJsonObject();
                                    if (resp.has("text")) {
                                        String k = getKey(resp, "text");
                                        if (k != null && !hasTranslation(k, language)) {
                                            return false;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    // --- KubeJS scripts on the server

    /** A script file as the server read it: the handlers it registers, the story events it listens to, the ids it mentions. */
    public record ScriptFile(String path, List<String> actions, List<String> conditions, List<ScriptListener> listeners, List<String> ids) {}

    public record ScriptListener(String event, String id) {}

    /**
     * The server's scripts, from the last answer to a request.
     *
     * @param known whether the server answered yet
     */
    public record Scripts(boolean known, boolean kubejs, String folder, Set<String> actions, Set<String> conditions, Map<String, ScriptFile> files) {
        static final Scripts UNKNOWN = new Scripts(false, false, "", Set.of(), Set.of(), Map.of());
    }

    /** Where a script block stands, for its card and its inspector. */
    public enum ScriptState { UNKNOWN, NO_KUBEJS, NO_FILE, FILE_MISSING, NOT_REGISTERED, READY }

    private static volatile Scripts scripts = Scripts.UNKNOWN;

    public static Scripts scripts() {
        return scripts;
    }

    public static void setScripts(String json) {
        JsonObject root = parseObject(json);
        Map<String, ScriptFile> files = new LinkedHashMap<>();
        if (root.has("files") && root.get("files").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                List<ScriptListener> listeners = new ArrayList<>();
                if (file.has("listeners")) {
                    file.getAsJsonArray("listeners").forEach(listener -> listeners.add(new ScriptListener(
                            listener.getAsJsonObject().get("event").getAsString(), listener.getAsJsonObject().get("id").getAsString())));
                }
                String path = file.get("path").getAsString();
                files.put(path, new ScriptFile(path, strings(file, "actions"), strings(file, "conditions"), listeners, strings(file, "ids")));
            }
        }
        scripts = new Scripts(true, root.has("kubejs") && root.get("kubejs").getAsBoolean(), root.has("folder") ? root.get("folder").getAsString() : "",
                Set.copyOf(strings(root, "actions")), Set.copyOf(strings(root, "conditions")), files);
        Runnable callback = listener;
        if (callback != null) {
            callback.run();
        }
    }

    private static List<String> strings(JsonObject object, String field) {
        List<String> values = new ArrayList<>();
        if (object.has(field) && object.get(field).isJsonArray()) {
            object.getAsJsonArray(field).forEach(value -> values.add(value.getAsString()));
        }
        return values;
    }

    /** The handler a script block stands for: its {@code hook}, or the block's own id. */
    public static ResourceLocation scriptHook(ResourceLocation script) {
        JsonObject json = parseObject(getJson(TYPE_SCRIPT, script));
        ResourceLocation hook = json.has("hook") && json.get("hook").isJsonPrimitive() ? ResourceLocation.tryParse(json.get("hook").getAsString()) : null;
        return hook != null ? hook : script;
    }

    public static @Nullable String scriptFile(ResourceLocation script) {
        JsonObject json = parseObject(getJson(TYPE_SCRIPT, script));
        return json.has("file") && json.get("file").isJsonPrimitive() && !json.get("file").getAsString().isBlank() ? json.get("file").getAsString() : null;
    }

    public static ScriptState scriptState(ResourceLocation script) {
        Scripts known = scripts;
        String file = scriptFile(script);
        if (!known.known()) {
            return ScriptState.UNKNOWN;
        }
        if (!known.kubejs()) {
            return ScriptState.NO_KUBEJS;
        }
        if (file == null) {
            return ScriptState.NO_FILE;
        }
        if (!known.files().containsKey(file)) {
            return ScriptState.FILE_MISSING;
        }
        String hook = scriptHook(script).toString();
        return known.actions().contains(hook) || known.conditions().contains(hook) ? ScriptState.READY : ScriptState.NOT_REGISTERED;
    }

    /** What reading a script shows: the events it listens to (arrows into it) and the story ids it mentions (arrows out). */
    private static void scriptEdges(NodeKey script, JsonObject json, Set<Edge> edges) {
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
        String path = scriptFile(script.id());
        ScriptFile file = path != null ? scripts.files().get(path) : null;
        if (file == null) {
            return;
        }
        Set<String> skip = new LinkedHashSet<>(file.actions());
        skip.addAll(file.conditions());
        for (ScriptListener listener : file.listeners()) {
            ResourceLocation type = switch (listener.event()) {
                case "questStateChanged", "questStageChanged" -> TYPE_QUEST;
                case "dialogueStarted", "dialogueEnded" -> TYPE_DIALOGUE;
                case "codexUnlocked" -> TYPE_CODEX;
                case "bossDefeated" -> TYPE_BOSS;
                case "npcInteract" -> TYPE_NPC;
                default -> null;
            };
            Optional<ResourceLocation> id = parseId(listener.id());
            if (type != null && id.isPresent() && getJson(type, id.get()) != null) {
                edges.add(new Edge(new NodeKey(type, id.get()), script, "event"));
                skip.add(listener.id());
            }
        }
        for (String mentioned : file.ids()) {
            Optional<ResourceLocation> id = parseId(mentioned);
            if (skip.contains(mentioned) || id.isEmpty()) {
                continue;
            }
            for (ResourceLocation type : List.of(TYPE_QUEST, TYPE_DIALOGUE, TYPE_DOCUMENT, TYPE_WAYPOINT, TYPE_BOSS, TYPE_CODEX)) {
                if (getJson(type, id.get()) != null) {
                    edges.add(new Edge(script, new NodeKey(type, id.get()), "uses"));
                }
            }
        }
    }

    public static List<Edge> collectEdges() {
        Set<Edge> edges = new LinkedHashSet<>();
        Map<String, NodeKey> hooks = new LinkedHashMap<>();
        byType(TYPE_SCRIPT).keySet().forEach(id -> hooks.putIfAbsent(scriptHook(id).toString(), new NodeKey(TYPE_SCRIPT, id)));
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
            collectLogicEdges(from, obj, edges, hooks);
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
            collectLogicEdges(from, obj, edges, hooks);
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
            collectLogicEdges(from, obj, edges, hooks);
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
            collectLogicEdges(from, obj, edges, hooks);
        });
        for (ResourceLocation type : List.of(TYPE_NPC, TYPE_BOSS, TYPE_CODEX)) {
            byType(type).forEach((id, raw) -> collectLogicEdges(new NodeKey(type, id), parseObject(raw), edges, hooks));
        }
        byType(TYPE_SCRIPT).forEach((id, raw) -> scriptEdges(new NodeKey(TYPE_SCRIPT, id), parseObject(raw), edges));
        return new ArrayList<>(edges);
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
                                .ifPresent(q -> edges.add(new Edge(owner, new NodeKey(TYPE_QUEST, q), type)));
                    }
                }
                case "quest", "quest_state_changed", "quest_stage_changed" -> {
                    // A condition or trigger waiting for another quest: that quest comes first.
                    if (obj.has("quest") && obj.get("quest").isJsonPrimitive()) {
                        parseId(obj.get("quest").getAsString()).map(q -> new NodeKey(TYPE_QUEST, q)).filter(q -> !q.equals(owner))
                                .ifPresent(q -> edges.add(new Edge(q, owner, "after")));
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

    public record NodeKey(ResourceLocation type, ResourceLocation id) {}

    public record Edge(NodeKey from, NodeKey to, String label) {}
}

