package dev.dzenthai.storybound.module.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ThinkTimeTest {
    @Test
    void thePauseFollowsTheOptionsLength() {
        assertEquals(ThinkTime.MIN_MILLIS, ThinkTime.resolve(ThinkTime.AUTO, 3), "short options: the least");
        assertEquals(2200, ThinkTime.resolve(ThinkTime.AUTO, 20));
        assertEquals(ThinkTime.MAX_MILLIS, ThinkTime.resolve(ThinkTime.AUTO, 400), "long ones: the most");
        assertEquals(ThinkTime.NONE, ThinkTime.encode(Optional.of(0f)), "\"think\": 0 is no pause");
        assertEquals(2500, ThinkTime.resolve(ThinkTime.encode(Optional.of(2.5f)), 100), "a fixed pause");
    }

    @Test
    void thePlayersSettingScalesItButNotBeyondWhatTheServerWaits() {
        assertEquals(1100, ThinkTime.resolve(ThinkTime.AUTO, 20, 0.5));
        assertEquals(0, ThinkTime.resolve(ThinkTime.AUTO, 20, 0), "0 answers at once");
        assertEquals(ThinkTime.MAX_MILLIS, ThinkTime.resolve(ThinkTime.AUTO, 60, 2), "never longer than the server waits");
    }
}
