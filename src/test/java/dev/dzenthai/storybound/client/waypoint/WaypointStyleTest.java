package dev.dzenthai.storybound.client.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class WaypointStyleTest {
    @Test
    void missingFieldsTakeTheBuiltInDefaults() {
        WaypointStyle style = parse("{ \"size\": 8, \"name\": \"near_crosshair\", \"edge\": \"never\" }");
        assertEquals(8, style.size());
        assertEquals(WaypointStyle.NameMode.NEAR_CROSSHAIR, style.name());
        assertEquals(WaypointStyle.EdgeMode.NEVER, style.edge());
        assertEquals(WaypointStyle.FALLBACK.icon(), style.icon());
        assertEquals(WaypointStyle.FALLBACK.maxDistance(), style.maxDistance());
    }

    @Test
    void ownSpritesAndColours() {
        WaypointStyle style = parse("{ \"icon\": \"mypack:marker/skull\", \"distance_color\": \"#C0392B\", \"text_color\": \"#80FFFFFF\" }");
        assertEquals(ResourceLocation.fromNamespaceAndPath("mypack", "marker/skull"), style.icon());
        assertEquals(0xFFC0392B, style.distanceColor(), "#RRGGBB is opaque");
        assertEquals(0x80FFFFFF, style.textColor(), "#AARRGGBB keeps its alpha");
    }

    @Test
    void badValuesAreRejected() {
        assertTrue(WaypointStyle.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{ \"name\": \"hover\" }")).error().isPresent());
        assertTrue(WaypointStyle.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{ \"size\": 2 }")).error().isPresent());
    }

    private static WaypointStyle parse(String json) {
        return WaypointStyle.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }
}
