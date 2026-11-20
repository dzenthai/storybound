package dev.dzenthai.storybound.module.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Stars or skulls a relationship scale shows: one for every 10, toward zero, five at most. */
class NpcReputationTest {
    @Test
    void tenPointsASymbolTowardZeroFiveAtMost() {
        assertEquals(0, NpcReputation.symbols(0), "neutral");
        assertEquals(0, NpcReputation.symbols(9), "not yet a star");
        assertEquals(1, NpcReputation.symbols(10));
        assertEquals(4, NpcReputation.symbols(49));
        assertEquals(5, NpcReputation.symbols(50));
        assertEquals(5, NpcReputation.symbols(80), "past the bounds of an old save: still five");
        assertEquals(0, NpcReputation.symbols(-9), "not yet a skull");
        assertEquals(1, NpcReputation.symbols(-10));
        assertEquals(5, NpcReputation.symbols(-55));
    }
}
