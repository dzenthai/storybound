package dev.dzenthai.storybound.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The story's namespace in project.json: where the dev mode makes new nodes. */
class ProjectSettingsTest {
    @TempDir
    Path root;

    @Test
    void theNamespaceIsStoryboundUntilTheAuthorSetsOne() throws IOException {
        assertEquals(ProjectSettings.DEFAULT_NAMESPACE, ProjectSettings.load(root).namespace(), "without a file");
        Files.writeString(root.resolve(ProjectSettings.FILE_NAME), "{ \"source_language\": \"en_us\", \"track_given_quests\": true }");
        assertEquals("storybound", ProjectSettings.load(root).namespace(), "a file without one");

        ProjectSettings.save(root, new ProjectSettings("en_us", List.of("en_us", "ru_ru"), "my_story"));
        assertEquals("my_story", ProjectSettings.load(root).namespace());
        assertTrue(Files.readString(root.resolve(ProjectSettings.FILE_NAME)).contains("\"track_given_quests\": true"), "other settings stay");
    }

    /** The settings window's preferences: written beside the languages, taken out when left out. */
    @Test
    void theSettingsWindowWritesOnlyItsOwnPreferences() throws IOException {
        Files.writeString(root.resolve(ProjectSettings.FILE_NAME),
                "{ \"source_language\": \"ru_ru\", \"languages\": [\"ru_ru\"], \"namespace\": \"my_story\", \"track_given_quests\": true }");
        com.google.gson.JsonObject options = new com.google.gson.JsonObject();
        com.google.gson.JsonObject difficulty = new com.google.gson.JsonObject();
        difficulty.addProperty("style", "stars");
        options.add("difficulty", difficulty);
        ProjectSettings.saveOptions(root, options);

        assertTrue(ProjectSettings.options(root).has("difficulty"), "written");
        assertTrue(!ProjectSettings.flag(root, "track_given_quests"), "left out: taken out of the file");
        ProjectSettings settings = ProjectSettings.load(root);
        assertEquals("ru_ru", settings.sourceLanguage(), "the languages stay");
        assertEquals("my_story", settings.namespace(), "the namespace stays");
        assertEquals(options, ProjectSettings.options(root), "read back as written");
    }

    @Test
    void settingsWithoutAFileKeepTheLanguagesThatLoad() throws IOException {
        Files.createDirectories(root.resolve("lang"));
        Files.writeString(root.resolve("lang/de_de.json"), "{}");
        com.google.gson.JsonObject options = new com.google.gson.JsonObject();
        options.addProperty("track_given_quests", true);
        ProjectSettings.saveOptions(root, options);

        assertEquals(List.of("de_de"), ProjectSettings.load(root).languages(), "the language found before is written out");
        assertTrue(ProjectSettings.flag(root, "track_given_quests"));
    }

    @Test
    void aNamespaceAStoryMayNotUseIsReportedAndReplaced() throws IOException {
        Files.writeString(root.resolve(ProjectSettings.FILE_NAME), "{ \"source_language\": \"en_us\", \"namespace\": \"scenario\" }");
        List<String> problems = new ArrayList<>();
        assertEquals("storybound", ProjectSettings.load(root, problems::add).namespace(), "the debug scenarios' own namespace is not a story's");
        assertTrue(problems.size() == 1 && problems.getFirst().contains("namespace \"scenario\""), problems.toString());
    }
}
