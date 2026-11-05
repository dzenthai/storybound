package dev.dzenthai.storybound.core.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.neoforged.fml.ModList;

/**
 * Sets up the story folder ({@link ConfigStorySource}) from the starter files bundled in the mod
 * ({@code storybound_starter/}, in the story folder's own layout) on first run, and puts the debug scenarios
 * ({@code storybound_scenarios/<name>/}) in and out of it on request.
 */
public final class StoryFolder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /**
     * The debug scenarios' namespace: their files ({@code dialogues/scenario/...}), texts and translation keys
     * ({@code scenario.*}). An author's own story never uses it, so clearing the scenarios removes nothing of theirs.
     */
    public static final String SCENARIO = "scenario";
    private static final String SCENARIOS = "storybound_scenarios";

    private StoryFolder() {}

    /**
     * Creates the story folder with a folder per definition type, a README and four blank documents, unless it exists:
     * deleting it brings the starter files back, changing them never does. There is no demo story any more (November
     * 5, 2026: it started for every player who joined, a live server's too); see the debug scenarios.
     */
    public static void createIfMissing() {
        Path root = ConfigStorySource.root();
        if (Files.exists(root) || ConfigStorySource.isElsewhere()) {
            return;
        }
        try {
            for (DefinitionType<?> type : DefinitionTypes.all()) {
                Files.createDirectories(root.resolve(ConfigStorySource.folder(type)));
            }
            Files.createDirectories(root.resolve("lang"));
            Optional<Path> starter = bundled("storybound_starter");
            if (starter.isPresent()) {
                for (Path file : files(starter.get())) {
                    Path target = root.resolve(relative(starter.get(), file));
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }
            Storybound.LOGGER.info("Storybound: created the story folder {}", root);
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not create the story folder {}", root, e);
        }
    }

    /** The debug scenarios bundled in the mod, by name. */
    public static List<String> scenarios() {
        Optional<Path> source = bundled(SCENARIOS);
        if (source.isEmpty()) {
            return List.of();
        }
        try (Stream<Path> names = Files.list(source.get())) {
            return names.filter(Files::isDirectory).map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not list the debug scenarios: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Copies a debug scenario into the story folder, replacing earlier copies of its files; its translations
     * ({@code scenario.*}) are merged into the folder's lang files. Returns how many files were written. {@code root}
     * is the story folder; tests pass another.
     */
    public static int installScenario(Path root, String name) throws IOException {
        Path source = bundled(SCENARIOS).map(folder -> folder.resolve(name)).filter(Files::isDirectory)
                .orElseThrow(() -> new IOException("No scenario '" + name + "' in this build"));
        int written = 0;
        for (Path file : files(source)) {
            String relative = relative(source, file);
            boolean translations = relative.startsWith("lang/") && relative.endsWith(".json");
            if (!translations && !isScenario(relative)) {
                Storybound.LOGGER.warn("Storybound: scenario {} has {} outside the \"{}\" namespace; left out", name, relative, SCENARIO);
                continue;
            }
            Path destination = root.resolve(relative);
            Files.createDirectories(destination.getParent());
            if (translations) {
                mergeTranslations(file, destination, SCENARIO + ".");
            } else {
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            written++;
        }
        return written;
    }

    /**
     * Removes every scenario from the story folder: the {@code scenario} namespace folder of each type and language and
     * the {@code scenario.*} translations. Nothing else is touched. Returns how many files were deleted.
     */
    public static int clearScenarios(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        for (Path file : files(root)) {
            String relative = relative(root, file);
            if (relative.startsWith("lang/") && relative.endsWith(".json")) {
                removeTranslations(file, SCENARIO + ".");
            } else if (isScenario(relative)) {
                Files.delete(file);
                deleted++;
            }
        }
        // The emptied namespace folders, deepest first.
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path folder : walk.filter(Files::isDirectory).sorted(java.util.Comparator.reverseOrder()).toList()) {
                String relative = relative(root, folder) + "/x";
                if (isScenario(relative) && isEmpty(folder)) {
                    Files.delete(folder);
                }
            }
        }
        return deleted;
    }

    /**
     * Whether a file belongs to the scenarios: its namespace folder is {@code scenario}, right inside a type's folder
     * ({@code dialogues/scenario/...}) or a language's ({@code texts/en_us/scenario/...}). Public for tests.
     */
    public static boolean isScenario(String relative) {
        String[] parts = relative.split("/");
        int namespace = parts[0].equals("texts") ? 2 : 1;
        return parts.length > namespace + 1 && parts[namespace].equals(SCENARIO);
    }

    private static boolean isEmpty(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        }
    }

    /** Takes the translations starting with {@code prefix} out of a lang file. */
    private static void removeTranslations(Path file, String prefix) throws IOException {
        JsonObject kept = new JsonObject();
        boolean removed = false;
        try {
            for (var entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    removed = true;
                } else {
                    kept.add(entry.getKey(), entry.getValue());
                }
            }
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException(file + " is not a valid lang file: " + e.getMessage(), e);
        }
        if (removed) {
            Files.writeString(file, GSON.toJson(kept) + "\n", StandardCharsets.UTF_8);
        }
    }

    /** Adds the file's translations starting with {@code prefix} to the lang file at {@code destination}, replacing the same keys. */
    private static void mergeTranslations(Path file, Path destination, String prefix) throws IOException {
        JsonObject merged = new JsonObject();
        if (Files.exists(destination)) {
            try {
                merged = JsonParser.parseString(Files.readString(destination, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                throw new IOException(destination + " is not a valid lang file: " + e.getMessage(), e);
            }
        }
        JsonObject added = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        for (var entry : added.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                merged.add(entry.getKey(), entry.getValue());
            }
        }
        Files.writeString(destination, GSON.toJson(merged) + "\n", StandardCharsets.UTF_8);
    }

    /** A folder bundled in the mod's jar (or the build's resources in a dev run). */
    private static Optional<Path> bundled(String folder) {
        return ModList.get().getModFileById(Storybound.MOD_ID) == null ? Optional.empty()
                : Optional.of(ModList.get().getModFileById(Storybound.MOD_ID).getFile().findResource(folder)).filter(Files::isDirectory);
    }

    private static List<Path> files(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static String relative(Path folder, Path file) {
        return folder.relativize(file).toString().replace('\\', '/');
    }
}
