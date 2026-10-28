package dev.dzenthai.storybound.client.menu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookViewportTest {
    /** The journal book shows its texture pixels at a whole ratio of window pixels, starting on a window pixel. */
    @Test
    void theJournalBookKeepsItsPixelsWhole() {
        JournalLayout book = JournalLayout.fit(BookViewport.WIDTH, BookViewport.HEIGHT);
        int[][] windows = {{1920, 1080}, {2560, 1440}, {3840, 2160}, {3440, 1440}, {1366, 768}, {1280, 720}, {1600, 900},
                {1024, 768}, {854, 480}, {640, 360}};
        double[][] expected = {{1920, 1}, {2560, 1}, {3840, 2}, {1366, 0.5}};
        for (int[] window : windows) {
            var reference = BookViewport.wholePixels(window[0], window[1], 1, JournalSkin.BOOK_WIDTH, book);
            for (int guiScale = 1; guiScale <= 6; guiScale++) {
                var viewport = BookViewport.wholePixels(window[0], window[1], guiScale, JournalSkin.BOOK_WIDTH, book);
                double left = viewport.toGuiX(book.x()) * guiScale;
                double top = viewport.toGuiY(book.y()) * guiScale;
                double right = viewport.toGuiX(book.x() + book.width()) * guiScale;
                double bottom = viewport.toGuiY(book.y() + book.height()) * guiScale;
                assertEquals(Math.rint(left), left, 1e-6, "the book starts on a window pixel");
                assertEquals(Math.rint(top), top, 1e-6);
                double perTexturePixel = (right - left) / JournalSkin.BOOK_WIDTH;
                double whole = perTexturePixel >= 1 ? perTexturePixel : 1 / perTexturePixel;
                assertEquals(Math.rint(whole), whole, 1e-6, "a whole ratio: " + perTexturePixel);
                assertEquals(perTexturePixel, (bottom - top) / JournalSkin.BOOK_HEIGHT, 1e-9, "the same both ways");
                assertTrue(viewport.toGuiX(0) * guiScale >= -1e-6 && viewport.toGuiX(BookViewport.WIDTH) * guiScale <= window[0] + 1e-6);
                assertTrue(viewport.toGuiY(0) * guiScale >= -1e-6 && viewport.toGuiY(BookViewport.HEIGHT) * guiScale <= window[1] + 1e-6);
                assertEquals(reference.toGuiX(100), viewport.toGuiX(100) * guiScale, 1e-6, "the HUD scale changes nothing");
                for (double[] known : expected) {
                    if (known[0] == window[0]) {
                        assertEquals(known[1], perTexturePixel, 1e-9, "at " + window[0] + "x" + window[1]);
                    }
                }
            }
        }
    }

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
