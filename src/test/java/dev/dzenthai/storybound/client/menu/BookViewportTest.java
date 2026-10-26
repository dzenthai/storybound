package dev.dzenthai.storybound.client.menu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookViewportTest {
    @Test
    void guiScaleDoesNotChangePhysicalBookOrHitTargets() {
        for (int[] size : new int[][]{{1280, 800}, {1920, 1080}, {854, 480}, {3440, 1440}, {800, 1000}}) {
            var reference = BookViewport.fit(size[0], size[1], 1);
            for (int guiScale = 1; guiScale <= 6; guiScale++) {
                var viewport = BookViewport.fit(size[0], size[1], guiScale);
                for (double point : new double[]{0, 37, 320, 640}) {
                    assertEquals(reference.toGuiX(point), viewport.toGuiX(point) * guiScale, 0.00001);
                    assertEquals(point, viewport.toBookX(reference.toGuiX(point) / guiScale), 0.00001);
                }
                for (double point : new double[]{0, 21, 200, 400}) {
                    assertEquals(reference.toGuiY(point), viewport.toGuiY(point) * guiScale, 0.00001);
                    assertEquals(point, viewport.toBookY(reference.toGuiY(point) / guiScale), 0.00001);
                }
                assertTrue(viewport.x() >= 0 && viewport.y() >= 0);
                assertTrue(viewport.toGuiX(640) * guiScale <= size[0] + 0.00001);
                assertTrue(viewport.toGuiY(400) * guiScale <= size[1] + 0.00001);
            }
        }
    }
}
