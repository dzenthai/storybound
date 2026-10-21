package dev.dzenthai.storybound.core.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The story folder, {@code config/storybound/}: story data that needs no datapack. Each definition type has a folder
 * named like its datapack directory without the {@code storybound/} prefix ({@code quests}, {@code documents}, ...).
 * Inside it, the first folder is the namespace: {@code quests/mypack/wolf_hunt.json} is {@code mypack:wolf_hunt};
 * a file right in the type folder gets {@link #DEFAULT_NAMESPACE}: {@code quests/wolf_hunt.json} is
 * {@code storybound:wolf_hunt}. Read on every data load, after the datapacks, so a file here replaces a datapack
 * entry with the same id.
 */
public final class ConfigStorySource {
    public static final String DEFAULT_NAMESPACE = "storybound";
    private static final String DATAPACK_PREFIX = "storybound/";
    private static final String JSON = ".json";

    private ConfigStorySource() {}

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("storybound");
    }

    /** The type's folder name inside the story folder, e.g. {@code quests}. */
    public static String folder(DefinitionType<?> type) {
        String directory = type.directory();
        return directory.startsWith(DATAPACK_PREFIX) ? directory.substring(DATAPACK_PREFIX.length()) : directory;
    }

    /**
     * The id of a file from its path inside a type (or text) folder, with {@code /} separators and without the
     * extension: {@code wolf_hunt} is {@code storybound:wolf_hunt}, {@code mypack/wolf_hunt} is {@code mypack:wolf_hunt},
     * {@code mypack/act1/wolf_hunt} is {@code mypack:act1/wolf_hunt}. Empty when it is not a valid id (capitals,
     * spaces). Public for tests.
     */
    public static Optional<ResourceLocation> idOf(String relativeWithoutExtension) {
        int slash = relativeWithoutExtension.indexOf('/');
        String namespace = slash < 0 ? DEFAULT_NAMESPACE : relativeWithoutExtension.substring(0, slash);
        String path = slash < 0 ? relativeWithoutExtension : relativeWithoutExtension.substring(slash + 1);
        return Optional.ofNullable(ResourceLocation.tryBuild(namespace, path));
    }

    /**
     * Reads the type's JSON files under {@code root} into {@code files} (replacing what the datapacks gave for the
     * same id) and reports broken ones to {@code issues}, with the file's path. Public for tests.
     */
    public static void scan(Path root, DefinitionType<?> type, Map<ResourceLocation, JsonElement> files, List<ValidationIssue> issues) {
        Path folder = root.resolve(folder(type));
        list(folder, JSON).forEach((relative, file) -> {
            String shown = "config/storybound/" + folder(type) + "/" + relative + JSON;
            Optional<ResourceLocation> id = idOf(relative);
            if (id.isEmpty()) {
                issues.add(ValidationIssue.error(type.id(), null, shown + ": the file name is not a valid id (use lowercase letters, digits, _ - . and /)"));
                return;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                files.put(id.get(), JsonParser.parseReader(reader));
            } catch (IOException | JsonParseException e) {
                issues.add(ValidationIssue.error(type.id(), id.get(), "Malformed JSON in " + shown + ": " + e.getMessage()));
            }
        });
    }

    /**
     * The file a definition is saved to: the reverse of {@link #idOf}, so it loads back under the same id.
     * Throws {@link IllegalArgumentException} for an id that would leave the story folder ({@code ..} in its path).
     */
    public static Path fileFor(Path root, DefinitionType<?> type, ResourceLocation id) {
        return inside(root, root.resolve(folder(type)), id, JSON);
    }

    /** The {@code .sbt} file of a long text in a language; throws like {@link #fileFor}, also for a bad language code. */
    public static Path sbtFileFor(Path root, String language, ResourceLocation id) {
        if (!isLanguageCode(language)) {
            throw new IllegalArgumentException("Not a language code: " + language);
        }
        return inside(root, root.resolve("texts").resolve(language), id, ".sbt");
    }

    /** A Minecraft language code like {@code en_us}, {@code ru_ru} or {@code zh_cn}; anything else never becomes a file name. */
    public static boolean isLanguageCode(String language) {
        return language.matches("[a-z]{2,4}_[a-z0-9]{2,4}");
    }

    /**
     * {@code folder/<path>.ext} for the default namespace when the path has no folder (so {@link #idOf} reads it back),
     * else {@code folder/<namespace>/<path>.ext}; an existing {@code folder/storybound/<path>.ext} is kept where it is.
     */
    private static Path inside(Path root, Path folder, ResourceLocation id, String extension) {
        for (String segment : id.getPath().split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Not a file path inside the story folder: " + id);
            }
        }
        Path namespaced = folder.resolve(id.getNamespace()).resolve(id.getPath() + extension);
        Path file = id.getNamespace().equals(DEFAULT_NAMESPACE) && !id.getPath().contains("/") && !Files.exists(namespaced)
                ? folder.resolve(id.getPath() + extension)
                : namespaced;
        if (!file.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Not a file path inside the story folder: " + id);
        }
        return file;
    }

    /** Files with the extension under {@code folder}, by their path inside it without the extension, sorted. */
    static Map<String, Path> list(Path folder, String extension) {
        Map<String, Path> found = new TreeMap<>();
        if (!Files.isDirectory(folder)) {
            return found;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .forEach(file -> {
                        String relative = folder.relativize(file).toString().replace('\\', '/');
                        found.put(relative.substring(0, relative.length() - extension.length()), file);
                    });
        } catch (IOException e) {
            // An unreadable folder reads as empty; the rest of the story still loads.
        }
        return found;
    }
}
