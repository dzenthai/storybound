package dev.dzenthai.storybound.core.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.core.story.StoryRenames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/**
 * The stories of the story folder (November 18, 2026): one folder, one story. Each folder of {@code config/storybound/}
 * holding a {@code project.json} is a story, its folder's name its id, with its own definitions, texts, sounds and
 * settings inside ({@code config/storybound/wolf_hunt/quests/...}); the debug scenarios are the story
 * {@value #SCENARIOS}. A story's {@code project.json} gives its {@code title} (else its namespace) and whether it is
 * {@code enabled} (unless it says no; a new one starts off): only enabled stories load into the game ({@link #scan}),
 * a definition id in two of them is an error and the first one keeps it. {@link #owner} says which story a loaded
 * definition came from, so the editor saves it back there; the editor works on one story at a time (see
 * {@code DevProjects}). The game's own settings (difficulty scale, chapter map, tracking a quest when given) are the
 * {@link #primary} story's. A new story's id is its namespace, made from its title ({@link #suggestId}).
 * <p>
 * The same day, first: {@code config/storybound/} itself was a story ({@code default}) and the others were in
 * {@code stories/<id>/}, a story inside a story; {@link #migrate} moves such a folder to the layout above.
 */
public final class StoryProjects {
    private static final Map<ResourceLocation, Map<ResourceLocation, Path>> OWNERS = new ConcurrentHashMap<>();
    /** The debug scenarios' story ({@code /storybound debug scenario}). */
    public static final String SCENARIOS = "scenarios";
    /** A story folder's name. */
    private static final String ID = "[a-z0-9][a-z0-9_-]{0,63}";
    /** What stays in the story folder itself when an old layout is moved into a story. */
    private static final String README = "README.txt";
    /** Where the other stories were, the same day, before one folder was one story. */
    private static final String OLD_STORIES = "stories";

    /** A story: its id (its folder's name), title, whether it loads into the game, folder. */
    public record Project(String id, String title, boolean enabled, Path root) {}

    private StoryProjects() {}

    /** A story's folder; refused for a name that is not an id or would lead outside the story folder. */
    public static Path resolve(Path workspace, String id) {
        if (!id.matches(ID)) {
            throw new IllegalArgumentException("Invalid story id: " + id);
        }
        Path base = workspace.toAbsolutePath().normalize();
        Path result = base.resolve(id).normalize();
        if (!base.equals(result.getParent()) || Files.isSymbolicLink(result)) {
            throw new IllegalArgumentException("Story folder must stay inside the workspace");
        }
        return result;
    }

