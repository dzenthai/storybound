package dev.dzenthai.storybound.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FlagDeclarationTest {
    private static DataResult<FlagDeclaration> parse(String json) {
        return FlagDeclaration.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    @Test
    void parsesFullDeclaration() {
        FlagDeclaration flag = parse("""
                { "type": "int", "default": 5, "scope": "party", "sync": true, "description": "Gold" }
                """).getOrThrow();

        assertEquals(new StoryValue.Int(5), flag.defaultValue());
        assertEquals(StoryScope.PARTY, flag.scope());
        assertTrue(flag.sync());
        assertEquals(Optional.of("Gold"), flag.description());
    }

    /** A relationship scale's bounds: a number written past them is held to them; other types as they are. */
    @Test
    void boundsHoldANumberWithinThem() {
        FlagDeclaration scale = parse("{ \"type\": \"int\", \"min\": -50, \"max\": 50 }").getOrThrow();
        assertEquals(Optional.of(-50), scale.min());
        assertEquals(new StoryValue.Int(50), scale.bounded(new StoryValue.Int(70)));
        assertEquals(new StoryValue.Int(-50), scale.bounded(new StoryValue.Int(-999)));
        assertEquals(new StoryValue.Int(20), scale.bounded(new StoryValue.Int(20)));
        FlagDeclaration free = parse("{ \"type\": \"int\" }").getOrThrow();
        assertEquals(new StoryValue.Int(999), free.bounded(new StoryValue.Int(999)), "no bounds, no limit");
        assertEquals(new StoryValue.Bool(true), scale.bounded(new StoryValue.Bool(true)), "only numbers are bounded");
    }

    @Test
    void appliesDefaults() {
        FlagDeclaration flag = parse("{ \"type\": \"bool\" }").getOrThrow();

        assertEquals(new StoryValue.Bool(false), flag.defaultValue());
        assertEquals(StoryScope.PLAYER, flag.scope());
        assertFalse(flag.sync());
        assertEquals(Optional.empty(), flag.description());
    }

    @Test
    void rejectsMissingType() {
        assertTrue(parse("{ \"default\": true }").isError());
    }

    @Test
    void rejectsUnknownScope() {
        assertTrue(parse("{ \"type\": \"bool\", \"scope\": \"guild\" }").isError());
    }

    @Test
    void rejectsDefaultOfWrongType() {
        assertTrue(parse("{ \"type\": \"int\", \"default\": \"five\" }").isError());
    }

    @Test
    void roundTrips() {
        FlagDeclaration flag = new FlagDeclaration(new StoryValue.Str("north"), StoryScope.WORLD, true, Optional.empty());
        JsonElement json = FlagDeclaration.CODEC.encodeStart(JsonOps.INSTANCE, flag).getOrThrow();

        assertEquals(flag, FlagDeclaration.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }
}
