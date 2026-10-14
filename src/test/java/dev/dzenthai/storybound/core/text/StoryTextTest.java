package dev.dzenthai.storybound.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StoryTextTest {
    private static StoryText parse(String json) {
        return StoryText.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void plainStringIsAKey() {
        assertEquals(StoryText.key("mypack.quest.title"), parse("\"mypack.quest.title\""));
    }

    @Test
    void fullFormKeepsEverything() {
        StoryText text = parse("""
                { "key": "mypack.npc.greet", "args": ["{player}", "{var:mypack:gold}"], "fallback": "Hello", "variant_by": "mypack:gender" }
                """);

        assertEquals(new StoryText.Key("mypack.npc.greet", List.of("{player}", "{var:mypack:gold}"), Optional.of("Hello"),
                Optional.of(ResourceLocation.parse("mypack:gender"))), text);
    }

    @Test
    void literalForPrototypes() {
        assertEquals(new StoryText.Literal("Draft"), parse("{ \"literal\": \"Draft\" }"));
    }

    @Test
    void plainKeysEncodeBackToStrings() {
        JsonElement json = StoryText.CODEC.encodeStart(JsonOps.INSTANCE, StoryText.key("a.b")).getOrThrow();

        assertTrue(json.isJsonPrimitive());
        assertEquals("a.b", json.getAsString());
    }

    @Test
    void rejectsObjectsWithoutKeyOrLiteral() {
        assertTrue(StoryText.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{ \"args\": [] }")).isError());
    }
}
