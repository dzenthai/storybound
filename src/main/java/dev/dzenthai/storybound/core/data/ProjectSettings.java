package dev.dzenthai.storybound.core.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
 * The story's languages, in {@code config/storybound/project.json}:
 * {@code { "source_language": "en_us", "languages": ["en_us", "ru_ru"] }}. The source language is the one authors
 * write in; the dev mode edits texts per language from the list. Without the file the languages are those with a
 * {@code lang/<language>.json}, and {@code en_us} is the source when present.
 */
public record ProjectSettings(String sourceLanguage, List<String> languages) {
    public static final String DEFAULT_SOURCE = "en_us";
    public static final String FILE_NAME = "project.json";

    public static ProjectSettings load(Path root) {
        return load(root, problem -> {});
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
                return new ProjectSettings(source, new ArrayList<>(languages));
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
        return new ProjectSettings(source, new ArrayList<>(detected));
    }

    /** @throws IllegalArgumentException for a value that is not a language code */
    public static void save(Path root, ProjectSettings settings) throws IOException {
        if (!ConfigStorySource.isLanguageCode(settings.sourceLanguage()) || !settings.languages().stream().allMatch(ConfigStorySource::isLanguageCode)) {
            throw new IllegalArgumentException("Not a language code in " + settings);
        }
        Path file = root.resolve(FILE_NAME);
        // Only the languages change: other settings of the file (the difficulty scale) stay as written.
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
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n", StandardCharsets.UTF_8);
    }
}
