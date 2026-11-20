package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.*;

import dev.dzenthai.storybound.core.text.ConfigTexts;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A story shows a block for a handler of the server's scripts only when the handler is its own (its namespace) or it
 * uses it.
 */
class StoryScriptsTest {
    private static final String SCRIPTS = """
            { "kubejs": true, "files": [
              { "path": "storybound/old.js", "actions": ["storybound:script_2", "storybound:used"] },
              { "path": "my_story/own.js", "actions": ["my_story:weather"], "conditions": ["my_story:is_night"] }
            ] }""";

    @BeforeEach
    @AfterEach
    void reset() {
        ClientDevStore.clearProjects();
    }

    @Test
    void aStoryShowsItsOwnHandlersAndThoseItUses() {
        ClientDevStore.setProject("my_story", "[]", ConfigTexts.Texts.EMPTY);
        ClientDevStore.setNamespace("my_story");
        // A quest of the story runs storybound:used as the action "Script".
        String quest = "{\"title\":\"q\",\"stages\":[{\"id\":\"a\",\"on_enter\":[{\"type\":\"kubejs\",\"id\":\"storybound:used\"}]}]}";
        ClientDevStore.setSnapshot(Map.of(ClientDevStore.TYPE_QUEST, Map.of(ResourceLocation.parse("my_story:q"), quest)),
                List.of(), "en_us", List.of("en_us"), 0);
        ClientDevStore.setScripts(SCRIPTS);
        var blocks = ClientDevStore.byType(ClientDevStore.TYPE_SCRIPT).keySet();
        assertTrue(blocks.contains(ResourceLocation.parse("my_story:weather")), "its own action");
        assertTrue(blocks.contains(ResourceLocation.parse("my_story:is_night")), "its own condition");
        assertTrue(blocks.contains(ResourceLocation.parse("storybound:used")), "one it uses");
        assertFalse(blocks.contains(ResourceLocation.parse("storybound:script_2")), "not another's it does not use");
    }

    @Test
    void anotherStorySeesNoneOfThem() {
        ClientDevStore.setProject("scenarios", "[]", ConfigTexts.Texts.EMPTY);
        ClientDevStore.setNamespace("scenario");
        ClientDevStore.setSnapshot(Map.of(), List.of(), "en_us", List.of("en_us"), 0);
        ClientDevStore.setScripts(SCRIPTS);
        assertTrue(ClientDevStore.byType(ClientDevStore.TYPE_SCRIPT).isEmpty());
    }
}
