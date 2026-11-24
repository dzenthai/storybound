package dev.dzenthai.storybound.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dzenthai.storybound.module.scene.SceneDefinition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** A scene's camera without the game: how a flight speeds up and slows down, and which way the camera looks. */
class SceneCameraTest {
    @Test
    void everyEaseStartsAtTheStartEndsAtTheEndAndNeverGoesBack() {
        for (SceneDefinition.Ease ease : SceneDefinition.Ease.values()) {
            assertEquals(0, ease.apply(0), 1e-9, ease + " at the start");
            assertEquals(1, ease.apply(1), 1e-9, ease + " at the end");
            double last = 0;
            for (int step = 1; step <= 100; step++) {
                double gone = ease.apply(step / 100.0);
                assertTrue(gone >= last - 1e-12, ease + " never goes back");
                last = gone;
            }
            assertEquals(1, ease.apply(1.5), 1e-9, ease + " past the end stays at the end");
        }
        assertEquals(0.5, SceneDefinition.Ease.IN_OUT.apply(0.5), 1e-9, "in_out is halfway at half the time");
        assertTrue(SceneDefinition.Ease.IN.apply(0.5) < 0.5 && SceneDefinition.Ease.OUT.apply(0.5) > 0.5, "in starts slow, out ends slow");
    }

    @Test
    void theCameraTurnsAsMinecraftDoes() {
        Vec3 here = new Vec3(10, 64, 10);
        assertEquals(0, SceneCamera.facing(here, here.add(0, 0, 5)).yaw(), 1e-4, "south is yaw 0");
        assertEquals(90, SceneCamera.facing(here, here.add(-5, 0, 0)).yaw(), 1e-4, "west is yaw 90");
        assertEquals(-90, SceneCamera.facing(here, here.add(5, 0, 0)).yaw(), 1e-4, "east is yaw -90");
        assertTrue(SceneCamera.facing(here, here.add(0, 5, 5)).pitch() < 0, "looking up is a negative pitch");
        assertEquals(45, SceneCamera.facing(here, here.add(0, -5, 5)).pitch(), 1e-4, "down at 45 degrees");
        SceneCamera.Pose same = SceneCamera.facing(here, here);
        assertEquals(0, same.yaw(), 1e-9, "a target on the camera itself: straight ahead");
    }

    @Test
    void aGlideTurnsTheShortWay() {
        SceneCamera.Pose from = new SceneCamera.Pose(Vec3.ZERO, 170, 0);
        SceneCamera.Pose to = new SceneCamera.Pose(new Vec3(2, 0, 0), -170, 10);
        SceneCamera.Pose half = from.toward(to, 0.5);
        assertEquals(180, half.yaw(), 1e-4, "through 180, not back through 0");
        assertEquals(5, half.pitch(), 1e-4);
        assertEquals(1, half.pos().x, 1e-9);
        assertEquals(0f, half.fov(), 1e-6, "unset FOV stays unset during a glide");

        SceneCamera.Pose wide = new SceneCamera.Pose(Vec3.ZERO, 0, 0, 90f);
        SceneCamera.Pose tight = new SceneCamera.Pose(Vec3.ZERO, 0, 0, 40f);
        assertEquals(65f, wide.toward(tight, 0.5).fov(), 1e-4, "explicit FOVs blend smoothly during a glide");
    }
}
