package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.client.dev.ScenePreview.TimedCue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A newer timeline mark replaces the previous cue of the same channel instead of layering over it. */
class ScenePreviewTimelineTest {
    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    @Test
    void newerMarkReplacesEarlierCueOfSameChannel() {
        List<TimedCue> cues = List.of(
                new TimedCue(0, 0, 0.0, json("{\"at\":0,\"title\":{\"text\":\"First\"}}")),
                new TimedCue(0, 1, 2.0, json("{\"at\":2,\"title\":{\"text\":\"Second\"}}")),
                new TimedCue(0, 2, 0.0, json("{\"at\":0,\"music\":\"storybound:track_a\"}")),
                new TimedCue(0, 3, 2.0, json("{\"at\":2,\"music\":\"storybound:track_b\"}")),
                new TimedCue(0, 4, 0.5, json("{\"at\":0.5,\"line\":{\"text\":\"Line 1\"}}")),
                new TimedCue(0, 5, 1.5, json("{\"at\":1.5,\"line\":{\"text\":\"Line 2\"}}"))
        );

        Map<String, TimedCue> at1 = ScenePreview.latestCuesByChannel(cues, 1.0, 0, -1);
        assertEquals(0, at1.get("title").cueIndex());
        assertEquals(2, at1.get("music").cueIndex());
        assertEquals(4, at1.get("line").cueIndex());

        Map<String, TimedCue> at25 = ScenePreview.latestCuesByChannel(cues, 2.5, 0, -1);
        assertEquals(1, at25.get("title").cueIndex(), "second title replaces the first");
        assertEquals(3, at25.get("music").cueIndex(), "second music track replaces the first");
        assertEquals(5, at25.get("line").cueIndex(), "second subtitle line replaces the first");
        assertNull(at25.get("caption"));
    }

    @Test
    void laterShotMarkReplacesEarlierShotCueInSceneMode() {
        List<TimedCue> cues = List.of(
                new TimedCue(0, 0, 1.0, json("{\"at\":1,\"caption\":{\"text\":\"Shot 1\"}}")),
                new TimedCue(1, 0, 5.5, json("{\"at\":0.5,\"caption\":{\"text\":\"Shot 2\"}}")),
                new TimedCue(0, 1, 0.0, json("{\"at\":0,\"music\":\"storybound:a\"}")),
                new TimedCue(1, 1, 6.0, json("{\"at\":1,\"music\":\"storybound:b\"}"))
        );

        Map<String, TimedCue> beforeSecondShot = ScenePreview.latestCuesByChannel(cues, 5.2, 0, -1);
        assertEquals(0, beforeSecondShot.get("caption").shotIndex());
        assertEquals(0, beforeSecondShot.get("music").shotIndex());

        Map<String, TimedCue> afterSecondShotCue = ScenePreview.latestCuesByChannel(cues, 6.2, 1, -1);
        assertEquals(1, afterSecondShotCue.get("caption").shotIndex());
        assertEquals(1, afterSecondShotCue.get("music").shotIndex());
    }

    @Test
    void fadeAlphaStartsBlackWhenInIsZero() {
        // Opening fade: already solid black at t=0, stays black, then reveals smoothly.
        assertEquals(1.0, ScenePreview.fadeAlpha(0.0, 0.0, 1.0, 0.5), 1e-6);
        assertEquals(1.0, ScenePreview.fadeAlpha(0.5, 0.0, 1.0, 0.5), 1e-6);
        assertEquals(0.5, ScenePreview.fadeAlpha(1.25, 0.0, 1.0, 0.5), 1e-6);
        assertEquals(0.0, ScenePreview.fadeAlpha(1.5, 0.0, 1.0, 0.5), 1e-6);

        // Zero hold: immediately solid black at t=0, starts fading out right away.
        assertEquals(1.0, ScenePreview.fadeAlpha(0.0, 0.0, 0.0, 0.8), 1e-6);
        assertEquals(0.5, ScenePreview.fadeAlpha(0.4, 0.0, 0.0, 0.8), 1e-6);

        // Mid-shot dip: starts clear, fades to black, holds, then clears.
        assertEquals(0.0, ScenePreview.fadeAlpha(0.0, 0.5, 1.0, 0.5), 1e-6);
        assertEquals(0.5, ScenePreview.fadeAlpha(0.25, 0.5, 1.0, 0.5), 1e-6);
        assertEquals(1.0, ScenePreview.fadeAlpha(0.5, 0.5, 1.0, 0.5), 1e-6);
        assertEquals(0.5, ScenePreview.fadeAlpha(1.75, 0.5, 1.0, 0.5), 1e-6);

        // Fade to black with out=0 never produces NaN.
        assertEquals(1.0, ScenePreview.fadeAlpha(1.0, 0.5, 1.0, 0.0), 1e-6);
        assertEquals(0.0, ScenePreview.fadeAlpha(1.5, 0.5, 1.0, 0.0), 1e-6);
    }

