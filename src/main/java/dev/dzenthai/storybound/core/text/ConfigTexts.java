package dev.dzenthai.storybound.core.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.ProjectSettings;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Texts from the story folder, so a pack needs no resource pack: translations in {@code lang/<language>.json}
 * (flat {@code "key": "text"} like a resource pack's lang file) and long texts in {@code texts/<language>/...sbt}
 * (named like definitions: {@code texts/en_us/mypack/letter.sbt} is {@code mypack:letter}, a file right in the
 * language folder gets the {@code storybound} namespace). Read on every data load and sent to every client, which
 * prefers them to resource packs.
 */
public final class ConfigTexts {
    public static final ResourceLocation SOURCE = Storybound.id("story_folder_texts");
    private static final int MAX_TEXT = 1 << 20;
    private static volatile Texts current = Texts.EMPTY;

    private ConfigTexts() {}

    /**
     * @param translations language → key → text
     * @param documents    language → text id → {@code .sbt} source
     */
    public record Texts(String sourceLanguage, Map<String, Map<String, String>> translations, Map<String, Map<ResourceLocation, String>> documents) {
        public static final Texts EMPTY = new Texts(ProjectSettings.DEFAULT_SOURCE, Map.of(), Map.of());
        private static final StreamCodec<ByteBuf, String> LONG_STRING = ByteBufCodecs.stringUtf8(MAX_TEXT);
        private static final StreamCodec<ByteBuf, Map<String, String>> TRANSLATIONS = ByteBufCodecs.map(HashMap::new, LONG_STRING, LONG_STRING);
        private static final StreamCodec<ByteBuf, Map<ResourceLocation, String>> DOCUMENTS = ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, LONG_STRING);
        public static final StreamCodec<ByteBuf, Texts> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Texts::sourceLanguage,
                ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, TRANSLATIONS), Texts::translations,
                ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, DOCUMENTS), Texts::documents,
                Texts::new);
    }

    public static Texts current() {
        return current;
    }

    public static void reload() {
        List<ValidationIssue> issues = new ArrayList<>();
        current = load(ConfigStorySource.root(), issues);
        ValidationService.recordLoad(SOURCE, issues);
    }

    public static Texts load(Path root, List<ValidationIssue> issues) {
        ProjectSettings settings = ProjectSettings.load(root, problem -> issues.add(ValidationIssue.error(SOURCE, null, problem)));
        return new Texts(settings.sourceLanguage(), translations(root.resolve("lang"), issues), documents(root.resolve("texts"), issues));
    }

    private static Map<String, Map<String, String>> translations(Path folder, List<ValidationIssue> issues) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        for (Path file : files(folder)) {
            String name = file.getFileName().toString();
            if (!name.endsWith(".json")) {
                continue;
            }
            String language = name.substring(0, name.length() - ".json".length());
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> entries = new TreeMap<>();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        entries.put(entry.getKey(), entry.getValue().getAsString());
                    } else {
                        issues.add(ValidationIssue.warning(SOURCE, null, "config/storybound/lang/" + name + ": " + entry.getKey() + " is not a text"));
                    }
                }
                result.put(language, entries);
            } catch (IOException | JsonParseException | IllegalStateException e) {
                issues.add(ValidationIssue.error(SOURCE, null, "Malformed JSON in config/storybound/lang/" + name + ": " + e.getMessage()));
            }
        }
        return result;
    }

    private static Map<String, Map<ResourceLocation, String>> documents(Path folder, List<ValidationIssue> issues) {
        Map<String, Map<ResourceLocation, String>> result = new TreeMap<>();
        for (Path languageFolder : files(folder)) {
            if (!Files.isDirectory(languageFolder)) {
                continue;
            }
            String language = languageFolder.getFileName().toString();
            Map<ResourceLocation, String> texts = new TreeMap<>();
            try (Stream<Path> walk = Files.walk(languageFolder)) {
                for (Path file : walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".sbt")).toList()) {
                    String relative = languageFolder.relativize(file).toString().replace('\\', '/');
                    String shown = "config/storybound/texts/" + language + "/" + relative;
                    var id = ConfigStorySource.idOf(relative.substring(0, relative.length() - ".sbt".length()));
                    if (id.isEmpty()) {
                        issues.add(ValidationIssue.error(SOURCE, null, shown + ": the file name is not a valid id (use lowercase letters, digits, _ - . and /)"));
                        continue;
                    }
                    try {
                        texts.put(id.get(), Files.readString(file, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        issues.add(ValidationIssue.error(SOURCE, id.get(), "Cannot read " + shown + ": " + e.getMessage()));
                    }
                }
            } catch (IOException e) {
                issues.add(ValidationIssue.error(SOURCE, null, "Cannot read config/storybound/texts/" + language + ": " + e.getMessage()));
            }
            result.put(language, texts);
        }
        return result;
    }

    private static List<Path> files(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
