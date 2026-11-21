package dev.dzenthai.storybound.core.logic.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class ScreenFadeTest {
    private static com.mojang.serialization.DataResult<ScreenFade> parse(String json) {
        return ScreenFade.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    @Test
    void defaultsAreAHalfSecondEachWayAndASecondOfBlack() {
        ScreenFade fade = parse("{}").getOrThrow();
        assertEquals(new ScreenFade(0.5f, 1f, 0.5f, ScreenFade.BLACK), fade);
    }

    @Test
    void aColourAndTimesAsWritten() {
        ScreenFade fade = parse("{ \"in\": 0, \"stay\": 30, \"out\": 2, \"color\": \"#FFFFFF\" }").getOrThrow();
        assertEquals(new ScreenFade(0f, 30f, 2f, 0xFFFFFFFF), fade);
    }

    @Test
    void aHoldLongerThanAMinuteIsRefused() {
        assertTrue(parse("{ \"stay\": 61 }").isError(), "a scene that never comes must not leave the screen black");
        assertTrue(parse("{ \"in\": -1 }").isError(), "no negative times");
    }
}
