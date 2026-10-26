package dev.dzenthai.storybound.client.menu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JournalLayoutTest {
    @Test
    void pagesAndControlsFitAtSupportedGuiSizes() {
        for (int[] size : new int[][]{{320, 180}, {320, 240}, {427, 240}, {640, 360}, {640, 400}, {960, 540}, {1920, 1080}}) {
            JournalLayout layout = JournalLayout.fit(size[0], size[1]);
            JournalLayout.Rect content = layout.content();
            assertTrue(layout.x() >= 0 && layout.y() >= 0);
            assertTrue(layout.x() + layout.width() <= size[0]);
            assertTrue(layout.y() + layout.height() <= size[1]);
            assertTrue(content.x() > layout.x());
            assertTrue(content.x() + layout.indexWidth() < layout.x() + layout.width() * 0.46);
            assertTrue(layout.detailLeft() >= layout.x() + layout.width() * 0.53);
            assertTrue(content.right() < layout.x() + layout.width() * 0.95);
            assertTrue(content.height() > 40);
            assertTrue(content.right() - layout.detailLeft() >= 64);
            if (size[0] == BookViewport.WIDTH && size[1] == BookViewport.HEIGHT) {
                assertTrue(content.right() - layout.detailLeft() >= 160);
            }
            assertTrue(Math.abs(layout.indexWidth() - (content.right() - layout.detailLeft())) <= 2);
        }
    }

    @Test
    void sideBookmarksStayOutsideReadingAreaAndDoNotOverlap() {
        for (int[] size : new int[][]{{320, 180}, {427, 240}, {960, 540}}) {
            JournalLayout layout = JournalLayout.fit(size[0], size[1]);
            for (int count = 1; count <= 8; count++) {
                int lastBottom = layout.y();
                for (int index = 0; index < count; index++) {
                    JournalLayout.Rect tab = layout.bookmark(index, count);
                    assertTrue(tab.y() >= lastBottom);
                    assertTrue(tab.width() > 0);
                    assertTrue(tab.x() > layout.content().right());
                    assertTrue(tab.bottom() <= layout.y() + layout.height() * 80 / 100);
                    assertTrue(tab.right() <= size[0]);
                    lastBottom = tab.bottom();
                }
            }
        }
    }
}
