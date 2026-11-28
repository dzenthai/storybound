package dev.dzenthai.storybound.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A line and a caption fade in and out; a short one still reaches full. */
class ClientSceneTest {
    @Test
    void aTextFadesOverItsFirstAndLastTicks() {
        // 100 ticks: 8 up, 8 down.
        assertEquals(0f, ClientScene.fadeAlpha(0, 0, 100), 1e-6f);
        assertEquals(0.5f, ClientScene.fadeAlpha(4.0, 0, 100), 1e-6f);
        assertEquals(1f, ClientScene.fadeAlpha(50, 0, 100), 1e-6f);
        assertEquals(0.5f, ClientScene.fadeAlpha(96.0, 0, 100), 1e-6f);
        assertEquals(0f, ClientScene.fadeAlpha(100, 0, 100), 1e-6f);
        assertEquals(0f, ClientScene.fadeAlpha(120, 0, 100), 1e-6f);
        // Not before it starts.
        assertEquals(0f, ClientScene.fadeAlpha(40, 50, 150), 1e-6f);
    }

    @Test
    void aShortTextFadesOverAQuarterOfItsTimeEachWay() {
        // 24 ticks: 6 up, 6 down (leaving half at full opacity).
        assertEquals(0.5f, ClientScene.fadeAlpha(3, 0, 24), 1e-6f);
        assertEquals(1f, ClientScene.fadeAlpha(12, 0, 24), 1e-6f);
        assertEquals(0.5f, ClientScene.fadeAlpha(21, 0, 24), 1e-6f);
    }

    @Test
    void aTextWithNoTimeNeverDividesByZero() {
        assertEquals(0f, ClientScene.fadeAlpha(5, 5, 5), 1e-6f);
        assertEquals(0f, ClientScene.fadeAlpha(4, 5, 5), 1e-6f);
    }

    @Test
    void skipHoldReachesFullAfterConfiguredDuration() {
        assertEquals(0f, SceneScreen.skipHoldProgress(-100L), 1e-6f);
        assertEquals(0f, SceneScreen.skipHoldProgress(0L), 1e-6f);
        assertEquals(0.5f, SceneScreen.skipHoldProgress(SceneScreen.SKIP_HOLD_MS / 2), 1e-6f);
        assertEquals(1f, SceneScreen.skipHoldProgress(SceneScreen.SKIP_HOLD_MS), 1e-6f);
        assertEquals(1f, SceneScreen.skipHoldProgress(SceneScreen.SKIP_HOLD_MS + 1000L), 1e-6f);
    }

    @Test
    void letterboxBarsEaseInAndOutSmoothly() {
        assertEquals(0f, ClientScene.letterboxProgress(10, 100, false, false), 1e-6f, "disabled stays at 0");
        assertEquals(0f, ClientScene.letterboxProgress(0, 100, true, false), 1e-6f, "starts closed on fresh scene");
        float midIn = ClientScene.letterboxProgress(3, 100, true, false);
        assertEquals(0.875f, midIn, 1e-4f, "cubic ease-out at halfway through slide-in");
        assertEquals(1f, ClientScene.letterboxProgress(50, 100, true, false), 1e-6f, "full height in the middle");
        float midOut = ClientScene.letterboxProgress(97, 100, true, false);
        assertEquals(0.875f, midOut, 1e-4f, "smooth cubic slide-out near end");
        assertEquals(0f, ClientScene.letterboxProgress(100, 100, true, false), 1e-6f, "retracted at scene end");
        assertEquals(1f, ClientScene.letterboxProgress(0, 100, true, true), 1e-6f, "chained letterbox scene stays full at tick 0");
    }
}

