package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The entity selector's filters (November 7, 2026, stage 1 of "who an NPC is"): what a villager is (profession, outfit),
 * a baby or not, the biome it stands in and whether it is in a village. Each is decoded from JSON as an author writes it.
 * Expected: every given part must match; profession and outfit never match an entity without villager data (an iron
 * golem), but do match a zombie villager; a selector of only "where" parts (baby, biome, in_village) is refused, as it
 * would take every entity there; an unknown profession fails the file, as an unknown entity type does.
 */
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EntitySelectorGameTests {
    private EntitySelectorGameTests() {}

    @GameTest(template = "empty")
    public static void professionAndOutfitMatchOnlyVillagerData(GameTestHelper helper) {
        Villager smith = villager(helper, VillagerProfession.WEAPONSMITH, VillagerType.DESERT, 1);
        Villager jobless = villager(helper, VillagerProfession.NONE, VillagerType.PLAINS, 2);
        ZombieVillager zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE_VILLAGER, 3, 2, 1);
        zombie.setVillagerData(zombie.getVillagerData().setProfession(VillagerProfession.WEAPONSMITH).setType(VillagerType.SNOW));
        IronGolem golem = helper.spawnWithNoFreeWill(EntityType.IRON_GOLEM, 1, 2, 3);

        EntitySelector smiths = selector(helper, """
                { "type": "minecraft:villager", "profession": "minecraft:weaponsmith" }""");
        expect(helper, smiths.matches(smith), true, "a weaponsmith villager is a smith");
        expect(helper, smiths.matches(jobless), false, "a jobless villager is not");
        expect(helper, smiths.mismatch(jobless), "profession is minecraft:none", "and the trace says why");
        expect(helper, smiths.matches(zombie), false, "a zombie weaponsmith is not a villager by kind");

        EntitySelector anySmith = selector(helper, """
                { "profession": ["minecraft:weaponsmith", "minecraft:armorer"] }""");
        expect(helper, anySmith.matches(zombie), true, "without a kind, a zombie villager's profession counts");
        expect(helper, anySmith.matches(golem), false, "an iron golem has no profession");
        expect(helper, anySmith.mismatch(golem), "no profession (not a villager)", "and the trace says why");

        EntitySelector desert = selector(helper, """
                { "type": "minecraft:villager", "outfit": "minecraft:desert" }""");
        expect(helper, desert.matches(smith), true, "a desert villager wears desert clothes");
        expect(helper, desert.matches(jobless), false, "a plains villager does not");
        expect(helper, desert.mismatch(jobless), "outfit is minecraft:plains", "and the trace says why");
        expect(helper, selector(helper, """
                { "outfit": "minecraft:snow" }""").matches(golem), false, "an iron golem has no outfit");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void babyIsAskedOfLivingEntitiesOnly(GameTestHelper helper) {
        Villager adult = villager(helper, VillagerProfession.FARMER, VillagerType.PLAINS, 1);
        Villager child = villager(helper, VillagerProfession.NONE, VillagerType.PLAINS, 2);
        child.setAge(-24000);
        Entity item = helper.spawnItem(net.minecraft.world.item.Items.BREAD, 3, 2, 3);

        EntitySelector grownUps = selector(helper, """
                { "type": "minecraft:villager", "baby": false }""");
        expect(helper, grownUps.matches(adult), true, "an adult villager is a grown-up");
        expect(helper, grownUps.matches(child), false, "a child is not");
        expect(helper, grownUps.mismatch(child), "a baby", "and the trace says why");
        expect(helper, selector(helper, """
                { "type": "minecraft:villager", "baby": true }""").matches(child), true, "baby: true takes the child");
        expect(helper, selector(helper, """
                { "type": "minecraft:item", "baby": false }""").matches(item), true, "an item cannot be a baby, so it counts as grown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void biomeIsWhereTheEntityStandsNow(GameTestHelper helper) {
        Villager villager = villager(helper, VillagerProfession.FARMER, VillagerType.PLAINS, 1);
        String here = villager.level().getBiome(villager.blockPosition()).unwrapKey().orElseThrow().location().toString();
        String elsewhere = here.equals("minecraft:basalt_deltas") ? "minecraft:warped_forest" : "minecraft:basalt_deltas";

        expect(helper, selector(helper, "{ \"type\": \"minecraft:villager\", \"biome\": \"" + here + "\" }").matches(villager), true,
                "the biome it stands in, " + here);
        expect(helper, selector(helper, "{ \"type\": \"minecraft:villager\", \"biome\": [\"" + elsewhere + "\", \"" + here + "\"] }")
                .matches(villager), true, "a list with that biome");
        EntitySelector away = selector(helper, "{ \"type\": \"minecraft:villager\", \"biome\": \"" + elsewhere + "\" }");
        expect(helper, away.matches(villager), false, "another biome");
        expect(helper, away.mismatch(villager), "biome is " + here, "and the trace says why");
        expect(helper, selector(helper, """
                { "type": "minecraft:villager", "biome": "#minecraft:is_nether" }""").matches(villager), false,
                "a biome #tag stands in for a dimension: the test world is not the Nether");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inVillageNeedsAClaimedVillagePoint(GameTestHelper helper) {
        Villager villager = villager(helper, VillagerProfession.FARMER, VillagerType.PLAINS, 1);
        EntitySelector inVillage = selector(helper, """
                { "type": "minecraft:villager", "in_village": true }""");
        EntitySelector outside = selector(helper, """
                { "type": "minecraft:villager", "in_village": false }""");
        expect(helper, inVillage.matches(villager), false, "no bell, no beds: not a village");
        expect(helper, outside.matches(villager), true, "so in_village: false takes it");
        expect(helper, inVillage.mismatch(villager), "not in a village", "and the trace says why");

        BlockPos bell = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.BELL);
        // Vanilla adds the bell's point a tick later; then a villager claiming it (as at a gathering) makes it a village.
        helper.runAfterDelay(2, () -> {
            expect(helper, inVillage.matches(villager), false, "a bell nobody has claimed is not a village yet");
            Optional<BlockPos> claimed = helper.getLevel().getPoiManager()
                    .take(type -> type.is(PoiTypes.MEETING), (type, pos) -> pos.equals(bell), bell, 2);
            expect(helper, claimed.isPresent(), true, "the bell is claimed");
            expect(helper, inVillage.matches(villager), true, "a claimed bell next to it: in a village");
            expect(helper, outside.matches(villager), false, "so in_village: false no longer takes it");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void aSelectorNeedsToSayWhichEntities(GameTestHelper helper) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        for (String where : new String[] {"{ \"baby\": false }", "{ \"biome\": \"minecraft:plains\" }", "{ \"in_village\": true }"}) {
            expect(helper, EntitySelector.CODEC.parse(ops, JsonParser.parseString(where)).isError(), true,
                    where + " alone would take every entity there");
        }
        DataResult<EntitySelector> unknown = EntitySelector.CODEC.parse(ops, JsonParser.parseString("""
                { "type": "minecraft:villager", "profession": "nomod:alchemist" }"""));
        expect(helper, unknown.isError(), true, "an unknown profession fails the file, as an unknown kind does");
        expect(helper, EntitySelector.CODEC.parse(ops, JsonParser.parseString("""
                { "profession": "minecraft:none" }""")).isSuccess(), true, "a profession alone says which entities");
        helper.succeed();
    }

    private static Villager villager(GameTestHelper helper, VillagerProfession profession, VillagerType type, int x) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, x, 2, 1);
        villager.setVillagerData(villager.getVillagerData().setProfession(profession).setType(type));
        return villager;
    }

    private static EntitySelector selector(GameTestHelper helper, String json) {
        return EntitySelector.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), JsonParser.parseString(json))
                .getOrThrow(GameTestAssertException::new);
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }
}
