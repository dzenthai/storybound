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
 * ({@code storybound_starter/}, in the story folder's own layout) on first run, puts the debug scenarios
 * ({@code storybound_scenarios/<name>/}) in and out of it on request, and installs the stories bundled in the mod
 * ({@code storybound_stories/<id>/}, each a whole story) as stories of their own.
 */
public final class StoryFolder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /**
     * The debug scenarios' namespace: their files ({@code dialogues/scenario/...}), texts and translation keys
     * ({@code scenario.*}). An author's own story never uses it, so clearing the scenarios removes nothing of theirs.
     */
    public static final String SCENARIO = "scenario";
    private static final String SCENARIOS = "storybound_scenarios";
    private static final String STORIES = "storybound_stories";

    private StoryFolder() {}

    /** The story a new story folder starts with. */
    public static final String FIRST_STORY = "my_story";

    /**
     * Readies the story folder at start: an old layout (a story's files right in it, others in {@code stories/}) is
     * moved to one folder per story ({@link StoryProjects#migrate}, each move in the log); a missing folder is created
     * with a README and the story {@value #FIRST_STORY} (a folder per definition type and four blank documents):
     * deleting the folder brings them back, changing them never does. A folder given by {@code -Dstorybound.folder} (a
     * pack's repository checked by CI) is never changed: an old layout there is a validation error instead. There is no
     * demo story any more; see the debug scenarios.
     */
    public static void createIfMissing() {
        Path root = ConfigStorySource.root();
        if (ConfigStorySource.isElsewhere()) {
            return;
        }
        if (Files.exists(root)) {
            if (StoryProjects.oldLayout(root)) {
                try {
                    List<String> moved = StoryProjects.migrate(root);
                    Storybound.LOGGER.info("Storybound: the story folder {} now has a folder per story ({} moves)", root, moved.size());
                    moved.forEach(line -> Storybound.LOGGER.info("Storybound:   {}", line));
                    // Debug scenarios were installed among the story's own files; they have their own story now.
                    for (StoryProjects.Project story : StoryProjects.list(root)) {
                        if (!story.id().equals(StoryProjects.SCENARIOS)) {
                            int cleared = clearScenarios(story.root());
                            if (cleared > 0) {
                                Storybound.LOGGER.info("Storybound:   {} debug scenario files taken out of {}", cleared, story.id());
                            }
                        }
                    }
                } catch (IOException e) {
                    Storybound.LOGGER.error("Storybound: could not move the story folder {} to a folder per story; move its files into a story folder by hand", root, e);
                }
            }
            return;
        }
        try {
            Path story = root.resolve(FIRST_STORY);
            for (DefinitionType<?> type : DefinitionTypes.all()) {
                Files.createDirectories(story.resolve(ConfigStorySource.folder(type)));
            }
            Files.createDirectories(story.resolve("lang"));
            Optional<Path> starter = bundled("storybound_starter");
            if (starter.isPresent()) {
                for (Path file : files(starter.get())) {
                    String relative = relative(starter.get(), file);
                    // The README explains the whole folder; the rest is the first story's.
                    Path target = relative.equals("README.txt") ? root.resolve(relative) : story.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
            }
            // Its id is its namespace, as for a story made in the editor: new nodes get my_story:..., not storybound:...
            Path settings = story.resolve(ProjectSettings.FILE_NAME);
            JsonObject json = Files.isRegularFile(settings)
                    ? JsonParser.parseString(Files.readString(settings, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
            if (!json.has("title")) {
                json.addProperty("title", "My story");
            }
            if (!json.has("namespace")) {
                json.addProperty("namespace", FIRST_STORY);
            }
            Files.writeString(settings, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
            Storybound.LOGGER.info("Storybound: created the story folder {} with the story {}", root, FIRST_STORY);
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not create the story folder {}", root, e);
        }
    }

    /**
     * A story folder the mod does not move by itself ({@code -Dstorybound.folder}, a pack's repository checked by CI)
     * still in the old layout is an error: none of its files would load, and a check would pass on nothing.
     */
    public static void validate(java.util.function.Consumer<ValidationIssue> sink) {
        Path root = ConfigStorySource.root();
        if (Files.isDirectory(root) && StoryProjects.oldLayout(root)) {
            sink.accept(ValidationIssue.error(Storybound.id("project"), null, "The story folder " + root
                    + " has the layout of before: move its files into a folder per story (" + FIRST_STORY
                    + "/quests/..., with its project.json), as the game does by itself in config/storybound"));
        }
    }

    /**
     * The debug scenarios' story, {@code <story folder>/scenarios/}, made when missing: in the game, its namespace
     * {@value #SCENARIO}, so an author's own story is never touched by installing or clearing them.
     */
    public static Path scenariosStory(Path workspace) throws IOException {
        Path story = StoryProjects.resolve(workspace, StoryProjects.SCENARIOS);
        Path settings = story.resolve(ProjectSettings.FILE_NAME);
        if (!Files.isRegularFile(settings)) {
            Files.createDirectories(story);
            JsonObject json = new JsonObject();
            json.addProperty("title", "Debug scenarios");
            json.addProperty("enabled", true);
            json.addProperty("namespace", SCENARIO);
            Files.writeString(settings, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
        }
        return story;
    }

    /** Takes the debug scenarios' story away, folder and all; returns how many files went. */
    public static int removeScenariosStory(Path workspace) throws IOException {
        Path story = StoryProjects.resolve(workspace, StoryProjects.SCENARIOS);
        if (!Files.isDirectory(story)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> walk = Files.walk(story)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(path) && !path.getFileName().toString().equals(ProjectSettings.FILE_NAME)) {
                    deleted++;
                }
                Files.delete(path);
            }
        }
        return deleted;
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

    /** The stories bundled in the mod, by id (their folder's name). */
    public static List<String> bundledStories() {
        Optional<Path> source = bundled(STORIES);
        if (source.isEmpty()) {
            return List.of();
        }
        try (Stream<Path> names = Files.list(source.get())) {
            return names.filter(path -> Files.isRegularFile(path.resolve(ProjectSettings.FILE_NAME)))
                    .map(path -> path.getFileName().toString().replace("/", "")).sorted().toList();
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not list the bundled stories: {}", e.getMessage());
            return List.of();
        }
    }

    /** A bundled story's title, as its {@code project.json} gives it; its id when it gives none. */
    public static String bundledTitle(String id) {
        return bundled(STORIES).map(folder -> folder.resolve(id).resolve(ProjectSettings.FILE_NAME)).filter(Files::isRegularFile).map(file -> {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                return json.has("title") ? json.get("title").getAsString() : id;
            } catch (IOException | JsonParseException | IllegalStateException e) {
                return id;
            }
        }).orElse(id);
    }

    /**
     * Copies a bundled story into the story folder as the story {@code id}, every file as it is: to play, to open in the
     * editor and change. A story of that id already there stays, unless {@code replace}: then its folder goes to
     * {@code .deleted} first, nothing erased. Returns how many files were written.
     *
     * @throws IllegalStateException when the story is there and {@code replace} is false
     */
    public static int installStory(Path workspace, String id, boolean replace) throws IOException {
        Path source = bundled(STORIES).map(folder -> folder.resolve(id)).filter(Files::isDirectory)
                .orElseThrow(() -> new IOException("No story '" + id + "' in this build"));
        Path target = StoryProjects.resolve(workspace, id);
        if (Files.exists(target)) {
            if (!replace) {
                throw new IllegalStateException("The story " + id + " is already in the story folder");
            }
            StoryProjects.trash(workspace, target, id);
        }
        int written = 0;
        for (Path file : files(source)) {
            Path destination = target.resolve(relative(source, file));
            Files.createDirectories(destination.getParent());
            Files.copy(file, destination);
            written++;
        }
        return written;
    }

    /** A bundled story's own folder, read where it is (the jar, the build's resources in a dev run). */
    public static Optional<Path> bundledRoot(String id) {
        return bundled(STORIES).map(folder -> folder.resolve(id)).filter(Files::isDirectory);
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
