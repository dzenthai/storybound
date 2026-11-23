package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class DevTraceRecorderTest {
    @Test
    void aStraightWalkKeepsOnlyItsEnds() {
        List<Vec3> walked = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            walked.add(new Vec3(i * 2, 64, 0));
        }
        assertEquals(List.of(walked.getFirst(), walked.getLast()), DevTraceRecorder.simplify(walked));
    }

    @Test
    void aTurnIsKept() {
        List<Vec3> walked = List.of(new Vec3(0, 64, 0), new Vec3(2, 64, 0), new Vec3(4, 64, 0), new Vec3(4, 64, 2), new Vec3(4, 64, 4));
        assertEquals(List.of(new Vec3(0, 64, 0), new Vec3(4, 64, 0), new Vec3(4, 64, 4)), DevTraceRecorder.simplify(walked));
    }

    @Test
    void aSmallSwayIsSmoothedAway() {
        List<Vec3> walked = List.of(new Vec3(0, 64, 0), new Vec3(2, 64, 0.2), new Vec3(4, 64, -0.2), new Vec3(6, 64, 0));
        assertEquals(List.of(new Vec3(0, 64, 0), new Vec3(6, 64, 0)), DevTraceRecorder.simplify(walked));
    }

    @Test
    void tooManyPointsAreThinnedEvenlyKeepingTheEnds() {
        List<Vec3> trail = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            trail.add(new Vec3(i, 64, i % 2));
        }
        List<Vec3> thinned = DevTraceRecorder.thinTo(trail, 512);
        assertEquals(512, thinned.size());
        assertEquals(trail.getFirst(), thinned.getFirst());
        assertEquals(trail.getLast(), thinned.getLast());
    }
}
