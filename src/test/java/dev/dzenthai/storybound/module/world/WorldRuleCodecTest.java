package dev.dzenthai.storybound.module.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryRenames;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.EntryRef;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.ItemLock;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.LootOperation;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.PlaceMode;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class WorldRuleCodecTest {
    @Test
    void entryRefParsesIdsTagsAndRejectsInvalid() {
        EntryRef item = EntryRef.parse("minecraft:diamond_sword").getOrThrow();
        assertFalse(item.tag());
        assertEquals("minecraft:diamond_sword", item.toString());

        EntryRef tag = EntryRef.parse("#minecraft:swords").getOrThrow();
        assertTrue(tag.tag());
        assertEquals("#minecraft:swords", tag.toString());

        assertTrue(EntryRef.parse("NOT VALID!").isError());
    }

    @Test
    void entryRefListSupportsSingleOrMixedArrayAndRoundTrips() {
        List<EntryRef> single = WorldRuleDefinition.ENTRY_REFS.parse(JsonOps.INSTANCE,
                JsonParser.parseString("\"#minecraft:swords\"")).getOrThrow();
        assertEquals(1, single.size());
        assertTrue(single.getFirst().tag());

        JsonElement encodedSingle = WorldRuleDefinition.ENTRY_REFS.encodeStart(JsonOps.INSTANCE, single).getOrThrow();
        assertTrue(encodedSingle.isJsonPrimitive());
        assertEquals("#minecraft:swords", encodedSingle.getAsString());

        List<EntryRef> mixed = WorldRuleDefinition.ENTRY_REFS.parse(JsonOps.INSTANCE,
                JsonParser.parseString("[\"#minecraft:swords\", \"minecraft:trident\"]")).getOrThrow();
        assertEquals(2, mixed.size());
        assertTrue(mixed.get(0).tag());
        assertFalse(mixed.get(1).tag());
    }

    @Test
    void itemLockParsesMixedTagsAndIdsWithSensibleDefaultsAndRejectsEmpty() {
        ItemLock lock = ItemLock.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {
                  "title": "High-tier weapons",
                  "items": ["#minecraft:swords", "minecraft:trident"],
                  "block_craft": true,
                  "hide_in_jei": true
                }
                """)).getOrThrow();
        assertTrue(lock.enabled());
        assertEquals(2, lock.items().size());
        assertTrue(lock.blockUse());
        assertTrue(lock.blockAttack());
        assertTrue(lock.blockEquip());
        assertTrue(lock.blockCraft());
        assertFalse(lock.blockPickup());
        assertTrue(lock.hideInJei());

        assertTrue(ItemLock.CODEC.codec().parse(JsonOps.INSTANCE,
                JsonParser.parseString("{ \"items\": [] }")).isError());
    }

    @Test
    void enumsSerializeExpectedNames() {
        assertEquals("barrier", PlaceMode.BARRIER.getSerializedName());
        assertEquals("hazard", PlaceMode.HAZARD.getSerializedName());
        assertEquals("add", LootOperation.ADD.getSerializedName());
        assertEquals("replace", LootOperation.REPLACE.getSerializedName());
        assertEquals("remove", LootOperation.REMOVE.getSerializedName());
    }

    @Test
    void nearestBoxOutwardNormalPointsToClosestWall() {
        BoundingBox box = new BoundingBox(0, 0, 0, 20, 10, 20);
        assertEquals(new Vec3(-1.0, 0.0, 0.0), WorldRules.nearestBoxOutwardNormal(box, 2.0, 10.0));
        assertEquals(new Vec3(1.0, 0.0, 0.0), WorldRules.nearestBoxOutwardNormal(box, 19.0, 10.0));
        assertEquals(new Vec3(0.0, 0.0, -1.0), WorldRules.nearestBoxOutwardNormal(box, 10.0, 2.0));
        assertEquals(new Vec3(0.0, 0.0, 1.0), WorldRules.nearestBoxOutwardNormal(box, 10.0, 19.0));
    }

    @Test
    void worldRulesParticipateInLinksAndRenames() {
        assertTrue(StoryRenames.RENAMEABLE.contains(StoryLinks.WORLD_RULE));
        assertTrue(StoryRenames.REFERRING.contains(StoryLinks.WORLD_RULE));

        ResourceLocation ruleId = ResourceLocation.parse("mypack:no_nether");
        ResourceLocation questId = ResourceLocation.parse("mypack:open_portal");
        ResourceLocation renamedQuestId = ResourceLocation.parse("mypack:nether_gate");
        String rawRule = """
                {
                  "type": "place",
                  "dimension": "minecraft:the_nether",
                  "unlock": { "type": "quest", "quest": "mypack:open_portal", "state": "completed" }
                }
                """;
        StoryLinks.Source source = new StoryLinks.Source() {
            @Override
            public Map<ResourceLocation, String> byType(ResourceLocation type) {
                return type.equals(StoryLinks.WORLD_RULE) ? Map.of(ruleId, rawRule) : Map.of();
            }

            @Override
            public StoryLinks.ScriptFile scriptFile(String path) {
                return null;
            }
        };
        List<StoryLinks.Edge> edges = StoryLinks.collect(source);
        assertEquals(1, edges.size());
        assertEquals(new StoryLinks.NodeKey(StoryLinks.QUEST, questId), edges.getFirst().from());
        assertEquals(new StoryLinks.NodeKey(StoryLinks.WORLD_RULE, ruleId), edges.getFirst().to());

        JsonObject json = JsonParser.parseString(rawRule).getAsJsonObject();
        int rewritten = StoryRenames.rewrite(StoryLinks.WORLD_RULE, json,
                List.of(new StoryRenames.Rename(StoryLinks.QUEST, questId, renamedQuestId)));
        assertEquals(1, rewritten);
        assertEquals("mypack:nether_gate", json.getAsJsonObject("unlock").get("quest").getAsString());
    }
}
