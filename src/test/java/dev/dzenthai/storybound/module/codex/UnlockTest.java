package dev.dzenthai.storybound.module.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class UnlockTest {
    private static Unlock.Source parse(String value) {
        return Unlock.Source.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(value)).getOrThrow();
    }

    private static String write(Unlock.Source source) {
        return Unlock.Source.CODEC.encodeStart(JsonOps.INSTANCE, source).getOrThrow().getAsString();
    }

    @Test
    void studyingIsWrittenWithItsSeconds() {
        assertEquals(new Unlock.Source.Observed(5), parse("observed"), "observed alone is 5 seconds");
        assertEquals(new Unlock.Source.Observed(2.5f), parse("observed:2.5"));
        assertEquals("observed:10", write(parse("observed:10")), "whole seconds without .0");
        assertEquals("observed:2.5", write(parse("observed:2.5")));
        assertEquals("observed:5", write(parse("observed")), "written out with its seconds");
        assertTrue(Unlock.Source.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("observed:0")).error().isPresent(), "at least a quarter of a second");
        assertTrue(Unlock.Source.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("observed:soon")).error().isPresent(), "a number");
        assertEquals(Unlock.Source.SEEN, parse("seen"), "the others as before");
    }
}
