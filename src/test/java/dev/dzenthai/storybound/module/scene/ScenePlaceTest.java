package dev.dzenthai.storybound.module.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** A shot's points as right, up and forward of its anchor, turned the way the anchor faces. */
class ScenePlaceTest {
    private static void near(Vec3 expected, Vec3 actual, String what) {
        assertEquals(0, expected.distanceTo(actual), 1e-9, what + ": expected " + expected + ", got " + actual);
    }

    @Test
    void offsetsFollowTheAnchorsFacing() {
        Vec3 at = new Vec3(100, 64, -20);
        Scenes.Found south = new Scenes.Found(at, 0, null);
        near(at.add(0, 0, 3), Scenes.place(south, new Vec3(0, 0, 3)), "facing south, forward is +z");
        near(at.add(-2, 0, 0), Scenes.place(south, new Vec3(2, 0, 0)), "and right is -x");
        near(at.add(0, 5, 0), Scenes.place(south, new Vec3(0, 5, 0)), "up is up whatever the facing");
        Scenes.Found west = new Scenes.Found(at, 90, null);
        near(at.add(-3, 0, 0), Scenes.place(west, new Vec3(0, 0, 3)), "facing west, forward is -x");
        near(at.add(0, 0, -2), Scenes.place(west, new Vec3(2, 0, 0)), "and right is -z");
    }
}
