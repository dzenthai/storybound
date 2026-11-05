package dev.dzenthai.storybound.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.core.state.FlagDeclaration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStorySourceTest {
    private static final DefinitionType<FlagDeclaration> FLAGS = DefinitionType.synced(
            ResourceLocation.fromNamespaceAndPath("test", "flag"), "storybound/flags", FlagDeclaration.CODEC, FlagDeclaration::sync);

    @TempDir
    Path root;

    @Test
    void theFolderIsNamedLikeTheDatapackDirectory() {
        assertEquals("flags", ConfigStorySource.folder(FLAGS));
    }

    @Test
    void theFirstFolderIsTheNamespace() {
        assertEquals(Optional.of(ResourceLocation.fromNamespaceAndPath("storybound", "wolf_hunt")), ConfigStorySource.idOf("wolf_hunt"));
        assertEquals(Optional.of(ResourceLocation.fromNamespaceAndPath("mypack", "wolf_hunt")), ConfigStorySource.idOf("mypack/wolf_hunt"));
        assertEquals(Optional.of(ResourceLocation.fromNamespaceAndPath("mypack", "act1/wolf_hunt")), ConfigStorySource.idOf("mypack/act1/wolf_hunt"));
        assertEquals(Optional.empty(), ConfigStorySource.idOf("Wolf Hunt"), "capitals and spaces are not an id");
    }

    @Test
    void filesReplaceDatapackEntriesAndBrokenOnesAreReported() throws IOException {
        write("flags/gold.json", "{ \"type\": \"int\" }");
        write("flags/mypack/met_ciri.json", "{ \"type\": \"bool\" }");
        write("flags/mypack/broken.json", "{ not json");
        write("flags/Bad Name.json", "{ \"type\": \"bool\" }");
        write("flags/notes.txt", "ignored");
        Map<ResourceLocation, JsonElement> files = new HashMap<>();
        ResourceLocation fromDatapack = ResourceLocation.fromNamespaceAndPath("mypack", "met_ciri");
        files.put(fromDatapack, JsonParser.parseString("{ \"type\": \"str\" }"));
        List<ValidationIssue> issues = new ArrayList<>();

        ConfigStorySource.scan(root, FLAGS, files, issues);

        assertEquals(JsonParser.parseString("{ \"type\": \"bool\" }"), files.get(fromDatapack), "the story folder wins over the datapack");
        assertTrue(files.containsKey(ResourceLocation.fromNamespaceAndPath("storybound", "gold")), "a file right in the folder");
        assertEquals(2, files.size(), "the broken file, the bad name and the text file add nothing");
        assertEquals(2, issues.size(), "the broken file and the bad name: " + issues);
        assertTrue(issues.stream().allMatch(ValidationIssue::isError));
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("config/storybound/flags/mypack/broken.json")), "issues name the file");
    }

    /** Whatever the editor saves loads back under the same id, including default-namespace ids with folders. */
    @Test
    void savedFilesLoadBackUnderTheSameId() {
        for (String text : new String[] {"storybound:wolf_hunt", "storybound:act1/wolf_hunt", "mypack:wolf_hunt", "mypack:act1/wolf_hunt"}) {
            ResourceLocation id = ResourceLocation.parse(text);
            Path file = ConfigStorySource.fileFor(root, FLAGS, id);
            String relative = root.resolve("flags").relativize(file).toString().replace('\\', '/');
            assertEquals(Optional.of(id), ConfigStorySource.idOf(relative.substring(0, relative.length() - ".json".length())), text);
        }
    }

    @Test
    void nothingIsWrittenOutsideTheStoryFolder() {
        for (String text : new String[] {"storybound:../../mods/evil", "mypack:a/../../../x", "mypack:a//b", "mypack:./a"}) {
            ResourceLocation id = ResourceLocation.parse(text);
            assertThrows(IllegalArgumentException.class, () -> ConfigStorySource.fileFor(root, FLAGS, id), text);
            assertThrows(IllegalArgumentException.class, () -> ConfigStorySource.sbtFileFor(root, "en_us", id), text);
        }
        assertThrows(IllegalArgumentException.class, () -> ConfigStorySource.sbtFileFor(root, "../../x", ResourceLocation.parse("mypack:a")));
        assertTrue(ConfigStorySource.isLanguageCode("ru_ru") && ConfigStorySource.isLanguageCode("zh_cn") && ConfigStorySource.isLanguageCode("lol_us"));
        assertFalse(ConfigStorySource.isLanguageCode("en") || ConfigStorySource.isLanguageCode("../en_us") || ConfigStorySource.isLanguageCode("EN_US"));
    }

    @Test
    void aMissingFolderReadsAsEmpty() {
        Map<ResourceLocation, JsonElement> files = new HashMap<>();
        List<ValidationIssue> issues = new ArrayList<>();
        ConfigStorySource.scan(root.resolve("nowhere"), FLAGS, files, issues);
        assertTrue(files.isEmpty());
        assertTrue(issues.isEmpty());
    }

    @Test
    void theScenariosAreTheirNamespacesFiles() {
        assertTrue(StoryFolder.isScenario("dialogues/scenario/campfire/story.json"));
        assertTrue(StoryFolder.isScenario("texts/ru_ru/scenario/campfire/note.sbt"));
        assertFalse(StoryFolder.isScenario("documents/book.json"), "a starter file of its own");
        assertFalse(StoryFolder.isScenario("texts/en_us/book.sbt"));
        assertFalse(StoryFolder.isScenario("quests/mypack/scenario/x.json"), "scenario is a namespace, not any folder");
        assertFalse(StoryFolder.isScenario("quests/demo/night_watch.json"), "an old demo copy is the author's now");
        assertFalse(StoryFolder.isScenario("lang/en_us.json"));
        assertFalse(StoryFolder.isScenario("README.txt"));
    }

    @Test
    void clearingTheScenariosLeavesTheAuthorsFiles() throws IOException {
        write("dialogues/scenario/campfire/story.json", "{}");
        write("events/scenario/campfire.json", "{}");
        write("texts/en_us/scenario/campfire/note.sbt", "text");
        write("dialogues/mypack/elder.json", "{}");
        write("quests/demo/night_watch.json", "{}");
        write("lang/en_us.json", "{ \"scenario.campfire.hint\": \"Talk to Brann\", \"mypack.elder\": \"Elder\" }");
        assertEquals(3, StoryFolder.clearScenarios(root), "the scenario's three files");
        assertFalse(Files.exists(root.resolve("dialogues/scenario")), "and their emptied folders");
        assertFalse(Files.exists(root.resolve("texts/en_us/scenario")));
        assertTrue(Files.exists(root.resolve("dialogues/mypack/elder.json")), "the author's own file stays");
        assertTrue(Files.exists(root.resolve("quests/demo/night_watch.json")), "so does an old demo copy");
        String lang = Files.readString(root.resolve("lang/en_us.json"));
        assertFalse(lang.contains("scenario.campfire.hint"), "the scenario's translations go");
        assertTrue(lang.contains("mypack.elder"), "the author's stay");
        assertEquals(0, StoryFolder.clearScenarios(root), "nothing left to clear");
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
