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
 * ({@code storybound_starter/}, in the story folder's own layout): all of them on first run, the demo story again on
 * request.
 */
public final class StoryFolder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** The demo story's namespace: its files, texts and translation keys. */
    public static final String DEMO = "demo";
    /**
     * The bundled demo's version: raise it whenever the demo's files change, so a story folder with an older copy is
     * told about it (see {@link #demoOutdated}). 2: the elder spawns once ({@code unique}), October 28, 2026.
     */
    public static final int DEMO_VERSION = 2;
    /** In the story folder: the version of the demo copied there. Missing for copies from before versions were kept (0). */
    static final String DEMO_VERSION_FILE = ".demo_version";

    private StoryFolder() {}

    /**
     * Creates the story folder with a folder per definition type, a README, four blank documents and the demo story,
     * unless it exists: deleting it brings the starter files back, changing them never does.
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
            writeDemoVersion(root);
            Storybound.LOGGER.info("Storybound: created the story folder {}", root);
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not create the story folder {}", root, e);
        }
    }

    /**
     * Copies the demo story from the starter files into the story folder, replacing earlier copies of its files; its
     * translations ({@code demo.*}) are merged into the folder's lang files, and the starter's own files are left
     * alone. For a story folder made before, or one the demo was deleted from. Returns how many files were written.
     * {@code root} is the story folder; tests pass another.
     */
    public static int installDemo(Path root) throws IOException {
        Path source = bundled("storybound_starter").orElseThrow(() -> new IOException("The starter files are not bundled in this build"));
        int written = 0;
        for (Path file : files(source)) {
            String relative = relative(source, file);
            boolean translations = relative.startsWith("lang/") && relative.endsWith(".json");
            if (!translations && !isDemo(relative)) {
                continue;
            }
            Path destination = root.resolve(relative);
            Files.createDirectories(destination.getParent());
            if (translations) {
                mergeTranslations(file, destination, DEMO + ".");
            } else {
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            written++;
        }
        writeDemoVersion(root);
        return written;
    }

    /**
     * Whether the story folder has the demo (its quests are there) in an older version than the mod brings: its files
     * are never replaced on their own (the author may have changed them), so an operator is told to install it again.
     */
    public static boolean demoOutdated(Path root) {
        if (!Files.isDirectory(root.resolve("quests").resolve(DEMO))) {
            return false;
        }
        int installed = 0;
        try {
            Path file = root.resolve(DEMO_VERSION_FILE);
            if (Files.exists(file)) {
                installed = Integer.parseInt(Files.readString(file).trim());
            }
        } catch (IOException | NumberFormatException e) {
            Storybound.LOGGER.warn("Storybound: could not read {}: {}", DEMO_VERSION_FILE, e.getMessage());
        }
        return installed < DEMO_VERSION;
    }

    private static void writeDemoVersion(Path root) {
        try {
            Files.writeString(root.resolve(DEMO_VERSION_FILE), Integer.toString(DEMO_VERSION));
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not write {}: {}", DEMO_VERSION_FILE, e.getMessage());
        }
    }

    /**
     * Whether a starter file belongs to the demo: its namespace folder is {@code demo}, right inside a type's folder
     * ({@code quests/demo/...}) or a language's ({@code texts/en_us/demo/...}). Public for tests.
     */
    public static boolean isDemo(String relative) {
        String[] parts = relative.split("/");
        int namespace = parts[0].equals("texts") ? 2 : 1;
        return parts.length > namespace + 1 && parts[namespace].equals(DEMO);
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
