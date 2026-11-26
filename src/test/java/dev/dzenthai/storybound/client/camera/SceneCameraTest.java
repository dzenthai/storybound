package dev.dzenthai.storybound.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dzenthai.storybound.module.scene.SceneDefinition;
import dev.dzenthai.storybound.network.ScenePayloads;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
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

        SceneCamera.Pose wide = new SceneCamera.Pose(Vec3.ZERO, 0, 0, 90f, -2f);
        SceneCamera.Pose tight = new SceneCamera.Pose(Vec3.ZERO, 0, 0, 40f, 4f);
        SceneCamera.Pose mid = wide.toward(tight, 0.5);
        assertEquals(65f, mid.fov(), 1e-4, "explicit FOVs blend smoothly during a glide");
        assertEquals(1f, mid.roll(), 1e-4, "roll blends smoothly during a glide");
    }

    @Test
    void shotZoomRollLookOffsetAndCodecRoundTripWork() {
        ScenePayloads.Shot shot = new ScenePayloads.Shot(
                new Vec3(0, 2, -4), List.of(), new Vec3(0, 2, -2),
                ScenePayloads.LOOK_POINT, new Vec3(0, 2, 0), -1,
                0, 40, SceneDefinition.Ease.LINEAR.ordinal(), SceneDefinition.Transition.CUT.ordinal(),
                60f, 40f, new Vec3(1.0, -0.5, 0.0), -2.0f, 2.0f, 0.04f);

        SceneCamera.Pose start = SceneCamera.pose(shot, 0.0, 0f);
        SceneCamera.Pose middle = SceneCamera.pose(shot, 0.5, 0f);
        SceneCamera.Pose end = SceneCamera.pose(shot, 1.0, 0f);
        assertEquals(60f, start.fov(), 1e-4, "zoom starts at fov");
        assertEquals(50f, middle.fov(), 1e-4, "zoom interpolates at midpoint");
        assertEquals(40f, end.fov(), 1e-4, "zoom finishes at fov_to");
        assertEquals(-2f, start.roll(), 1e-4, "roll starts at roll");
        assertEquals(0f, middle.roll(), 1e-4, "roll interpolates at midpoint");
        assertEquals(2f, end.roll(), 1e-4, "roll finishes at roll_to");
        // Camera at (0, 2, -4) looking toward (0, 2, 0) faces south (+Z), where viewer's right is -X; positive lookOffset.x shifts aim to -X (positive yaw).
        assertTrue(start.yaw() > 10f, "positive lookOffset.x shifts aim rightward (placing subject on left third): " + start.yaw());
        assertTrue(start.pitch() > 5f, "negative lookOffset.y aims slightly lower (giving subject headroom): " + start.pitch());

        ByteBuf buf = Unpooled.buffer();
        ScenePayloads.Shot.STREAM_CODEC.encode(buf, shot);
        ScenePayloads.Shot decoded = ScenePayloads.Shot.STREAM_CODEC.decode(buf);
        assertEquals(shot, decoded, "all cinematic Shot fields survive network round-trip");
    }

    @Test
    void freeLookInterpolatesShortestYawArcAndSurvivesCodecRoundTrip() {
        List<Vec2> rotations = List.of(new Vec2(170f, -10f), new Vec2(-170f, 10f));
        ScenePayloads.Shot freeShot = new ScenePayloads.Shot(
                new Vec3(0, 4, -6), List.of(), new Vec3(6, 4, -6),
                ScenePayloads.LOOK_FREE, Vec3.ZERO, -1,
                0, 40, SceneDefinition.Ease.LINEAR.ordinal(), SceneDefinition.Transition.CUT.ordinal(),
                58f, 48f, Vec3.ZERO, -1f, 1f, 0.02f, rotations);

        SceneCamera.Pose start = SceneCamera.pose(freeShot, 0.0, 0f);
        SceneCamera.Pose mid = SceneCamera.pose(freeShot, 0.5, 0f);
        SceneCamera.Pose end = SceneCamera.pose(freeShot, 1.0, 0f);
        assertEquals(170f, start.yaw(), 1e-4, "starts at first rotation yaw");
        assertEquals(-10f, start.pitch(), 1e-4, "starts at first rotation pitch");
        assertEquals(0f, Mth.wrapDegrees(mid.yaw() - 180f), 1e-4, "turns shortest way through 180 instead of across 0");
        assertEquals(0f, mid.pitch(), 1e-4, "interpolates pitch smoothly");
        assertEquals(0f, Mth.wrapDegrees(end.yaw() - (-170f)), 1e-4, "ends at final rotation yaw");
        assertEquals(10f, end.pitch(), 1e-4, "ends at final rotation pitch");

        ByteBuf buf = Unpooled.buffer();
        ScenePayloads.Shot.STREAM_CODEC.encode(buf, freeShot);
        ScenePayloads.Shot decoded = ScenePayloads.Shot.STREAM_CODEC.decode(buf);
        assertEquals(ScenePayloads.LOOK_FREE, decoded.look(), "look mode preserved");
        assertEquals(2, decoded.rotations().size(), "rotation count preserved");
        assertEquals(170f, decoded.rotations().get(0).x, 1e-4, "first yaw preserved");
        assertEquals(-10f, decoded.rotations().get(0).y, 1e-4, "first pitch preserved");
        assertEquals(-170f, decoded.rotations().get(1).x, 1e-4, "second yaw preserved");
        assertEquals(10f, decoded.rotations().get(1).y, 1e-4, "second pitch preserved");
    }
}
