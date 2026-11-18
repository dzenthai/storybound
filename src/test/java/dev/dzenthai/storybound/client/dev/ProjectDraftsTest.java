package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectDraftsTest {
    @BeforeEach
    @AfterEach
    void reset() {
        ClientDevStore.clearProjects();
    }

    @Test
    void switchingRestoresOnlyTheSelectedStoryDraftsAndTranslations() {
        var type = ClientDevStore.TYPE_QUEST;
        var id = ResourceLocation.parse("example:draft");
        JsonObject text = new JsonObject();
        select("default");
        ClientDevStore.putDraft(type, id, "{\"title\":\"First\"}");
        ClientDevStore.writeStoryText(text, "title", "First translation", "example.title");
        select("second");
        assertFalse(ClientDevStore.isDraft(type, id));
        assertFalse(ClientDevStore.byType(type).containsKey(id));
        ClientDevStore.putDraft(type, id, "{\"title\":\"Second\"}");
        ClientDevStore.writeStoryText(text, "title", "Second translation", "example.title");
        select("default");
        assertEquals("{\"title\":\"First\"}", ClientDevStore.byType(type).get(id));
        assertEquals("First translation", ClientDevStore.readStoryText(text, "title", ""));
        select("second");
        assertEquals("{\"title\":\"Second\"}", ClientDevStore.byType(type).get(id));
        assertEquals("Second translation", ClientDevStore.readStoryText(text, "title", ""));
        ClientDevStore.clearProjects();
        select("default");
        assertFalse(ClientDevStore.isDraft(type, id));
    }

    private static void select(String id) {
        ClientDevStore.setProject(id, "[]", ConfigTexts.Texts.EMPTY);
        ClientDevStore.setSnapshot(Map.of(), List.of(), "en_us", List.of("en_us"));
    }
}
