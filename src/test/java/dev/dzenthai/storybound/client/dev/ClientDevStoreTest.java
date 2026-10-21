package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientDevStoreTest {

    @BeforeEach
    void setUp() {
        ClientDevStore.clearPendingTranslations("en_us");
        ClientDevStore.setActiveLanguage("en_us");
    }

    @Test
    void writeStoryTextGeneratesUniqueKeys() {
        JsonObject obj1 = new JsonObject();
        JsonObject obj2 = new JsonObject();

        ClientDevStore.writeStoryText(obj1, "text", "First Text");
        ClientDevStore.writeStoryText(obj2, "text", "Second Text");

        String key1 = ClientDevStore.getKey(obj1, "text");
        String key2 = ClientDevStore.getKey(obj2, "text");

        assertNotEquals(key1, key2);
        assertEquals("First Text", ClientDevStore.readStoryText(obj1, "text", ""));
        assertEquals("Second Text", ClientDevStore.readStoryText(obj2, "text", ""));
    }

    @Test
    void editingOneObjectDoesNotMutateAnother() {
        JsonObject lineNode = new JsonObject();
        JsonObject choiceOption = new JsonObject();

        ClientDevStore.writeStoryText(lineNode, "text", "Line text", "storybound.dialogue.test.node.line_1.text");
        ClientDevStore.writeStoryText(choiceOption, "text", "Choice text", "storybound.dialogue.test.node.choice_1.opt.0.text");

        ClientDevStore.writeStoryText(choiceOption, "text", "Updated choice text", "storybound.dialogue.test.node.choice_1.opt.0.text");

        assertEquals("Line text", ClientDevStore.readStoryText(lineNode, "text", ""));
        assertEquals("Updated choice text", ClientDevStore.readStoryText(choiceOption, "text", ""));
    }

    @Test
    void buggySharedKeyIsReplaced() {
        String buggyKey = "storybound.custom.text." + Math.abs("text".hashCode());
        assertTrue(ClientDevStore.isBuggySharedKey(buggyKey));

        JsonObject obj = new JsonObject();
        obj.addProperty("text", buggyKey);

        ClientDevStore.writeStoryText(obj, "text", "Clean Text");
        String newKey = ClientDevStore.getKey(obj, "text");

        assertNotEquals(buggyKey, newKey);
        assertEquals("Clean Text", ClientDevStore.readStoryText(obj, "text", ""));
    }
}

