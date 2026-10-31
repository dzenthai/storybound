package dev.dzenthai.storybound.client.menu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookViewportTest {
    /** The journal book shows its texture pixels at a whole ratio of window pixels, starting on a window pixel. */
    @Test
    void theJournalBookKeepsItsPixelsWhole() {
        JournalLayout book = JournalLayout.fit(BookViewport.MENU_WIDTH, BookViewport.MENU_HEIGHT);
        int[][] windows = {{1920, 1080}, {2560, 1440}, {3840, 2160}, {3440, 1440}, {1366, 768}, {1280, 720}, {1600, 900},
                {1024, 768}, {854, 480}, {640, 360}};
        // Window pixels per pixel of the 384×256 book: the largest with which it takes at most 80% of the window's height
        // and 90% of its width, as the documents (October 31, 2026; before, as large as the menu fits: 4, 5, 8, 2).
        double[][] expected = {{1920, 3}, {2560, 4}, {3840, 6}, {1366, 2}};
        for (int[] window : windows) {
            var reference = BookViewport.wholePixels(window[0], window[1], 1, JournalSkin.BOOK_WIDTH, book);
            for (int guiScale = 1; guiScale <= 6; guiScale++) {
                var viewport = BookViewport.wholePixels(window[0], window[1], guiScale, JournalSkin.BOOK_WIDTH, book);
                // The book as the menu lays it out on the canvas it is given (its size follows the window).
                JournalLayout drawn = JournalLayout.fit(viewport.width(), viewport.height());
                double left = viewport.toGuiX(drawn.x()) * guiScale;
                double top = viewport.toGuiY(drawn.y()) * guiScale;
                double right = viewport.toGuiX(drawn.x() + drawn.width()) * guiScale;
                double bottom = viewport.toGuiY(drawn.y() + drawn.height()) * guiScale;
                assertEquals(Math.rint(left), left, 1e-6, "the book starts on a window pixel");
                assertEquals(Math.rint(top), top, 1e-6);
                double perTexturePixel = (right - left) / JournalSkin.BOOK_WIDTH;
                double whole = perTexturePixel >= 1 ? perTexturePixel : 1 / perTexturePixel;
                assertEquals(Math.rint(whole), whole, 1e-6, "a whole ratio: " + perTexturePixel);
                assertEquals(perTexturePixel, (bottom - top) / JournalSkin.BOOK_HEIGHT, 1e-9, "the same both ways");
                assertTrue(viewport.toGuiX(0) * guiScale >= -1e-6 && viewport.toGuiX(viewport.width()) * guiScale <= window[0] + 1e-6);
                assertTrue(viewport.toGuiY(0) * guiScale >= -1e-6 && viewport.toGuiY(viewport.height()) * guiScale <= window[1] + 1e-6);
                if (perTexturePixel >= 2) {
                    // The text, drawn in the canvas' units, has whole pixels too, and the canvas starts on a pixel.
                    double perUnit = viewport.scale() * guiScale;
                    assertEquals(Math.rint(perUnit), perUnit, 1e-6, "a unit is whole window pixels at " + window[0] + "x" + window[1]);
                    assertEquals(Math.rint(viewport.toGuiX(0) * guiScale), viewport.toGuiX(0) * guiScale, 1e-6);
                    assertEquals(Math.rint(viewport.toGuiY(0) * guiScale), viewport.toGuiY(0) * guiScale, 1e-6);
                }
                assertEquals(reference.toGuiX(100), viewport.toGuiX(100) * guiScale, 1e-6, "the HUD scale changes nothing");
                for (double[] known : expected) {
                    if (known[0] == window[0]) {
                        assertEquals(known[1], perTexturePixel, 1e-9, "at " + window[0] + "x" + window[1]);
                    }
                }
            }
        }
    }

    /** A document's sheet: whole window pixels to its pixels, at most 80% of the window's height, centred, on a pixel. */
    @Test
    void documentsKeepTheirPixelsWholeAndLeaveTheWorldInSight() {
        // Window height → window pixels per pixel of a 320 × 384 sheet (the letter).
        double[][] expected = {{1080, 2}, {1440, 3}, {2160, 4}};
        for (int[] window : new int[][]{{1920, 1080}, {2560, 1440}, {3840, 2160}, {1366, 768}, {1280, 720}, {854, 480}}) {
            for (int guiScale = 1; guiScale <= 4; guiScale++) {
                var viewport = BookViewport.document(window[0], window[1], guiScale, 320, 384);
                double left = viewport.toGuiX((BookViewport.WIDTH - 320) / 2) * guiScale;
                double top = viewport.toGuiY((BookViewport.HEIGHT - 384) / 2) * guiScale;
                double perPixel = viewport.scale() * guiScale;
                assertTrue(384 * perPixel <= 0.8 * window[1] + 1e-6, "at most 80% of the height at " + window[1]);
                assertEquals(window[0] / 2.0, left + 160 * perPixel, 1.0, "centred");
                if (perPixel >= 2) {
                    assertEquals(Math.rint(perPixel), perPixel, 1e-9, "whole pixels");
                    assertEquals(Math.rint(left), left, 1e-6, "on a window pixel");
                    assertEquals(Math.rint(top), top, 1e-6);
                }
                for (double[] known : expected) {
                    if (known[0] == window[1]) {
                        assertEquals(known[1], perPixel, 1e-9, "at " + window[0] + "x" + window[1]);
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
