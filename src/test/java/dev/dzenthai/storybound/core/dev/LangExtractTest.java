package dev.dzenthai.storybound.core.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Texts written straight into the JSON moving to the lang file under canonical keys. */
class LangExtractTest {
    private static final ResourceLocation QUEST = ResourceLocation.fromNamespaceAndPath("storybound", "quest");
    private static final ResourceLocation DIALOGUE = ResourceLocation.fromNamespaceAndPath("storybound", "dialogue");

    @Test
    void literalsBecomeKeysByFieldAndListElementId() {
        JsonElement json = JsonParser.parseString("""
                { "title": { "literal": "Wolf Hunt" },
                  "stages": [ { "id": "hunt", "objectives": [ { "id": "wolves", "text": { "literal": "Kill wolves" } }, { "text": { "literal": "Come back" } } ] } ],
                  "description": "mypack.quest.hunt.description" }
                """);
        Map<String, String> added = new TreeMap<>();
        LangExtract.Counts counts = LangExtract.extract(QUEST, ResourceLocation.fromNamespaceAndPath("mypack", "act1/hunt"), json, Map.of(), added);
        assertEquals(3, counts.moved());
        assertEquals(Map.of("mypack.quest.act1.hunt.title", "Wolf Hunt",
                "mypack.quest.act1.hunt.stages.hunt.objectives.wolves.text", "Kill wolves",
                "mypack.quest.act1.hunt.stages.hunt.objectives.1.text", "Come back"), added);
        assertEquals("mypack.quest.act1.hunt.title", json.getAsJsonObject().get("title").getAsString());
        assertEquals("mypack.quest.hunt.description", json.getAsJsonObject().get("description").getAsString(), "a key stays as it is");
    }

    @Test
    void dialogueNodesUseTheEditorsKeys() {
        JsonElement json = JsonParser.parseString("""
                { "start": "hello", "nodes": { "hello": { "type": "line", "text": { "literal": "Hi there" } } } }
                """);
        Map<String, String> added = new TreeMap<>();
        LangExtract.extract(DIALOGUE, ResourceLocation.fromNamespaceAndPath("mypack", "elder"), json, Map.of(), added);
        assertEquals(Map.of("mypack.dialogue.elder.node.hello.text", "Hi there"), added);
    }

    @Test
    void aKeyHoldingAnotherTextGetsASuffixTheSameTextIsReused() {
        Map<String, String> lang = Map.of("mypack.quest.hunt.title", "Old title", "mypack.quest.hunt.subtitle", "Same");
        JsonElement json = JsonParser.parseString("""
                { "title": { "literal": "New title" }, "subtitle": { "literal": "Same" } }
                """);
        Map<String, String> added = new TreeMap<>();
        LangExtract.extract(QUEST, ResourceLocation.fromNamespaceAndPath("mypack", "hunt"), json, lang, added);
        assertEquals("mypack.quest.hunt.title_2", json.getAsJsonObject().get("title").getAsString());
        assertEquals("mypack.quest.hunt.subtitle", json.getAsJsonObject().get("subtitle").getAsString());
        assertEquals("New title", added.get("mypack.quest.hunt.title_2"));
    }

    @Test
    void aMissingKeyGetsItsFallbackAndKeepsIt() {
        JsonElement json = JsonParser.parseString("""
                { "title": { "key": "mypack.quest.hunt.title", "fallback": "Wolf Hunt" }, "log": { "key": "mypack.known", "fallback": "x" } }
                """);
        Map<String, String> added = new TreeMap<>();
        LangExtract.Counts counts = LangExtract.extract(QUEST, ResourceLocation.fromNamespaceAndPath("mypack", "hunt"), json, Map.of("mypack.known", "Known"), added);
        assertEquals(new LangExtract.Counts(0, 1), counts);
        assertEquals(Map.of("mypack.quest.hunt.title", "Wolf Hunt"), added);
        assertEquals("Wolf Hunt", json.getAsJsonObject().getAsJsonObject("title").get("fallback").getAsString());
    }
}
