package dev.dzenthai.storybound.core.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import dev.dzenthai.storybound.core.state.FlagDeclaration;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoryProjectsTest {
    private static final DefinitionType<FlagDeclaration> FLAGS = DefinitionType.serverOnly(
            ResourceLocation.fromNamespaceAndPath("project_test", "flag"), "storybound/flags", FlagDeclaration.CODEC);

    @TempDir
    Path workspace;

    /** One folder, one story: the old layout moves on its own, nothing overwritten. */
    @Test
    void anOldLayoutMovesToAFolderPerStory() throws Exception {
        Files.writeString(workspace.resolve("project.json"), "{\"namespace\":\"legacy\",\"track_given_quests\":true}");
        write(workspace, "flags/legacy/started.json", "{\"type\":\"bool\"}");
        write(workspace, "README.txt", "readme");
        write(workspace, "stories/second/project.json", "{\"title\":\"Second\",\"enabled\":false}");
        write(workspace, "stories/second/flags/second/count.json", "{\"type\":\"int\"}");
        assertTrue(StoryProjects.oldLayout(workspace));
        assertFalse(StoryProjects.migrate(workspace).isEmpty());
        assertFalse(StoryProjects.oldLayout(workspace));
        assertTrue(Files.exists(workspace.resolve("legacy/flags/legacy/started.json")));
        assertTrue(Files.exists(workspace.resolve("second/flags/second/count.json")));
        assertTrue(Files.exists(workspace.resolve("README.txt")), "the README explains the whole folder");
        assertFalse(Files.exists(workspace.resolve("stories")));
        assertEquals(java.util.List.of("legacy", "second"), StoryProjects.list(workspace).stream().map(StoryProjects.Project::id).toList());
        assertTrue(StoryProjects.get(workspace, "legacy").enabled());
        assertFalse(StoryProjects.get(workspace, "second").enabled());
        assertTrue(ProjectSettings.flag(workspace.resolve("legacy"), "track_given_quests"));
        assertEquals("legacy", StoryProjects.primary(workspace).orElseThrow().id());
        assertTrue(StoryProjects.migrate(workspace).isEmpty(), "a second start moves nothing");
    }

    @Test
    void aMigrationOverwritesNothing() throws Exception {
        // The story in the folder has the namespace of a story in stories/; a story of stories/ has the name of one
        // already beside them.
        Files.writeString(workspace.resolve("project.json"), "{\"namespace\":\"second\"}");
        write(workspace, "flags/second/started.json", "{\"type\":\"bool\"}");
        write(workspace, "third/project.json", "{\"title\":\"Third, already here\"}");
        write(workspace, "stories/second/project.json", "{}");
        write(workspace, "stories/third/project.json", "{\"title\":\"Third, in stories\"}");
        var log = StoryProjects.migrate(workspace);
        assertTrue(Files.exists(workspace.resolve("second_2/flags/second/started.json")), log.toString());
        assertTrue(Files.exists(workspace.resolve("second/project.json")));
        assertEquals("Third, already here", StoryProjects.get(workspace, "third").title());
        assertTrue(Files.exists(workspace.resolve("stories/third/project.json")), "left where it was");
        assertTrue(log.stream().anyMatch(line -> line.startsWith("left stories/third")), log.toString());
        assertTrue(StoryProjects.oldLayout(workspace), "still to sort out by hand");
    }

    @Test
    void creationMakesAFolderBesideTheOthers() throws Exception {
        story("legacy");
        var created = StoryProjects.create(workspace, "second", "Вторая история", "ru_ru");
        assertEquals(workspace.resolve("second").toAbsolutePath(), created.root());
        assertFalse(created.enabled());
        assertEquals("ru_ru", ProjectSettings.load(created.root()).sourceLanguage());
        StoryProjects.update(workspace, "second", "New title", false);
        assertEquals("second", ProjectSettings.load(created.root()).namespace());
        assertEquals("New title", StoryProjects.get(workspace, "second").title());
        assertEquals("legacy", StoryProjects.primary(workspace).orElseThrow().id(), "the enabled one");
        assertFalse(StoryProjects.oldLayout(workspace));
    }

    @Test
    void rejectsEscapesReservedNamesAndNamespaceCollisions() throws Exception {
        for (String id : new String[]{"../escape", "a/b", "a\\b", "C:\\escape", "", "UPPER"}) {
            assertThrows(IllegalArgumentException.class, () -> StoryProjects.resolve(workspace, id));
        }
        assertThrows(IllegalArgumentException.class, () -> StoryProjects.create(workspace, StoryProjects.SCENARIOS, "x", "en_us"));
        assertThrows(IllegalArgumentException.class, () -> StoryProjects.create(workspace, "minecraft", "x", "en_us"));
        assertThrows(IllegalArgumentException.class, () -> StoryProjects.create(workspace, "empty", " ", "en_us"));
        StoryProjects.create(workspace, "second", "Second", "en_us");
        assertThrows(IllegalArgumentException.class, () -> StoryProjects.checkNamespace(workspace, workspace, "second"));
        assertThrows(IllegalArgumentException.class, () -> StoryProjects.create(workspace, "second", "Again", "en_us"));
    }

    @Test
    void onlyEnabledStoriesLoadAndDuplicatesKeepTheFirstDefinition() throws Exception {
        Path legacy = story("legacy");
        var second = StoryProjects.create(workspace, "second", "Second", "en_us");
        write(legacy, "flags/legacy/started.json", "{\"type\":\"bool\"}");
        write(second.root(), "flags/second/started.json", "{\"type\":\"int\"}");
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        var issues = new ArrayList<ValidationIssue>();
        StoryProjects.scan(workspace, FLAGS, files, issues);
        assertEquals(1, files.size());
        StoryProjects.update(workspace, "second", "Second", true);
        files.clear();
        StoryProjects.scan(workspace, FLAGS, files, issues);
        assertEquals(2, files.size());
        assertEquals(second.root(), StoryProjects.owner(FLAGS, ResourceLocation.parse("second:started")));
        write(second.root(), "flags/legacy/started.json", "{\"type\":\"int\"}");
        files.clear();
        StoryProjects.scan(workspace, FLAGS, files, issues);
        assertEquals("bool", files.get(ResourceLocation.parse("legacy:started")).getAsJsonObject().get("type").getAsString());
        assertTrue(issues.stream().anyMatch(ValidationIssue::isError));
    }

    @Test
    void textsKeepEachStorySourceLanguageThroughTheNetwork() throws Exception {
        Path legacy = story("legacy");
        var second = StoryProjects.create(workspace, "second", "Second", "ru_ru");
        write(legacy, "lang/en_us.json", "{\"legacy.title\":\"English\"}");
        write(second.root(), "lang/ru_ru.json", "{\"second.title\":\"Русский\"}");
        write(second.root(), "texts/ru_ru/second/letter.sbt", "Письмо");
        var issues = new ArrayList<ValidationIssue>();
        assertFalse(ConfigTexts.loadProjects(workspace, issues).sourceTranslations().containsKey("second.title"));
        StoryProjects.update(workspace, "second", "Second", true);
        var texts = ConfigTexts.loadProjects(workspace, issues);
        assertEquals("English", texts.sourceTranslations().get("legacy.title"));
        assertEquals("Русский", texts.sourceTranslations().get("second.title"));
        assertEquals("Письмо", texts.sourceDocuments().get(ResourceLocation.parse("second:letter")));
        var buffer = Unpooled.buffer();
        try {
            ConfigTexts.Texts.STREAM_CODEC.encode(buffer, texts);
            assertEquals(texts, ConfigTexts.Texts.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        assertTrue(issues.isEmpty(), issues.toString());
    }

    /** An enabled story of the namespace {@code id} in the folder {@code id}. */
    private Path story(String id) throws Exception {
        write(workspace, id + "/project.json", "{\"namespace\":\"" + id + "\"}");
        return workspace.resolve(id).toAbsolutePath();
    }

    private static void write(Path root, String relative, String value) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }

    /** Deleting a story moves its folder aside, nothing erased; the last one stays. */
    @Test
    void deletingMovesTheFolderAsideAndKeepsTheLastStory() throws Exception {
        story("legacy");
        var second = StoryProjects.create(workspace, "second", "Second", "en_us");
        write(second.root(), "flags/second/started.json", "{\"type\":\"bool\"}");
        Path moved = StoryProjects.delete(workspace, "second");
        assertFalse(Files.exists(second.root()));
        assertTrue(Files.exists(moved.resolve("flags/second/started.json")), "nothing erased");
        assertEquals(workspace.resolve(StoryProjects.DELETED).toAbsolutePath().normalize(), moved.getParent());
        assertEquals(java.util.List.of("legacy"), StoryProjects.list(workspace).stream().map(StoryProjects.Project::id).toList(),
                "the deleted folder is not a story");
        assertThrows(IllegalArgumentException.class, () -> StoryProjects.delete(workspace, "legacy"), "the last story stays");
        // The same name again, deleted again: a folder of its own.
        StoryProjects.create(workspace, "second", "Second again", "en_us");
        assertNotEquals(moved, StoryProjects.delete(workspace, "second"));
    }

    /** A new story's id from its title. */
    @Test
    void aNewStorysIdComesFromItsTitle() {
        assertEquals("moya_istoriya", StoryProjects.suggestId("Моя история", java.util.Set.of()));
        assertEquals("wolf_hunt_2", StoryProjects.suggestId("Wolf Hunt 2!", java.util.Set.of()));
        assertEquals("story_1", StoryProjects.suggestId("故事", java.util.Set.of()));
        assertEquals("story_2", StoryProjects.suggestId("???", java.util.Set.of("story_1")));
        assertEquals("wolves_2", StoryProjects.suggestId("Wolves", java.util.Set.of("wolves")));
        assertEquals("storybound_2", StoryProjects.suggestId("Storybound", java.util.Set.of()), "reserved");
        assertTrue(StoryProjects.suggestId("a".repeat(100), java.util.Set.of()).length() <= 40);
        for (String title : new String[]{"Моя история", "Wolf Hunt 2!", "故事", "  -- ", "Ёлка и щука"}) {
            assertTrue(StoryProjects.validId(StoryProjects.suggestId(title, java.util.Set.of())), title);
        }
        assertFalse(StoryProjects.validId("Bad Id"));
        assertFalse(StoryProjects.validId("default"));
        assertFalse(StoryProjects.validId("моя"));
    }
}
