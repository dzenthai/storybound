package dev.dzenthai.storybound.module.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Pure math tests for camera flight paths: straight lines and centripetal Catmull-Rom splines. */
class ScenePathTest {
    @Test
    void twoPointsStayAnExactStraightLine() {
        Vec3 a = new Vec3(0, 2, -4);
        Vec3 b = new Vec3(10, 6, 4);
        ScenePath path = ScenePath.of(a, List.of(), b);

        assertEquals(a.distanceTo(b), path.length(), 1e-9);
        assertEquals(2, path.controlCount());
        assertEquals(0.0, path.parameterOfControl(0), 1e-9);
        assertEquals(1.0, path.parameterOfControl(1), 1e-9);
        assertVecEquals(a, path.pointAt(0.0), 1e-9);
        assertVecEquals(a.lerp(b, 0.35), path.pointAt(0.35), 1e-9);
        assertVecEquals(b, path.pointAt(1.0), 1e-9);
        assertVecEquals(b.subtract(a), path.tangentAt(0.5), 1e-9);
    }

    @Test
    void splinePassesThroughEveryControlPointAndCurvesSmoothly() {
        Vec3 from = new Vec3(0, 0, 0);
        Vec3 via1 = new Vec3(6, 4, 0);
        Vec3 via2 = new Vec3(12, 4, 6);
        Vec3 to = new Vec3(12, 0, 12);
        ScenePath path = ScenePath.of(from, List.of(via1, via2), to);

        assertEquals(4, path.controlCount());
        assertTrue(path.length() > from.distanceTo(to), "curved spline is longer than straight chord");
        assertVecEquals(from, path.pointAt(0.0), 1e-6);
        assertVecEquals(via1, path.pointAt(path.parameterOfControl(1)), 1e-5);
        assertVecEquals(via2, path.pointAt(path.parameterOfControl(2)), 1e-5);
        assertVecEquals(to, path.pointAt(1.0), 1e-6);

        Vec3 startDir = path.tangentAt(0.05).normalize();
        Vec3 endDir = path.tangentAt(0.95).normalize();
        assertTrue(startDir.x > 0.5, "starts heading along +X toward via1");
        assertTrue(endDir.z > 0.5, "ends heading along +Z toward to");
    }

    @Test
    void arcLengthParameterizationStaysEvenAcrossUnevenControlSpacing() {
        Vec3 from = new Vec3(0, 0, 0);
        Vec3 nearVia = new Vec3(1, 0, 0);
        Vec3 farTo = new Vec3(21, 0, 0);
        ScenePath path = ScenePath.of(from, List.of(nearVia), farTo);

        assertEquals(21.0, path.length(), 1e-3);
        Vec3 midpoint = path.pointAt(0.5);
        assertEquals(10.5, midpoint.x, 0.05, "u=0.5 must be at half the arc length, not at the clustered via point");
        assertTrue(path.parameterOfControl(1) < 0.1, "near via point is near the start of the arc length");
    }

    @Test
    void coincidentPointsNeverProduceNaN() {
        Vec3 p = new Vec3(3, 4, 5);
        ScenePath path = ScenePath.of(p, List.of(p, p), p);

        assertEquals(0.0, path.length(), 1e-9);
        for (double u : new double[] {0.0, 0.5, 1.0}) {
            Vec3 at = path.pointAt(u);
            Vec3 tan = path.tangentAt(u);
            assertTrue(Double.isFinite(at.x) && Double.isFinite(at.y) && Double.isFinite(at.z));
            assertTrue(Double.isFinite(tan.x) && Double.isFinite(tan.y) && Double.isFinite(tan.z));
            assertVecEquals(p, at, 1e-9);
        }
    }

    private static void assertVecEquals(Vec3 expected, Vec3 actual, double tol) {
        assertEquals(expected.x, actual.x, tol, "x");
        assertEquals(expected.y, actual.y, tol, "y");
        assertEquals(expected.z, actual.z, tol, "z");
    }
}

