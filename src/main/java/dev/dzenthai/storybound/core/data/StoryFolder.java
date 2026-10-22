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
 * Sets up the story folder ({@link ConfigStorySource}): the starter files on first run, and the example story on
 * request. Both are bundled in the mod: {@code storybound_starter/} in the story folder's own layout, and
 * {@code storybound_example/} as the example datapack and resource pack (from {@code examples/}), turned into the
 * story folder's layout while copying.
 */
public final class StoryFolder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private StoryFolder() {}

    /**
     * Creates the story folder with a folder per definition type, a README and four blank documents, unless it
     * exists: deleting it brings the starter files back, changing them never does.
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

    /**
     * Copies the example story into the story folder, replacing earlier copies of its files; its translations are
     * merged into the folder's lang files. Returns how many files were written. {@code root} is the story folder;
     * tests pass another.
     */
    public static int installExample(Path root) throws IOException {
        Path source = bundled("storybound_example").orElseThrow(() -> new IOException("The example is not bundled in this build"));
        int written = 0;
        for (Path file : files(source)) {
            String relative = relative(source, file);
            Optional<String> target = examplePath(relative);
            if (target.isEmpty()) {
                continue;
            }
            Path destination = root.resolve(target.get());
            Files.createDirectories(destination.getParent());
            if (target.get().startsWith("lang/")) {
                mergeTranslations(file, destination);
            } else {
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            written++;
        }
        return written;
    }

    /**
     * Where a file of the example's datapack or resource pack goes in the story folder, or empty for files it does
     * not need ({@code pack.mcmeta}):
     * {@code data/<ns>/storybound/<type>/<path>.json} to {@code <type>/<ns>/<path>.json},
     * {@code assets/<ns>/lang/<language>.json} to {@code lang/<language>.json} (merged),
     * {@code assets/<ns>/storybound/texts/<language>/<path>.sbt} to {@code texts/<language>/<ns>/<path>.sbt}.
     * Public for tests.
     */
    public static Optional<String> examplePath(String relative) {
        String[] parts = relative.split("/");
        if (parts.length >= 5 && parts[0].equals("data") && parts[2].equals("storybound") && relative.endsWith(".json")) {
            return Optional.of(parts[3] + "/" + parts[1] + "/" + String.join("/", List.of(parts).subList(4, parts.length)));
        }
        if (parts.length == 4 && parts[0].equals("assets") && parts[2].equals("lang") && relative.endsWith(".json")) {
            return Optional.of("lang/" + parts[3]);
        }
        if (parts.length >= 6 && parts[0].equals("assets") && parts[2].equals("storybound") && parts[3].equals("texts") && relative.endsWith(".sbt")) {
            return Optional.of("texts/" + parts[4] + "/" + parts[1] + "/" + String.join("/", List.of(parts).subList(5, parts.length)));
        }
        return Optional.empty();
    }

    /** Adds the file's translations to the lang file at {@code destination}, replacing the same keys. */
    private static void mergeTranslations(Path file, Path destination) throws IOException {
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
            merged.add(entry.getKey(), entry.getValue());
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
