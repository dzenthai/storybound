package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.text.ClientConfigTexts;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryLinks.Edge;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import dev.dzenthai.storybound.core.story.StoryLinks.ScriptFile;
import dev.dzenthai.storybound.core.story.StoryLinks.ScriptListener;
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
    public static final ResourceLocation TYPE_QUEST = StoryLinks.QUEST;
    public static final ResourceLocation TYPE_DIALOGUE = StoryLinks.DIALOGUE;
    public static final ResourceLocation TYPE_NPC = StoryLinks.NPC;
    public static final ResourceLocation TYPE_DOCUMENT = StoryLinks.DOCUMENT;
    public static final ResourceLocation TYPE_WAYPOINT = StoryLinks.WAYPOINT;
    public static final ResourceLocation TYPE_BOSS = StoryLinks.BOSS;
    public static final ResourceLocation TYPE_CODEX = StoryLinks.CODEX;
    public static final ResourceLocation TYPE_EVENT = StoryLinks.EVENT;
    public static final ResourceLocation TYPE_FLAG = Storybound.id("flag");
    public static final ResourceLocation TYPE_CHAPTER = Storybound.id("chapter");
    public static final ResourceLocation TYPE_DIALOGUE_LAYOUT = Storybound.id("dialogue_layout");
    public static final ResourceLocation TYPE_SCRIPT = StoryLinks.SCRIPT;

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
        refreshDerivedScripts();
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

    /** Script blocks include those derived from the server's scripts (see {@link #isDerivedScript}). */
    public static Map<ResourceLocation, String> byType(ResourceLocation type) {
        return type.equals(TYPE_SCRIPT) ? scriptBlocks : definitions.getOrDefault(type, Map.of());
    }

    public static @Nullable String getJson(ResourceLocation type, ResourceLocation id) {
        return byType(type).get(id);
    }

    /** Forgets a definition here at once (its deletion is on the way to the server). */
    public static void removeLocal(ResourceLocation type, ResourceLocation id) {
        Map<ResourceLocation, Map<ResourceLocation, String>> immutable = new LinkedHashMap<>();
        definitions.forEach((t, map) -> {
            Map<ResourceLocation, String> copy = new LinkedHashMap<>(map);
            if (t.equals(type)) {
                copy.remove(id);
            }
            immutable.put(t, Collections.unmodifiableMap(copy));
        });
        definitions = Collections.unmodifiableMap(immutable);
        refreshDerivedScripts();
    }

    public static void putLocal(ResourceLocation type, ResourceLocation id, String json) {
        Map<ResourceLocation, Map<ResourceLocation, String>> copy = new LinkedHashMap<>();
        definitions.forEach((t, map) -> copy.put(t, new LinkedHashMap<>(map)));
        copy.computeIfAbsent(type, k -> new LinkedHashMap<>()).put(id, json);
        Map<ResourceLocation, Map<ResourceLocation, String>> immutable = new LinkedHashMap<>();
        copy.forEach((t, map) -> immutable.put(t, Collections.unmodifiableMap(map)));
        definitions = Collections.unmodifiableMap(immutable);
        refreshDerivedScripts();
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

    // --- a player's progress, for the map's progress layer

    private static volatile String progressPlayer = "";
    private static volatile Map<ResourceLocation, QuestProgress> progress = Map.of();

    public static void setProgress(String player, Map<ResourceLocation, QuestProgress> quests) {
        progressPlayer = player;
        progress = Map.copyOf(quests);
    }

    /** Whether the server answered for that player yet. */
    static boolean hasProgress(String player) {
        return player.equals(progressPlayer);
    }

    /** The player's progress in the quest; null when they do not know it. */
    static @Nullable QuestProgress progress(String player, ResourceLocation quest) {
        return player.equals(progressPlayer) ? progress.get(quest) : null;
    }

    // --- KubeJS scripts on the server

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
                // @storybound leads_to comments, as (type, id) pairs.
                List<ScriptListener> leadsTo = new ArrayList<>();
                if (file.has("leads_to")) {
                    file.getAsJsonArray("leads_to").forEach(lead -> leadsTo.add(new ScriptListener(
                            lead.getAsJsonObject().get("type").getAsString(), lead.getAsJsonObject().get("id").getAsString())));
                }
                String path = file.get("path").getAsString();
                files.put(path, new ScriptFile(path, strings(file, "actions"), strings(file, "conditions"), listeners, strings(file, "ids"), leadsTo));
            }
        }
        scripts = new Scripts(true, root.has("kubejs") && root.get("kubejs").getAsBoolean(), root.has("folder") ? root.get("folder").getAsString() : "",
                Set.copyOf(strings(root, "actions")), Set.copyOf(strings(root, "conditions")), files);
        refreshDerivedScripts();
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

    /** Script blocks: those with a file and those derived from the scripts. */
    private static volatile Map<ResourceLocation, String> scriptBlocks = Map.of();
    private static volatile Set<ResourceLocation> derivedScripts = Set.of();

    /**
     * A block without a file of its own, standing for a handler the server's scripts register that no block stands
     * for, so every handler shows on the canvas without writing a block. Saving it (a title, {@code leads_to}) writes
     * its file; deleting it means deleting the handler from the script.
     */
    public static boolean isDerivedScript(ResourceLocation id) {
        return derivedScripts.contains(id);
    }

    private static void refreshDerivedScripts() {
        Map<ResourceLocation, String> blocks = definitions.getOrDefault(TYPE_SCRIPT, Map.of());
        Set<String> covered = new LinkedHashSet<>();
        Map<ResourceLocation, String> merged = new LinkedHashMap<>();
        blocks.forEach((id, raw) -> {
            JsonObject json = parseObject(raw);
            ResourceLocation hook = json.has("hook") && json.get("hook").isJsonPrimitive() ? ResourceLocation.tryParse(json.get("hook").getAsString()) : null;
            // A hidden block still covers its handler, so no block is derived for it either.
            covered.add((hook != null ? hook : id).toString());
            boolean hidden = json.has("hidden") && json.get("hidden").isJsonPrimitive() && json.get("hidden").getAsBoolean();
            if (!hidden) {
                merged.put(id, raw);
            }
        });
        Set<ResourceLocation> derived = new LinkedHashSet<>();
        for (ScriptFile file : scripts.files().values()) {
            List<String> handlers = new ArrayList<>(file.actions());
            handlers.addAll(file.conditions());
            for (String handler : handlers) {
                ResourceLocation id = ResourceLocation.tryParse(handler);
                if (id != null && covered.add(handler) && !merged.containsKey(id)) {
                    JsonObject json = new JsonObject();
                    json.addProperty("file", file.path());
                    merged.put(id, json.toString());
                    derived.add(id);
                }
            }
        }
        scriptBlocks = Collections.unmodifiableMap(merged);
        derivedScripts = Set.copyOf(derived);
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

    /** Whether the server's scripts register the block's handler now: deleting such a block only hides it. */
    public static boolean scriptRegistered(ResourceLocation script) {
        String hook = scriptHook(script).toString();
        return scripts.actions().contains(hook) || scripts.conditions().contains(hook);
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

    /** Every link between the definitions, the server's scripts included (see {@link StoryLinks}). */
    public static List<Edge> collectEdges() {
        return StoryLinks.collect(new StoryLinks.Source() {
            @Override
            public Map<ResourceLocation, String> byType(ResourceLocation type) {
                return ClientDevStore.byType(type);
            }

            @Override
            public @Nullable ScriptFile scriptFile(String path) {
                return scripts.files().get(path);
            }
        });
    }
}

