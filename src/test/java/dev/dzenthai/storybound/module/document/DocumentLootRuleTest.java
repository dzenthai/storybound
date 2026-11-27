package dev.dzenthai.storybound.module.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class DocumentLootRuleTest {
    @Test
    void detectsChestLootTablesAcrossVanillaAndMods() {
        assertTrue(DocumentDefinition.LootRule.isChestTable(ResourceLocation.parse("minecraft:chests/abandoned_mineshaft")));
        assertTrue(DocumentDefinition.LootRule.isChestTable(ResourceLocation.parse("minecraft:chests/village/village_weaponsmith")));
        assertTrue(DocumentDefinition.LootRule.isChestTable(ResourceLocation.parse("modid:chest/watchtower")));
        assertTrue(DocumentDefinition.LootRule.isChestTable(ResourceLocation.parse("modid:dungeons/chests/crypt")));

        assertFalse(DocumentDefinition.LootRule.isChestTable(ResourceLocation.parse("minecraft:entities/zombie")));
        assertFalse(DocumentDefinition.LootRule.isChestTable(ResourceLocation.parse("minecraft:blocks/stone")));
        assertFalse(DocumentDefinition.LootRule.isChestTable(ResourceLocation.parse("minecraft:gameplay/fishing")));
    }

    @Test
    void emptyTablesMatchesOnlyChestsWhileExplicitTablesMatchesExactIds() {
        DocumentDefinition.LootRule defaultChests = new DocumentDefinition.LootRule(
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                1.0f, DocumentDefinition.LootRule.Once.NONE, Optional.empty());
        assertTrue(defaultChests.matchesTable(ResourceLocation.parse("minecraft:chests/simple_dungeon")));
        assertFalse(defaultChests.matchesTable(ResourceLocation.parse("minecraft:entities/pillager")));

        ResourceLocation pillager = ResourceLocation.parse("minecraft:entities/pillager");
        DocumentDefinition.LootRule mobDrop = new DocumentDefinition.LootRule(
                List.of(pillager), Optional.empty(), Optional.empty(), Optional.empty(),
                0.5f, DocumentDefinition.LootRule.Once.PLAYER, Optional.empty());
        assertTrue(mobDrop.matchesTable(pillager));
        assertFalse(mobDrop.matchesTable(ResourceLocation.parse("minecraft:chests/simple_dungeon")));
    }

    @Test
    void clientViewStripsServerOnlyLootRules() {
        DocumentDefinition.LootRule rule = new DocumentDefinition.LootRule(
                List.of(ResourceLocation.parse("minecraft:chests/stronghold_library")),
                Optional.empty(), Optional.empty(), Optional.empty(),
                0.25f, DocumentDefinition.LootRule.Once.WORLD, Optional.empty());
        DocumentDefinition full = new DocumentDefinition(
                DocumentKind.BOOK, new StoryText.Literal("Old Tome"), Optional.empty(), Optional.empty(),
                DocumentDefinition.JournalSettings.DEFAULT, List.of(), Optional.empty(),
                Optional.empty(), List.of(rule));

        assertEquals(1, full.loot().size());
        assertTrue(full.clientView().loot().isEmpty());
    }

    @Test
    void onceEnumSerializesExpectedNames() {
        assertEquals("none", DocumentDefinition.LootRule.Once.NONE.getSerializedName());
        assertEquals("player", DocumentDefinition.LootRule.Once.PLAYER.getSerializedName());
        assertEquals("world", DocumentDefinition.LootRule.Once.WORLD.getSerializedName());
    }
}
