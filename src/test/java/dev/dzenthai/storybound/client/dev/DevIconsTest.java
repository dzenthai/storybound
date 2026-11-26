package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The dev mode's icons: every glyph has one, and a label's glyph is read only at its start. */
class DevIconsTest {
    @Test
    void everyGlyphHasAnIcon() {
        DevIcons.GLYPHS.forEach((glyph, icon) -> assertTrue(DevIcons.has(icon), glyph + " → " + icon));
    }

    @Test
    void aLabelsGlyphBecomesItsIcon() {
        assertEquals(new DevIcons.Split("save", "Save"), DevIcons.split("💾 Save"));
        assertEquals(new DevIcons.Split("trash", ""), DevIcons.split("🗑"));
        assertEquals(new DevIcons.Split("plus", "Add"), DevIcons.split("+ Add"));
        assertEquals(new DevIcons.Split("warning", "2"), DevIcons.split("⚠ 2"));
        assertEquals(new DevIcons.Split("grid", ""), DevIcons.split("⊞"));
        assertEquals(new DevIcons.Split("grid_off", ""), DevIcons.split("⊟"));
        assertEquals(new DevIcons.Split("keys", ""), DevIcons.split("⌨"));
    }

    @Test
    void textThatOnlyBeginsLikeAGlyphStaysText() {
        assertNull(DevIcons.split("+3").icon(), "a count, not the plus icon");
        assertNull(DevIcons.split("Save 💾").icon(), "only at the start");
        assertNull(DevIcons.split("").icon());
        assertNull(DevIcons.split("Ω omega").icon(), "a glyph with no icon");
    }
}
