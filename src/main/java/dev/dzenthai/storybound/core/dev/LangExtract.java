package dev.dzenthai.storybound.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code /storybound lang extract}: texts written straight into the story folder's JSON ({@code {"literal": "..."}})
 * move to {@code lang/<language>.json} under the dev mode's canonical keys ({@code <ns>.<type>.<path>.<field>}, list
 * elements by their id; dialogue nodes as {@code node.<node>}), and the JSON keeps only the key. A key whose
 * {@code fallback} the lang file lacks gets the fallback as its text (the fallback stays). So a story can be written in
 * one language first and made translatable afterwards. Files keep their indentation and line endings; with
 * {@code write == false} nothing is written (a preview).
 */
public final class LangExtract {
    /**
     * @param moved     literal texts moved to the lang file
     * @param fallbacks fallbacks copied to the lang file
     * @param files     the definition files that change, relative to the story folder
     */
    public record Result(String language, int moved, int fallbacks, List<String> files) {}

    private LangExtract() {}

    public static Result run(String language, boolean write) throws IOException {
        if (!ConfigStorySource.isLanguageCode(language)) {
            throw new IOException("Not a language code: " + language);
        }
        Path root = ConfigStorySource.root();
        Path langFile = DevModeServer.langFile(language);
        Map<String, String> lang = DevModeServer.readLang(langFile);
        Map<String, String> added = new TreeMap<>();
        List<String> files = new ArrayList<>();
        int moved = 0;
        int fallbacks = 0;
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            Map<ResourceLocation, JsonElement> found = new LinkedHashMap<>();
            ConfigStorySource.scan(root, type, found, new ArrayList<ValidationIssue>());
            for (Map.Entry<ResourceLocation, JsonElement> entry : found.entrySet()) {
                JsonElement json = entry.getValue().deepCopy();
                Counts counts = extract(type.id(), entry.getKey(), json, lang, added);
                fallbacks += counts.fallbacks();
                if (counts.moved() == 0) {
                    continue;
                }
                moved += counts.moved();
                Path file = ConfigStorySource.fileFor(root, type, entry.getKey());
                files.add(root.relativize(file).toString().replace('\\', '/'));
                if (write) {
                    DevModeServer.writeJson(file, json);
                }
            }
        }
        if (write && !added.isEmpty()) {
            Map<String, String> next = new TreeMap<>(lang);
            next.putAll(added);
            DevModeServer.writeLang(langFile, next);
        }
        return new Result(language, moved, fallbacks, List.copyOf(files));
    }

    public record Counts(int moved, int fallbacks) {}

    /**
     * Extracts one definition's texts: changes {@code json} in place and puts new texts in {@code added}, checking
     * keys against {@code lang} and {@code added}. Public for tests.
     */
    public static Counts extract(ResourceLocation type, ResourceLocation id, JsonElement json, Map<String, String> lang, Map<String, String> added) {
        Walk walk = new Walk(type, id, lang, added);
        walk.visit(json, new ArrayList<>());
        return new Counts(walk.moved, walk.fallbacks);
    }

    /** One definition's JSON, changed in place. */
    private static final class Walk {
        private final ResourceLocation type;
        private final ResourceLocation id;
        private final Map<String, String> lang;
        private final Map<String, String> added;
        private int moved;
        private int fallbacks;

        Walk(ResourceLocation type, ResourceLocation id, Map<String, String> lang, Map<String, String> added) {
            this.type = type;
            this.id = id;
            this.lang = lang;
            this.added = added;
        }

        void visit(JsonElement element, List<String> path) {
            if (element instanceof JsonObject object) {
                for (String name : List.copyOf(object.keySet())) {
                    JsonElement child = object.get(name);
                    List<String> at = with(path, name);
                    String literal = literal(child);
                    if (literal != null) {
                        object.add(name, new JsonPrimitive(place(at, literal)));
                    } else {
                        fallback(child);
                        visit(child, at);
                    }
                }
            } else if (element instanceof JsonArray array) {
                for (int i = 0; i < array.size(); i++) {
                    JsonElement child = array.get(i);
                    List<String> at = with(path, child instanceof JsonObject object && object.get("id") instanceof JsonPrimitive own
                            ? own.getAsString() : String.valueOf(i));
                    String literal = literal(child);
                    if (literal != null) {
                        array.set(i, new JsonPrimitive(place(at, literal)));
                    } else {
                        fallback(child);
                        visit(child, at);
                    }
                }
            }
        }

        /** The key for the text at {@code path}: the canonical one, or with {@code _2}... when it holds another text. */
        private String place(List<String> path, String text) {
            List<String> segments = new ArrayList<>(path);
            if (type.getPath().equals("dialogue") && !segments.isEmpty() && segments.getFirst().equals("nodes")) {
                segments.set(0, "node");
            }
            String base = id.getNamespace() + "." + type.getPath() + "." + id.getPath().replace('/', '.') + "." + String.join(".", segments);
            String key = base;
            for (int n = 2; known(key) != null && !known(key).equals(text); n++) {
                key = base + "_" + n;
            }
            added.put(key, text);
            moved++;
            return key;
        }

        /** {@code {"key": ..., "fallback": ...}} with the key missing from the lang file: the fallback becomes its text. */
        private void fallback(JsonElement element) {
            if (element instanceof JsonObject object && !object.has("type") && object.get("key") instanceof JsonPrimitive key
                    && object.get("fallback") instanceof JsonPrimitive text && known(key.getAsString()) == null) {
                added.put(key.getAsString(), text.getAsString());
                fallbacks++;
            }
        }

        private String known(String key) {
            String text = added.get(key);
            return text != null ? text : lang.get(key);
        }

        private static String literal(JsonElement element) {
            return element instanceof JsonObject object && object.size() == 1 && object.get("literal") instanceof JsonPrimitive text && text.isString()
                    ? text.getAsString() : null;
        }

        private static List<String> with(List<String> path, String step) {
            List<String> longer = new ArrayList<>(path);
            longer.add(step);
            return longer;
        }
    }
}
