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

/** The story's namespace in project.json (October 29, 2026): where the dev mode makes new nodes. */
class ProjectSettingsTest {
    @TempDir
    Path root;

    @Test
    void theNamespaceIsStoryboundUntilTheAuthorSetsOne() throws IOException {
        assertEquals(ProjectSettings.DEFAULT_NAMESPACE, ProjectSettings.load(root).namespace(), "without a file");
        Files.writeString(root.resolve(ProjectSettings.FILE_NAME), "{ \"source_language\": \"en_us\", \"chapter_map\": true }");
        assertEquals("storybound", ProjectSettings.load(root).namespace(), "a file without one");

        ProjectSettings.save(root, new ProjectSettings("en_us", List.of("en_us", "ru_ru"), "my_story"));
        assertEquals("my_story", ProjectSettings.load(root).namespace());
        assertTrue(Files.readString(root.resolve(ProjectSettings.FILE_NAME)).contains("\"chapter_map\": true"), "other settings stay");
    }

    @Test
    void aNamespaceAStoryMayNotUseIsReportedAndReplaced() throws IOException {
        Files.writeString(root.resolve(ProjectSettings.FILE_NAME), "{ \"source_language\": \"en_us\", \"namespace\": \"demo\" }");
        List<String> problems = new ArrayList<>();
        assertEquals("storybound", ProjectSettings.load(root, problems::add).namespace(), "the demo's own namespace is not a story's");
        assertTrue(problems.size() == 1 && problems.getFirst().contains("namespace \"demo\""), problems.toString());
    }
}
