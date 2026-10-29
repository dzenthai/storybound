package dev.dzenthai.storybound.client.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkerContrastTest {
    /** Light colours, the default among them, are shown as chosen. */
    @Test
    void lightColoursStayAsChosen() {
        for (int light : new int[] {0xE8C66A, 0xFFAA00, 0xFFFF55, 0xFFFFFF, 0x55FFFF, 0x55FF55, 0xAAAAAA, 0xFF5555}) {
            assertEquals(0xFF000000 | light, MarkerContrast.readable(light), Integer.toHexString(light));
        }
    }

    /** The colours the dev mode offers look the same everywhere: as chosen in the world, and Xaero has them (the default aside). */
    @Test
    void theOfferedColoursLookTheSameEverywhere() {
        assertEquals(10, dev.dzenthai.storybound.module.waypoint.WaypointDefinition.MARKER_COLORS.size());
        for (int color : dev.dzenthai.storybound.module.waypoint.WaypointDefinition.MARKER_COLORS) {
            assertEquals(color, MarkerContrast.readable(color), "unchanged in the world: " + Integer.toHexString(color));
            if (color != dev.dzenthai.storybound.module.waypoint.WaypointDefinition.DEFAULT_COLOR) {
                assertTrue(dev.dzenthai.storybound.module.waypoint.WaypointDefinition.PALETTE.contains(color), "in Xaero: " + Integer.toHexString(color));
            }
        }
    }

    /** Dark colours are raised to the least lightness, keeping their hue: black to grey, dark red to red. */
    @Test
    void darkColoursAreRaisedKeepingTheirHue() {
        for (int dark : new int[] {0x000000, 0x0000AA, 0xAA0000, 0xAA00AA, 0x555555, 0x5555FF, 0x00002A}) {
            int shown = MarkerContrast.readable(dark);
            assertTrue(MarkerContrast.lightness(shown) >= MarkerContrast.MIN_LIGHTNESS - 0.01, Integer.toHexString(dark) + " -> " + Integer.toHexString(shown));
        }
        int grey = MarkerContrast.readable(0x000000);
        assertEquals((grey >> 16) & 0xFF, grey & 0xFF, "black shows a grey");
        assertEquals((grey >> 8) & 0xFF, grey & 0xFF);
        int red = MarkerContrast.readable(0xAA0000);
        assertTrue(((red >> 16) & 0xFF) > ((red >> 8) & 0xFF) + 60 && ((red >> 16) & 0xFF) > (red & 0xFF) + 60, "still red: " + Integer.toHexString(red));
        int blue = MarkerContrast.readable(0x0000AA);
        assertTrue((blue & 0xFF) > ((blue >> 16) & 0xFF) + 60, "still blue: " + Integer.toHexString(blue));
    }
}