    @Test
    void textsFadeInAndOutAndShortOnesStillReachFull() {
        // A chapter title: up over 0.75 s, down over 1.25 s of its 5.5.
        assertEquals(0.0, ScenePreview.textAlpha(0.0, 5.5, 0.75, 1.25), 1e-6);
        assertEquals(0.5, ScenePreview.textAlpha(0.375, 5.5, 0.75, 1.25), 1e-6);
        assertEquals(1.0, ScenePreview.textAlpha(3.0, 5.5, 0.75, 1.25), 1e-6);
        assertEquals(0.5, ScenePreview.textAlpha(4.875, 5.5, 0.75, 1.25), 1e-6);
        assertEquals(0.0, ScenePreview.textAlpha(5.5, 5.5, 0.75, 1.25), 1e-6);
        assertEquals(0.0, ScenePreview.textAlpha(6.0, 5.5, 0.75, 1.25), 1e-6);

        // Held at its mark (no fade in): whole at once, still fading out at its end.
        assertEquals(1.0, ScenePreview.textAlpha(0.0, 2.5, 0.0, 0.75), 1e-6);
        assertEquals(0.5, ScenePreview.textAlpha(2.125, 2.5, 0.0, 0.75), 1e-6);

        // A text shorter than its two fades shortens both: it peaks in the middle, never above 1 or below 0.
        assertEquals(1.0, ScenePreview.textAlpha(0.5, 1.0, 0.75, 0.75), 1e-6);
        assertEquals(0.5, ScenePreview.textAlpha(0.25, 1.0, 0.75, 0.75), 1e-6);
    }

    @Test
    void freshFadeAtStartDefaultsToZeroFadeIn() {
        JsonObject atStart = ScenePreview.freshCue("fade", 0.0).getAsJsonObject();
        assertEquals(0.0, atStart.get("in").getAsDouble(), 1e-6);
        assertEquals(1.0, atStart.get("stay").getAsDouble(), 1e-6);
        assertEquals(0.5, atStart.get("out").getAsDouble(), 1e-6);

        JsonObject midShot = ScenePreview.freshCue("fade", 2.0).getAsJsonObject();
        assertEquals(0.5, midShot.get("in").getAsDouble(), 1e-6);

        JsonObject shake = ScenePreview.freshCue("shake", 1.0).getAsJsonObject();
        assertEquals(0.2, shake.get("intensity").getAsDouble(), 1e-6);
        assertEquals(0.5, shake.get("duration").getAsDouble(), 1e-6);
    }

    @Test
    void shakeChannelReplacesEarlierShakeIndependentlyOfVisualChannels() {
        List<TimedCue> cues = List.of(
                new TimedCue(0, 0, 0.5, json("{\"at\":0.5,\"shake\":{\"intensity\":0.15,\"duration\":0.4}}")),
                new TimedCue(0, 1, 1.0, json("{\"at\":1.0,\"title\":{\"text\":\"Act I\"}}")),
                new TimedCue(0, 2, 2.0, json("{\"at\":2.0,\"shake\":{\"intensity\":0.4,\"duration\":0.6}}"))
        );

        Map<String, TimedCue> at12 = ScenePreview.latestCuesByChannel(cues, 1.2, 0, -1);
        assertEquals(0, at12.get("shake").cueIndex());
        assertEquals(1, at12.get("title").cueIndex());

        Map<String, TimedCue> at22 = ScenePreview.latestCuesByChannel(cues, 2.2, 0, -1);
        assertEquals(2, at22.get("shake").cueIndex(), "later shake replaces earlier shake");
        assertEquals(1, at22.get("title").cueIndex(), "title channel remains unaffected");
    }

    @Test
    void cueShakeDecaysQuadraticallyAndMatchesPeakIntensity() {
        List<TimedCue> cues = List.of(
                new TimedCue(0, 0, 1.0, json("{\"at\":1.0,\"shake\":{\"intensity\":0.8,\"duration\":1.0}}"))
        );
        assertEquals(0.0f, ScenePreview.cueShakeAt(cues, 0.99), 1e-5f);
        assertEquals(0.8f, ScenePreview.cueShakeAt(cues, 1.0), 1e-5f);
        assertEquals(0.2f, ScenePreview.cueShakeAt(cues, 1.5), 1e-5f, "halfway through quadratic decay -> 0.8 * 0.25 = 0.2");
        assertEquals(0.0f, ScenePreview.cueShakeAt(cues, 2.0), 1e-5f);
    }

    @Test
    void sceneMusicFieldRemainsVisibleAfterClearing() {
        assertTrue(FormDialogues.NODE_ORDER.get("scene").contains("music"), "scene music appears in ordered form fields");
        assertTrue(FormDialogues.nodeFieldShown("scene", "music", new JsonObject(), null),
                "clearing scene music must not hide the music field");
        assertTrue(FormDialogues.nodeFieldShown("scene", "music", null, null),
                "scene music field is visible even on a brand new scene");
    }
}

