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

    /**
     * A variant and a mob's data (November 9, 2026), for what mods' mobs keep: a snowy wolf matches "minecraft:snowy",
     * not "minecraft:pale"; a pig has no variant; a red sheep holds {Color:14b} and not {Color:1b} (the type of the
     * number counts, as in vanilla's @e[nbt=...]). Both are filters: a selector with them ranks closer.
     */
    @GameTest(template = "empty")
    public static void aVariantAndTheMobsDataPickMobsOfAnyMod(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Wolf wolf = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.WOLF, 1, 2, 1);
        wolf.setVariant(helper.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.WOLF_VARIANT)
                .getHolderOrThrow(net.minecraft.world.entity.animal.WolfVariants.SNOWY));
        net.minecraft.world.entity.animal.Sheep sheep = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.SHEEP, 3, 2, 1);
        sheep.setColor(net.minecraft.world.item.DyeColor.RED);
        net.minecraft.world.entity.animal.Pig pig = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.PIG, 3, 2, 3);

        EntitySelector snowy = selector(helper, """
                { "type": "minecraft:wolf", "variant": "minecraft:snowy" }""");
        expect(helper, snowy.matches(wolf), true, "the snowy wolf");
        expect(helper, selector(helper, """
                { "type": "minecraft:wolf", "variant": [ "minecraft:pale", "minecraft:woods" ] }""").matches(wolf), false, "not a pale or woods one");
        expect(helper, selector(helper, """
                { "type": "minecraft:wolf", "variant": [ "minecraft:pale", "minecraft:woods" ] }""").mismatch(wolf), "variant is minecraft:snowy", "the trace says why");
        expect(helper, selector(helper, """
                { "type": "minecraft:pig", "variant": "minecraft:snowy" }""").mismatch(pig), "no variant", "a pig has none");
        expect(helper, selector(helper, """
                { "type": "minecraft:sheep", "nbt": "{Color:14b}" }""").matches(sheep), true, "the red sheep holds Color 14");
        expect(helper, selector(helper, """
                { "type": "minecraft:sheep", "nbt": "{Color:1b}" }""").matches(sheep), false, "not Color 1");
        expect(helper, selector(helper, """
                { "type": "minecraft:sheep", "nbt": "{Color:14}" }""").matches(sheep), false, "an int is not the byte it holds, as in vanilla");
        expect(helper, snowy.filters(), 1, "a variant is a filter");
        helper.succeed();
    }

    /**
     * A mob's data (November 9, 2026): fields of what it saves, nothing known of its kind. Expected: a weaponsmith matches
     * "VillagerData/profession" weaponsmith or armorer, a farmer does not and the trace says which value it has; a number
     * matches any numeric tag of that value (a villager's level 1, saved as an int); true / false a 1 or 0 (a sheep not
     * sheared); a field the mob does not save (a "Stage" of another mod's zombie) never matches; each field is a filter;
     * a number is written back whole (1, not 1.0), and the filter reads the same from NBT, as a waypoint keeps it.
     */
    @GameTest(template = "empty")
    public static void dataPicksAMobByWhatItSaves(GameTestHelper helper) {
        Villager farmer = villager(helper, VillagerProfession.FARMER, VillagerType.PLAINS, 1);
        Villager smith = villager(helper, VillagerProfession.WEAPONSMITH, VillagerType.PLAINS, 3);
        net.minecraft.world.entity.animal.Sheep sheep = helper.spawnWithNoFreeWill(EntityType.SHEEP, 1, 2, 3);

        EntitySelector smiths = selector(helper, """
                { "type": "minecraft:villager", "data": { "VillagerData/profession": ["minecraft:weaponsmith", "minecraft:armorer"] } }""");
        expect(helper, smiths.matches(smith), true, "the weaponsmith");
        expect(helper, smiths.matches(farmer), false, "not the farmer");
        expect(helper, smiths.mismatch(farmer), "VillagerData/profession is minecraft:farmer, wanted minecraft:weaponsmith or minecraft:armorer",
                "and the trace says why");
        expect(helper, selector(helper, """
                { "type": "minecraft:villager", "data": { "VillagerData/level": 1 } }""").matches(smith), true, "level 1, an int saved");
        expect(helper, selector(helper, """
                { "type": "minecraft:villager", "data": { "VillagerData/level": 2 } }""").matches(smith), false, "not level 2");
        expect(helper, selector(helper, """
                { "type": "minecraft:sheep", "data": { "Sheared": false } }""").matches(sheep), true, "a sheep not sheared");
        expect(helper, selector(helper, """
                { "type": "minecraft:sheep", "data": { "Sheared": true } }""").matches(sheep), false, "not a sheared one");
        EntitySelector staged = selector(helper, """
                { "type": "minecraft:villager", "data": { "Stage": 2 } }""");
        expect(helper, staged.matches(farmer), false, "a field it does not save never matches");
        expect(helper, staged.mismatch(farmer), "Stage is not there, wanted 2", "and the trace says why");
        expect(helper, selector(helper, """
                { "type": "minecraft:villager", "data": { "VillagerData/profession": "minecraft:farmer", "VillagerData/level": 1 } }""").filters(), 2,
                "each field is a filter");

        RegistryOps<JsonElement> json = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        expect(helper, EntitySelector.CODEC.encodeStart(json, staged).getOrThrow().getAsJsonObject().getAsJsonObject("data").get("Stage").toString(), "2",
                "a whole number is written whole");
        RegistryOps<net.minecraft.nbt.Tag> nbt = RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, helper.getLevel().registryAccess());
        EntitySelector kept = EntitySelector.CODEC.parse(nbt, EntitySelector.CODEC.encodeStart(nbt, smiths).getOrThrow()).getOrThrow();
        expect(helper, kept.matches(smith) && !kept.matches(farmer), true, "read back from NBT, it picks the same mobs");
        helper.succeed();
    }

    /** The dimension it is in now: the test world is the overworld; the trace names the one it is in. */
    @GameTest(template = "empty")
    public static void dimensionIsWhereTheEntityIsNow(GameTestHelper helper) {
        Villager villager = villager(helper, VillagerProfession.FARMER, VillagerType.PLAINS, 1);
        expect(helper, selector(helper, """
                { "type": "minecraft:villager", "dimension": "minecraft:overworld" }""").matches(villager), true, "in the overworld");
        EntitySelector nether = selector(helper, """
                { "type": "minecraft:villager", "dimension": "minecraft:the_nether" }""");
        expect(helper, nether.matches(villager), false, "not in the Nether");
        expect(helper, nether.mismatch(villager), "in minecraft:overworld", "and the trace says why");
        expect(helper, nether.filters(), 1, "a dimension is a filter");
        helper.succeed();
    }

    /**
     * What a place can never be, as the game makes its worlds (nothing of vanilla written in the mod): the Nether has no
     * plains, no biome of #is_overworld, no End city; a bastion is never in basalt deltas; but a fortress in a crimson
     * forest and an End city in the End highlands can be; an unknown dimension is said so. The location condition passes
     * its place on to the validator.
     */
    @GameTest(template = "empty")
    public static void aPlaceThatCanNeverBeIsSaid(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        expect(helper, problems(helper, server, "minecraft:the_nether", "minecraft:plains", null),
                java.util.List.of("biome minecraft:plains is never in minecraft:the_nether"), "plains in the Nether");
        expect(helper, problems(helper, server, "minecraft:the_nether", "#minecraft:is_overworld", null),
                java.util.List.of("no biome of #minecraft:is_overworld is ever in minecraft:the_nether"), "an overworld #tag in the Nether");
        expect(helper, problems(helper, server, "minecraft:the_nether", null, "minecraft:end_city"),
                java.util.List.of("structure minecraft:end_city is never in minecraft:the_nether"), "an End city in the Nether");
        expect(helper, problems(helper, server, "minecraft:the_nether", "minecraft:basalt_deltas", "minecraft:bastion_remnant"),
                java.util.List.of("structure minecraft:bastion_remnant is never in biome minecraft:basalt_deltas"), "a bastion in basalt deltas");
        expect(helper, problems(helper, server, "minecraft:the_nether", "minecraft:crimson_forest", "minecraft:fortress"), java.util.List.of(),
                "a fortress in a crimson forest can be");
        expect(helper, problems(helper, server, "minecraft:the_end", "minecraft:end_highlands", "minecraft:end_city"), java.util.List.of(),
                "an End city in the End highlands can be");
        expect(helper, problems(helper, server, "storyboundtest:nowhere", null, null), java.util.List.of("dimension storyboundtest:nowhere is unknown"),
                "an unknown dimension");

        java.util.List<dev.dzenthai.storybound.core.logic.WorldPlaces.Place> passed = new java.util.ArrayList<>();
        dev.dzenthai.storybound.core.logic.LogicCodecs.CONDITION.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()),
                        JsonParser.parseString("{ \"type\": \"location\", \"dimension\": \"minecraft:the_nether\", \"biome\": \"minecraft:plains\" }"))
                .getOrThrow().collectReferences(new dev.dzenthai.storybound.core.logic.References() {
                    @Override
                    public void place(dev.dzenthai.storybound.core.logic.WorldPlaces.Place place) {
                        passed.add(place);
                    }
                });
        expect(helper, passed.size() == 1 && !dev.dzenthai.storybound.core.logic.WorldPlaces.problems(server, passed.getFirst()).isEmpty(), true,
                "the location condition tells the validator its place");
        helper.succeed();
    }

    /**
     * The world's map the editor gets (stage 2): every dimension, the Nether's biomes without plains, a structure with the
     * biomes it stands in (a #tag the client resolves), the structure tags with their members (a client is not sent them).
     */
    @GameTest(template = "empty")
    public static void theEditorGetsTheWorldsMap(GameTestHelper helper) {
        java.util.Map<String, java.util.List<String>> map = dev.dzenthai.storybound.core.logic.WorldPlaces.forEditor(helper.getLevel().getServer());
        expect(helper, map.get("world:dimensions").containsAll(java.util.List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")), true,
                "the three dimensions");
        java.util.List<String> nether = map.get("world:biomes:minecraft:the_nether");
        expect(helper, nether.contains("minecraft:crimson_forest") && !nether.contains("minecraft:plains"), true, "the Nether's biomes, no plains: " + nether);
        expect(helper, map.get("world:structures").stream().anyMatch(line -> line.startsWith("minecraft:fortress=#")), true,
                "a fortress with its biome tag");
        expect(helper, map.get("world:structure_tags").stream().anyMatch(line -> line.startsWith("#minecraft:village=") && line.contains("minecraft:village_plains")),
                true, "#village with its members");
        helper.succeed();
    }

    private static java.util.List<String> problems(GameTestHelper helper, net.minecraft.server.MinecraftServer server, String dimension,
                                                  @org.jetbrains.annotations.Nullable String biome, @org.jetbrains.annotations.Nullable String structure) {
        StringBuilder json = new StringBuilder("{ \"type\": \"minecraft:villager\", \"dimension\": \"" + dimension + "\"");
        if (biome != null) {
            json.append(", \"biome\": \"").append(biome).append('"');
        }
        if (structure != null) {
            json.append(", \"structure\": \"").append(structure).append('"');
        }
        return dev.dzenthai.storybound.core.logic.WorldPlaces.problems(server, selector(helper, json.append(" }").toString()).place());
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
        for (String where : new String[] {"{ \"baby\": false }", "{ \"biome\": \"minecraft:plains\" }", "{ \"in_village\": true }",
                "{ \"dimension\": \"minecraft:the_nether\" }", "{ \"data\": { \"Stage\": 2 } }"}) {
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
