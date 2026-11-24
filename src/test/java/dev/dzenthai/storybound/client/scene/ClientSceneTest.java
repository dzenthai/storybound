package dev.dzenthai.storybound.client.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A line and a caption fade in and out; a short one still reaches full. */
class ClientSceneTest {
    @Test
    void aTextFadesOverItsFirstAndLastTicks() {
        // 100 ticks: 15 up, 15 down.
        assertEquals(0f, ClientScene.fadeAlpha(0, 0, 100), 1e-6f);
        assertEquals(0.5f, ClientScene.fadeAlpha(7.5, 0, 100), 1e-6f);
        assertEquals(1f, ClientScene.fadeAlpha(50, 0, 100), 1e-6f);
        assertEquals(0.5f, ClientScene.fadeAlpha(92.5, 0, 100), 1e-6f);
        assertEquals(0f, ClientScene.fadeAlpha(100, 0, 100), 1e-6f);
        assertEquals(0f, ClientScene.fadeAlpha(120, 0, 100), 1e-6f);
        // Not before it starts.
        assertEquals(0f, ClientScene.fadeAlpha(40, 50, 150), 1e-6f);
    }

    @Test
    void aShortTextFadesOverAThirdOfItsTimeEachWay() {
        // 30 ticks: 10 up, 10 down.
        assertEquals(0.5f, ClientScene.fadeAlpha(5, 0, 30), 1e-6f);
        assertEquals(1f, ClientScene.fadeAlpha(15, 0, 30), 1e-6f);
        assertEquals(0.5f, ClientScene.fadeAlpha(25, 0, 30), 1e-6f);
    }

    @Test
    void aTextWithNoTimeNeverDividesByZero() {
        assertEquals(0f, ClientScene.fadeAlpha(5, 5, 5), 1e-6f);
        assertEquals(0f, ClientScene.fadeAlpha(4, 5, 5), 1e-6f);
    }
}

