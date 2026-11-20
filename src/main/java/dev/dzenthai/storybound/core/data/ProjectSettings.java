package dev.dzenthai.storybound.core.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.core.story.StoryRenames;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The story's languages and namespace, in {@code config/storybound/project.json}:
 * {@code { "source_language": "en_us", "languages": ["en_us", "ru_ru"], "namespace": "my_story" }}. The namespace is
 * where the dev mode makes new nodes (their folder in the story folder); {@code storybound} until the author sets one. The source language is the one authors
 * write in; the dev mode edits texts per language from the list. Without the file the languages are those with a
 * {@code lang/<language>.json}, and {@code en_us} is the source when present.
 */
public record ProjectSettings(String sourceLanguage, List<String> languages, String namespace) {
    public static final String DEFAULT_SOURCE = "en_us";
    /** Where the dev mode makes new nodes until the author names the story's own namespace. */
    public static final String DEFAULT_NAMESPACE = "storybound";
    public static final String FILE_NAME = "project.json";

    /**
     * The story's preferences the dev mode's settings window edits (November 16, 2026), beside the languages and the
     * namespace, which the top bar edits: the difficulty scale, tracking a quest when given.
     */
    public static final List<String> OPTIONS = List.of("difficulty", "track_given_quests");

    public static ProjectSettings load(Path root) {
        return load(root, problem -> {});
    }

    /** The file's object, or empty when it is missing or broken (its readers report that themselves). */
    public static JsonObject raw(Path root) {
        Path file = root.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try {
                JsonElement read = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (read.isJsonObject()) {
                    return read.getAsJsonObject();
                }
            } catch (IOException | JsonParseException e) {
                // Empty: each setting takes its default.
            }
        }
        return new JsonObject();
    }

    /** A yes/no preference of the file ({@code track_given_quests}); false when missing or not a boolean. */
    public static boolean flag(Path root, String key) {
        JsonElement value = raw(root).get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    /** The {@link #OPTIONS} the file writes, as written. */
    public static JsonObject options(Path root) {
        JsonObject json = raw(root);
        JsonObject options = new JsonObject();
        OPTIONS.forEach(key -> {
            if (json.has(key)) {
                options.add(key, json.get(key).deepCopy());
            }
        });
        return options;
    }

    /**
     * Writes the {@link #OPTIONS}: those given replace the file's, those left out are taken out of it; the languages,
     * the namespace and anything else the file has stay. A missing file is made with the languages that load now.
     */
    public static void saveOptions(Path root, JsonObject options) throws IOException {
        Path file = root.resolve(FILE_NAME);
        JsonObject json = raw(root);
        if (!json.has("languages")) {
            ProjectSettings settings = load(root);
            json.addProperty("source_language", settings.sourceLanguage());
            JsonArray languages = new JsonArray();
            settings.languages().forEach(languages::add);
            json.add("languages", languages);
            json.addProperty("namespace", settings.namespace());
        }
        OPTIONS.forEach(json::remove);
        OPTIONS.forEach(key -> {
            if (options.has(key)) {
                json.add(key, options.get(key));
            }
        });
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Never fails: a broken file or a value that is not a language code is reported to {@code problems} and replaced
     * by the default, so a typo cannot turn into a file path outside {@code lang/}.
     */
    public static ProjectSettings load(Path root, Consumer<String> problems) {
        Path file = root.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                String source = DEFAULT_SOURCE;
                if (json.has("source_language")) {
                    String value = json.get("source_language").getAsString().toLowerCase(Locale.ROOT);
                    if (ConfigStorySource.isLanguageCode(value)) {
                        source = value;
                    } else {
                        problems.accept("config/storybound/" + FILE_NAME + ": source_language \"" + value + "\" is not a language code");
                    }
                }
                Set<String> languages = new LinkedHashSet<>();
                if (json.has("languages")) {
                    for (JsonElement element : json.getAsJsonArray("languages")) {
                        String value = element.getAsString().toLowerCase(Locale.ROOT);
                        if (ConfigStorySource.isLanguageCode(value)) {
                            languages.add(value);
                        } else {
                            problems.accept("config/storybound/" + FILE_NAME + ": \"" + value + "\" in languages is not a language code");
                        }
                    }
                }
                languages.add(source);
                String namespace = DEFAULT_NAMESPACE;
                if (json.has("namespace")) {
                    String value = json.get("namespace").getAsString();
                    if (StoryRenames.isStoryNamespace(value)) {
                        namespace = value;
                    } else {
                        problems.accept("config/storybound/" + FILE_NAME + ": namespace \"" + value + "\" is not one a story may use");
                    }
                }
                return new ProjectSettings(source, new ArrayList<>(languages), namespace);
            } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException e) {
                problems.accept("Malformed config/storybound/" + FILE_NAME + ": " + e.getMessage());
            }
        }
        return detect(root);
    }

    /** The languages that have a lang file; {@code en_us} and {@code ru_ru} for a story without any. */
    private static ProjectSettings detect(Path root) {
        Set<String> detected = new LinkedHashSet<>();
        Path folder = root.resolve("lang");
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".json"))
                        .map(name -> name.substring(0, name.length() - ".json".length()).toLowerCase(Locale.ROOT))
                        .filter(ConfigStorySource::isLanguageCode)
                        .sorted()
                        .forEach(detected::add);
            } catch (IOException ignored) {
                // An unreadable folder is reported by the text loader; the defaults below still work.
            }
        }
        if (detected.isEmpty()) {
            detected.add(DEFAULT_SOURCE);
            detected.add("ru_ru");
        }
        String source = detected.contains(DEFAULT_SOURCE) ? DEFAULT_SOURCE : detected.iterator().next();
        return new ProjectSettings(source, new ArrayList<>(detected), DEFAULT_NAMESPACE);
    }

    /** @throws IllegalArgumentException for a value that is not a language code, or a namespace a story may not use */
    public static void save(Path root, ProjectSettings settings) throws IOException {
        if (!ConfigStorySource.isLanguageCode(settings.sourceLanguage()) || !settings.languages().stream().allMatch(ConfigStorySource::isLanguageCode)
                || !StoryRenames.isStoryNamespace(settings.namespace()) && !settings.namespace().equals(DEFAULT_NAMESPACE)) {
            throw new IllegalArgumentException("Not a language code in " + settings);
        }
        Path file = root.resolve(FILE_NAME);
        // Only the languages and the namespace change: other settings of the file (the difficulty scale) stay as written.
        JsonObject json = new JsonObject();
        if (Files.isRegularFile(file)) {
            try {
                JsonElement read = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (read.isJsonObject()) {
                    json = read.getAsJsonObject();
                }
            } catch (JsonParseException e) {
                // A broken file is replaced by the settings that load.
            }
        }
        json.addProperty("source_language", settings.sourceLanguage());
        JsonArray languages = new JsonArray();
        settings.languages().forEach(languages::add);
        json.add("languages", languages);
        json.addProperty("namespace", settings.namespace());
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n", StandardCharsets.UTF_8);
    }
}
