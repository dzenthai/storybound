package dev.dzenthai.storybound.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void aMissingFolderReadsAsEmpty() {
        Map<ResourceLocation, JsonElement> files = new HashMap<>();
        List<ValidationIssue> issues = new ArrayList<>();
        ConfigStorySource.scan(root.resolve("nowhere"), FLAGS, files, issues);
        assertTrue(files.isEmpty());
        assertTrue(issues.isEmpty());
    }

    @Test
    void theExampleIsMovedIntoTheStoryFolderLayout() {
        assertEquals(Optional.of("quests/example/wolf_hunt.json"), StoryFolder.examplePath("data/example/storybound/quests/wolf_hunt.json"));
        assertEquals(Optional.of("lang/ru_ru.json"), StoryFolder.examplePath("assets/example/lang/ru_ru.json"));
        assertEquals(Optional.of("texts/en_us/example/codex/wolf/base.sbt"), StoryFolder.examplePath("assets/example/storybound/texts/en_us/codex/wolf/base.sbt"));
        assertFalse(StoryFolder.examplePath("pack.mcmeta").isPresent());
        assertFalse(StoryFolder.examplePath("assets/example/textures/item/x.png").isPresent());
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
