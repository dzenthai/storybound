package dev.dzenthai.storybound.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The floating camera of dialogue shots (November 16, 2026), frame by frame at 60 frames a second. */
class FloatingCameraTest {
    private static final float FRAME = 1f / 60f;

    /** One mouse movement in the first frame, then still frames. */
    private static void frames(FloatingCamera camera, int count, double dx, double dy, boolean free) {
        for (int i = 0; i < count; i++) {
            camera.update(i == 0 ? dx : 0, i == 0 ? dy : 0, FRAME, free);
        }
    }

    @Test
    void theMouseTurnsTheFrameLeftAndRightAsItGoesUpAndDownInverted() {
        FloatingCamera camera = new FloatingCamera();
        frames(camera, 40, 100, -60, true);
        assertTrue(camera.yaw() > 0.3f, "right: the frame turns right, " + camera.yaw());
        assertTrue(camera.pitch() > 0.2f, "up: the frame turns down (a larger pitch), " + camera.pitch());
    }

    @Test
    void itStartsWithoutAJerkAndNeverOvershoots() {
        FloatingCamera camera = new FloatingCamera();
        float aim = FloatingCamera.aim(100 * FloatingCamera.DEGREES_PER_PIXEL, FloatingCamera.MAX_YAW);
        camera.update(100, 0, FRAME, true);
        assertTrue(camera.yaw() < aim * 0.02f, "the first frame barely moves: " + camera.yaw() + " of " + aim);
        float previous = camera.yaw();
        float largest = 0f;
        for (int i = 0; i < 30; i++) {
            camera.update(0, 0, FRAME, true);
            assertTrue(camera.yaw() >= previous, "it only goes on towards the aim");
            previous = camera.yaw();
            largest = Math.max(largest, camera.yaw());
        }
        assertTrue(largest <= aim + 1e-4f, "no overshoot: " + largest + " of " + aim);
    }

    @Test
    void itSlowsDownTowardsTheLimitsAndNeverReachesThem() {
        FloatingCamera camera = new FloatingCamera();
        for (int i = 0; i < 120; i++) {
            camera.update(500, 500, FRAME, true);
        }
        assertTrue(camera.yaw() < FloatingCamera.MAX_YAW && camera.yaw() > FloatingCamera.MAX_YAW * 0.85f, "yaw " + camera.yaw());
        assertTrue(camera.pitch() > -FloatingCamera.MAX_PITCH && camera.pitch() < -FloatingCamera.MAX_PITCH * 0.85f, "pitch " + camera.pitch());
        // Pushing on against the limit stores nothing: the way back answers at once.
        float atLimit = camera.yaw();
        for (int i = 0; i < 60; i++) {
            camera.update(i == 0 ? -150 : 0, 0, FRAME, true);
        }
        assertTrue(camera.yaw() < atLimit - 0.5f, "back from the limit at once: " + camera.yaw());
    }

    @Test
    void leftAloneItDriftsBackSlowly() {
        FloatingCamera camera = new FloatingCamera();
        frames(camera, 36, 150, 0, true);
        float turned = camera.yaw();
        frames(camera, 30, 0, 0, true);
        assertTrue(camera.yaw() > turned * 0.9f, "a moment later still about where the mouse left it: " + camera.yaw());
        frames(camera, 120, 0, 0, true);
        assertTrue(camera.yaw() < turned * 0.8f && camera.yaw() > turned * 0.2f, "two seconds on, part of the way back: " + camera.yaw());
        frames(camera, 1200, 0, 0, true);
        assertEquals(0f, camera.yaw(), 0.02f, "back in the end");
    }

    @Test
    void whileTheAnswersAreShownItGlidesBackAndTheMouseIsIgnored() {
        FloatingCamera camera = new FloatingCamera();
        frames(camera, 60, 150, 80, true);
        float turned = camera.yaw();
        camera.update(0, 0, FRAME, false);
        assertTrue(camera.yaw() > turned * 0.95f, "no jump: " + camera.yaw());
        frames(camera, 90, 300, 300, false);
        assertTrue(Math.abs(camera.yaw()) < turned * 0.08f, "nearly back within a second and a half, whatever the mouse does: " + camera.yaw());
        frames(camera, 60, 300, 300, false);
        assertEquals(0f, camera.yaw(), 0.02f, "back, the spring settling it softly");
        assertEquals(0f, camera.pitch(), 0.02f);
    }

    @Test
    void aHitchDoesNotMakeItJump() {
        FloatingCamera camera = new FloatingCamera();
        frames(camera, 60, 150, 0, true);
        float before = camera.yaw();
        camera.update(0, 0, 5f, false);
        assertTrue(camera.yaw() > before * 0.7f, "a five-second frame counts as a short one: " + camera.yaw());
    }

    @Test
    void resetCentresAtOnce() {
        FloatingCamera camera = new FloatingCamera();
        frames(camera, 20, 150, 150, true);
        camera.reset();
        assertEquals(0f, camera.yaw());
        assertEquals(0f, camera.pitch());
    }
}
