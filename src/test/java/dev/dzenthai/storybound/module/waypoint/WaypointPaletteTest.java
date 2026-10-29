package dev.dzenthai.storybound.module.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaypointPaletteTest {
    /** The 16 colours of Minecraft's text, opaque: the ones Xaero's Minimap can show. */
    @Test
    void theSixteenTextColours() {
        assertEquals(16, WaypointDefinition.PALETTE.size());
        assertTrue(WaypointDefinition.PALETTE.contains(0xFFFFAA00), "gold");
        assertTrue(WaypointDefinition.PALETTE.contains(0xFFAA0000), "dark red");
        assertTrue(WaypointDefinition.PALETTE.stream().allMatch(color -> (color >>> 24) == 0xFF), "opaque");
    }

    /** Any other colour is shown by Xaero as the nearest of them; a palette colour as itself. */
    @Test
    void theNearestColourOfThePalette() {
        assertEquals(0xFF5555FF, WaypointDefinition.nearestInPalette(0xFF3366FF));
        assertEquals(0xFFFFAA00, WaypointDefinition.nearestInPalette(0xFFE67E22), "the demo's old orange");
        assertEquals(0xFFAA0000, WaypointDefinition.nearestInPalette(0xFFD35400), "the demo's old rust");
        for (int color : WaypointDefinition.PALETTE) {
            assertEquals(color, WaypointDefinition.nearestInPalette(color));
        }
    }
}