    /** The stories, by id. */
    public static List<Project> list(Path workspace) {
        List<Project> result = new ArrayList<>();
        if (Files.isDirectory(workspace)) {
            try (var directories = Files.list(workspace)) {
                for (Path folder : directories.sorted().toList()) {
                    if (isStory(folder)) {
                        result.add(describe(workspace, folder.getFileName().toString()));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot list stories", e);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isStory(Path folder) {
        return folder.getFileName().toString().matches(ID) && Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(folder.resolve(ProjectSettings.FILE_NAME));
    }

    /**
     * The story the game's own settings come from and the editor opens first: the first enabled one that is not the
     * debug scenarios, else the first enabled, else the first; empty when there is none.
     */
    public static java.util.Optional<Project> primary(Path workspace) {
        List<Project> stories = list(workspace);
        return stories.stream().filter(project -> project.enabled() && !SCENARIOS.equals(project.id())).findFirst()
                .or(() -> stories.stream().filter(Project::enabled).findFirst())
                .or(() -> stories.stream().findFirst());
    }

    /** The primary story's folder, else the story folder itself (no story yet: what is written there is moved into one on the next start). */
    public static Path primaryRoot() {
        Path workspace = ConfigStorySource.root();
        return primary(workspace).map(Project::root).orElse(workspace.toAbsolutePath().normalize());
    }

    /** Whether the folder still has a story's files right in it, or the other stories in {@code stories/}: the layout of before. */
    public static boolean oldLayout(Path workspace) {
        return Files.isRegularFile(workspace.resolve(ProjectSettings.FILE_NAME)) || Files.isDirectory(workspace.resolve(OLD_STORIES));
    }

    /**
     * Moves an old layout to one folder per story: the story right in the folder into a folder named after its
     * namespace (or a free name made from it), every entry but {@code README.txt} and the story folders already there;
     * then each story of {@code stories/} up beside it, and {@code stories/} away once empty. Nothing is overwritten: a
     * name already taken is left where it is and said so. Returns what was done, a line each, for the log.
     */
    public static List<String> migrate(Path workspace) throws IOException {
        List<String> done = new ArrayList<>();
        Path base = workspace.toAbsolutePath().normalize();
        if (Files.isRegularFile(base.resolve(ProjectSettings.FILE_NAME))) {
            // Taken: every name in the folder (a type's folder too: a namespace "quests" must not land in quests/) and
            // every story of stories/, which moves up beside it next.
            Set<String> taken = new java.util.HashSet<>();
            try (var entries = Files.list(base)) {
                entries.forEach(entry -> taken.add(entry.getFileName().toString()));
            }
            if (Files.isDirectory(base.resolve(OLD_STORIES), LinkOption.NOFOLLOW_LINKS)) {
                try (var entries = Files.list(base.resolve(OLD_STORIES))) {
                    entries.forEach(entry -> taken.add(entry.getFileName().toString()));
                }
            }
            String namespace = ProjectSettings.load(base).namespace();
            String id = namespace.matches(ID) && !taken.contains(namespace) && !SCENARIOS.equals(namespace)
                    ? namespace : suggestId(namespace, taken);
            Path target = base.resolve(id);
            Files.createDirectory(target);
            try (var entries = Files.list(base)) {
                for (Path entry : entries.toList()) {
                    String name = entry.getFileName().toString();
                    if (entry.equals(target) || name.equals(README) || name.equals(OLD_STORIES) || isStory(entry)) {
                        continue;
                    }
                    Files.move(entry, target.resolve(name));
                    done.add(name + " -> " + id + "/" + name);
                }
            }
        }
        Path stories = base.resolve(OLD_STORIES);
        if (Files.isDirectory(stories, LinkOption.NOFOLLOW_LINKS)) {
            try (var folders = Files.list(stories)) {
                for (Path folder : folders.toList()) {
                    String name = folder.getFileName().toString();
                    if (!isStory(folder)) {
                        continue;
                    }
                    Path target = base.resolve(name);
                    if (Files.exists(target)) {
                        done.add("left " + OLD_STORIES + "/" + name + ": " + name + " is taken");
                    } else {
                        Files.move(folder, target);
                        done.add(OLD_STORIES + "/" + name + " -> " + name);
                    }
                }
            }
            try (var left = Files.list(stories)) {
                if (left.findAny().isEmpty()) {
                    Files.delete(stories);
                }
            }
        }
        return done;
    }

    public static Project get(Path workspace, String id) {
        resolve(workspace, id);
        return list(workspace).stream().filter(project -> project.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown story: " + id));
    }

    private static Project describe(Path workspace, String id) {
        Path root = resolve(workspace, id);
        JsonObject json = ProjectSettings.raw(root);
        String title = json.has("title") && json.get("title").isJsonPrimitive() ? json.get("title").getAsString() : "";
        if (title.isBlank()) {
            title = ProjectSettings.load(root).namespace();
        }
        boolean enabled = !json.has("enabled") || !json.get("enabled").isJsonPrimitive()
                || !json.getAsJsonPrimitive("enabled").isBoolean() || json.get("enabled").getAsBoolean();
        return new Project(id, title, enabled, root);
    }

    /** Ids a new story never gets: the scenarios' story, the old layout's folder, and namespaces the mod or the game keep for themselves. */
    public static final Set<String> RESERVED = Set.of(SCENARIOS, OLD_STORIES, "default", "minecraft", "scenario", "storybound");
    /** How long a suggested id is at most (an id may be 64 long; a suggestion leaves room for "_2"). */
    private static final int SUGGESTED_MAX = 40;
    private static final Map<Character, String> LATIN = latin();

    /**
     * A new story's id, which is also its namespace (the prefix of its nodes' ids), made from its title (November 18,
     * 2026; before, a random {@code story_1a2b3c4d}, which then stood in every id and translation key): lowercase Latin
     * letters, digits and {@code _}, Cyrillic written in Latin letters ("Моя история" is {@code moya_istoriya});
     * {@code story_1}, {@code story_2}... when nothing is left of the title; {@code _2}, {@code _3}... after one that
     * is taken or reserved. A valid id for {@link #resolve} and {@link StoryRenames#isStoryNamespace}.
     */
    public static String suggestId(String title, Set<String> taken) {
        StringBuilder out = new StringBuilder();
        for (char ch : title.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9') {
                out.append(ch);
            } else if (LATIN.containsKey(ch)) {
                out.append(LATIN.get(ch));
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        String base = out.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (base.length() > SUGGESTED_MAX) {
            base = base.substring(0, SUGGESTED_MAX).replaceAll("_$", "");
        }
        if (base.isEmpty()) {
            int number = 1;
            while (taken.contains("story_" + number) || RESERVED.contains("story_" + number)) {
                number++;
            }
            return "story_" + number;
        }
        String id = base;
        for (int number = 2; taken.contains(id) || RESERVED.contains(id); number++) {
            id = base + "_" + number;
        }
        return id;
    }

    /** Whether a typed id can be a new story's: the folder's and the namespace's rules both. */
    public static boolean validId(String id) {
        return id.matches("[a-z0-9][a-z0-9_-]{0,63}") && !RESERVED.contains(id) && StoryRenames.isStoryNamespace(id);
    }

    /** Cyrillic letters (Russian, Ukrainian, Belarusian) in Latin ones, as names are usually written. */
    private static Map<Character, String> latin() {
        String[][] pairs = {{"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"е", "e"}, {"ё", "yo"}, {"ж", "zh"}, {"з", "z"},
                {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"}, {"о", "o"}, {"п", "p"}, {"р", "r"}, {"с", "s"},
                {"т", "t"}, {"у", "u"}, {"ф", "f"}, {"х", "kh"}, {"ц", "ts"}, {"ч", "ch"}, {"ш", "sh"}, {"щ", "shch"}, {"ъ", ""}, {"ы", "y"},
                {"ь", ""}, {"э", "e"}, {"ю", "yu"}, {"я", "ya"}, {"і", "i"}, {"ї", "yi"}, {"є", "ye"}, {"ґ", "g"}, {"ў", "u"}};
        Map<Character, String> map = new java.util.HashMap<>();
        for (String[] pair : pairs) {
            map.put(pair[0].charAt(0), pair[1]);
        }
        return Map.copyOf(map);
    }

    public static Project create(Path workspace, String id, String title, String sourceLanguage) throws IOException {
        Path root = resolve(workspace, id);
        if (RESERVED.contains(id) || Files.exists(root) || !StoryRenames.isStoryNamespace(id)) {
            throw new IllegalArgumentException("Story id is reserved or already exists: " + id);
        }
        for (Project project : list(workspace)) {
            if (ProjectSettings.load(project.root()).namespace().equals(id)) {
                throw new IllegalArgumentException("Namespace already belongs to another story: " + id);
            }
        }
        checkTitle(title);
        if (!ConfigStorySource.isLanguageCode(sourceLanguage)) {
            throw new IllegalArgumentException("Invalid source language");
        }
        Files.createDirectories(root.getParent());
        Files.createDirectory(root);
        JsonObject json = new JsonObject();
        json.addProperty("title", title.trim());
        json.addProperty("enabled", false);
        json.addProperty("namespace", id);
        json.addProperty("source_language", sourceLanguage);
        JsonArray languages = new JsonArray();
        languages.add(sourceLanguage);
        json.add("languages", languages);
        write(root, json);
        return get(workspace, id);
    }

    /** Where deleted stories go, in the story folder: not a story's name (it starts with a dot), so never listed. */
    public static final String DELETED = ".deleted";

    /**
     * Deletes a story (November 19, 2026, the author: there was no way but by hand): its folder is moved into
     * {@value #DELETED}{@code /<id>-<date and time>/}, nothing erased, so moving it back brings it back. The last story is
     * not deleted: the editor and the game's own settings need one. Returns where the folder went.
     */
    public static Path delete(Path workspace, String id) throws IOException {
        Project project = get(workspace, id);
        if (list(workspace).size() <= 1) {
            throw new IllegalArgumentException("The last story cannot be deleted: make another first");
        }
        Path trash = workspace.toAbsolutePath().normalize().resolve(DELETED);
        Files.createDirectories(trash);
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = trash.resolve(id + "-" + stamp);
        for (int n = 2; Files.exists(target); n++) {
            target = trash.resolve(id + "-" + stamp + "-" + n);
        }
        Files.move(project.root(), target);
        return target;
    }

    public static void update(Path workspace, String id, String title, boolean enabled) throws IOException {
        Project project = get(workspace, id);
        checkTitle(title);
        JsonObject json = read(project.root());
        json.addProperty("title", title.trim());
        json.addProperty("enabled", enabled);
        write(project.root(), json);
    }

    private static void checkTitle(String title) {
        if (title.isBlank() || title.length() > 80 || title.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A story title must contain 1 to 80 printable characters");
        }
    }

    private static JsonObject read(Path root) throws IOException {
        Path file = root.resolve(ProjectSettings.FILE_NAME);
        return Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
    }

    private static void write(Path root, JsonObject json) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve(ProjectSettings.FILE_NAME),
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n");
    }

    public static void scan(Path workspace, DefinitionType<?> type, Map<ResourceLocation, JsonElement> files, List<ValidationIssue> issues) {
        Map<ResourceLocation, Path> owners = new LinkedHashMap<>();
        for (Project project : list(workspace)) {
            if (!project.enabled()) {
                continue;
            }
            Map<ResourceLocation, JsonElement> local = new LinkedHashMap<>();
            ConfigStorySource.scan(project.root(), type, local, issues);
            local.forEach((id, json) -> {
                if (owners.containsKey(id)) {
                    issues.add(ValidationIssue.error(type.id(), id, "Duplicate definition in story " + project.id()
                            + "; already loaded from " + owners.get(id)));
                } else {
                    owners.put(id, project.root());
                    files.put(id, json);
                }
            });
        }
        OWNERS.put(type.id(), Map.copyOf(owners));
    }

    public static void recordOwner(DefinitionType<?> type, ResourceLocation id, Path root) {
        OWNERS.compute(type.id(), (key, current) -> {
            Map<ResourceLocation, Path> copy = new LinkedHashMap<>(current == null ? Map.of() : current);
            copy.put(id, root);
            return Map.copyOf(copy);
        });
    }

    public static Path owner(DefinitionType<?> type, ResourceLocation id) {
        Path owner = OWNERS.getOrDefault(type.id(), Map.of()).get(id);
        return owner != null ? owner : primaryRoot();
    }

    public static void checkWritable(Path root, DefinitionType<?> type, ResourceLocation id) {
        for (Project project : list(ConfigStorySource.root())) {
            if (!project.root().equals(root.toAbsolutePath().normalize())
                    && Files.isRegularFile(ConfigStorySource.fileFor(project.root(), type, id))) {
                throw new IllegalArgumentException("Definition belongs to story " + project.title() + ": " + id);
            }
        }
    }

    public static void checkNamespace(Path workspace, Path root, String namespace) {
        for (Project project : list(workspace)) {
            if (!project.root().equals(root.toAbsolutePath().normalize())
                    && ProjectSettings.load(project.root()).namespace().equals(namespace)) {
                throw new IllegalArgumentException("Namespace belongs to story " + project.title());
            }
        }
    }

    public static boolean enabled(Path root) {
        return list(ConfigStorySource.root()).stream().filter(project -> project.root().equals(root.toAbsolutePath().normalize()))
                .findFirst().map(Project::enabled).orElse(false);
    }
}
