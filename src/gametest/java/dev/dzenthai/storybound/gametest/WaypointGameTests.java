package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.EntityReturns;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.state.PartyManager;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestEvents;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import dev.dzenthai.storybound.module.waypoint.StructureLocator;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import dev.dzenthai.storybound.module.waypoint.WaypointRegistries;
import dev.dzenthai.storybound.module.waypoint.WaypointSightings;
import dev.dzenthai.storybound.module.waypoint.WaypointTarget;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Waypoints on a real server: quest stage bindings, conditions, waypoints added by scope, entity-following, validation. */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WaypointGameTests {
    private static final ResourceLocation QUEST = key("waypoint_quest");
    private static final ResourceLocation CAMP = key("camp");
    private static final ResourceLocation SHRINE = key("shrine");
    private static final ResourceLocation TOWER = key("tower");

    private WaypointGameTests() {}

    @GameTest(template = "empty")
    public static void stageWaypointsComeAndGoWithTheStage(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        with(helper, WaypointRegistries.WAYPOINTS.serverStore(), WaypointDefinition.CODEC, Map.of(
                CAMP, "{ \"name\": \"gametest.camp\", \"target\": { \"pos\": [10, 64, 10] }, \"radius\": 16 }",
                SHRINE, "{ \"name\": \"gametest.shrine\", \"target\": { \"pos\": [20, 64, 20], \"dimension\": \"minecraft:the_nether\" },"
                        + " \"quest\": \"gametest:waypoint_quest\", \"stage\": \"second\" }",
                TOWER, "{ \"name\": \"gametest.tower\", \"target\": { \"pos\": [30, 70, 30] }, \"color\": \"#3366FF\", \"style\": \"storybound:npc\","
                        + " \"conditions\": { \"type\": \"flag\", \"key\": \"gametest:tower_known\" } }"
        ), () -> with(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(QUEST, """
                { "title": "gametest.waypoint_quest", "stages": [
                    { "id": "first", "waypoints": ["gametest:camp"] },
                    { "id": "second" } ] }
                """), () -> {
            QuestEvents.rebuild();
            Waypoints.rebuild();
            expect(helper, keys(player), List.of(), "nothing before the quest and without the flag");

            Quests.start(player, QUEST);
            ClientWaypoint camp = Waypoints.visible(player).get(CAMP.toString());
            helper.assertTrue(camp != null, "the first stage shows its waypoint");
            expect(helper, camp.quest(), Optional.of(QUEST), "it knows its quest, for highlighting");
            expect(helper, camp.radius(), 16f, "an area keeps its radius");
            expect(helper, camp.entity(), ClientWaypoint.NO_ENTITY, "a fixed waypoint is above no entity");

            Quests.setStage(player, QUEST, "second");
            expect(helper, keys(player), List.of(SHRINE.toString()), "the next stage hides the camp and shows the shrine");
            expect(helper, Waypoints.visible(player).get(SHRINE.toString()).dimension(), ResourceLocation.withDefaultNamespace("the_nether"), "dimension kept");

            ok(() -> StoryState.set(player, StoryScope.PLAYER, key("tower_known"), new StoryValue.Bool(true)));
            helper.assertTrue(Waypoints.visible(player).containsKey(TOWER.toString()), "conditions decide an unbound waypoint");
            expect(helper, Waypoints.visible(player).get(TOWER.toString()).color(), 0xFF3366FF, "colour");
            expect(helper, Waypoints.visible(player).get(TOWER.toString()).style(), ResourceLocation.fromNamespaceAndPath("storybound", "npc"), "marker style");
            expect(helper, camp.style(), WaypointDefinition.DEFAULT_STYLE, "the default marker style");

            Quests.setStatus(player, QUEST, null);
            expect(helper, keys(player), List.of(TOWER.toString()), "a reset quest takes its waypoints away");
        }));
        Waypoints.rebuild();
        QuestEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    /**
     * For a party quest, what one member's waypoint found, the others see: the reaper the leader came across shows for
     * a member far away, who never saw it. For a personal quest it does not: each may have their own.
     */
    @GameTest(template = "empty")
    public static void aPartyQuestsWaypointShowsWhatAMemberFound(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ok(() -> PartyManager.create(leader, "Trackers"));
        ok(() -> PartyManager.invite(leader, member));
        ok(() -> PartyManager.accept(member));
        leader.setPos(helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        member.setPos(helper.absoluteVec(new Vec3(1.5, 1, 60.5)));
        LivingEntity prey = helper.spawnWithNoFreeWill(EntityType.PIG, 3, 1, 1);
        prey.addTag("gametest_shared_prey");
        ResourceLocation trail = key("trail");
        String quest = """
                { "title": "gametest.waypoint_quest", "scope": "%s", "stages": [ { "id": "hunt", "waypoints": ["gametest:trail"] } ] }
                """;
        with(helper, WaypointRegistries.WAYPOINTS.serverStore(), WaypointDefinition.CODEC, Map.of(trail,
                "{ \"name\": \"gametest.trail\", \"target\": { \"nearest\": { \"type\": \"minecraft:pig\", \"tag\": \"gametest_shared_prey\" }, \"range\": 8 } }"
        ), () -> {
            with(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(QUEST, quest.formatted("party")), () -> {
                QuestEvents.rebuild();
                Waypoints.rebuild();
                Quests.start(leader, QUEST);
                assertAt(helper, leader, trail, prey, "the leader finds it nearby");
                ClientWaypoint shared = Waypoints.visible(member).get(trail.toString());
                helper.assertTrue(shared != null && Math.abs(shared.pos().x - prey.getX()) < 1 && Math.abs(shared.pos().z - prey.getZ()) < 1,
                        "the member, far beyond the range, sees what the leader found, got " + shared);
                Quests.setStatus(leader, QUEST, null);
            });
            // What the party showed the member is theirs now (they know where it is): a member who never learnt it.
            member.setData(WaypointRegistries.SIGHTINGS, WaypointSightings.EMPTY);
            with(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(QUEST, quest.formatted("player")), () -> {
                QuestEvents.rebuild();
                Waypoints.rebuild();
                Quests.start(leader, QUEST);
                Quests.start(member, QUEST);
                assertAt(helper, leader, trail, prey, "the leader's own");
                helper.assertFalse(Waypoints.visible(member).containsKey(trail.toString()), "a personal quest's waypoint finds only one's own");
                Quests.setStatus(leader, QUEST, null);
                Quests.setStatus(member, QUEST, null);
            });
        });
        Waypoints.rebuild();
        QuestEvents.rebuild();
        prey.discard();
        ok(() -> PartyManager.disband(leader));
        remove(helper, leader);
        remove(helper, member);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void addedWaypointsFollowTheirVisibility(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        ok(() -> PartyManager.create(leader, "Scouts"));
        ok(() -> PartyManager.invite(leader, member));
        ok(() -> PartyManager.accept(member));

        run(helper, leader, "{ \"type\": \"add_waypoint\", \"id\": \"gametest:mine\", \"name\": \"gametest.mine\", \"pos\": [1, 2, 3] }");
        run(helper, leader, "{ \"type\": \"add_waypoint\", \"id\": \"gametest:ours\", \"name\": \"gametest.ours\", \"pos\": [4, 5, 6], \"visibility\": \"party\", \"style\": \"storybound:npc\" }");
        run(helper, leader, "{ \"type\": \"add_waypoint\", \"id\": \"gametest:everyone\", \"name\": \"gametest.everyone\", \"pos\": [7, 8, 9], \"visibility\": \"world\" }");

        helper.assertTrue(Waypoints.visible(leader).keySet().containsAll(List.of("player/gametest:mine", "party/gametest:ours", "world/gametest:everyone")), "the author sees all three");
        helper.assertFalse(Waypoints.visible(member).containsKey("player/gametest:mine"), "a party member does not see a personal waypoint");
        helper.assertTrue(Waypoints.visible(member).containsKey("party/gametest:ours"), "a party member sees the party one");
        expect(helper, Waypoints.visible(member).get("party/gametest:ours").style(), ResourceLocation.fromNamespaceAndPath("storybound", "npc"), "an added waypoint keeps its style");
        helper.assertFalse(Waypoints.visible(stranger).containsKey("party/gametest:ours"), "a stranger does not see the party one");
        helper.assertTrue(Waypoints.visible(stranger).containsKey("world/gametest:everyone"), "everyone sees a world waypoint");

        run(helper, member, "{ \"type\": \"remove_waypoint\", \"id\": \"gametest:ours\", \"visibility\": \"party\" }");
        helper.assertFalse(Waypoints.visible(leader).containsKey("party/gametest:ours"), "any party member can remove the party waypoint");
        run(helper, leader, "{ \"type\": \"remove_waypoint\", \"id\": \"gametest:mine\" }");
        run(helper, leader, "{ \"type\": \"remove_waypoint\", \"id\": \"gametest:everyone\", \"visibility\": \"world\" }");
        expect(helper, Waypoints.visible(stranger).keySet().stream().filter(key -> key.contains("gametest:")).toList(), List.of(), "all removed");

        ok(() -> PartyManager.disband(leader));
        List.of(leader, member, stranger).forEach(player -> remove(helper, player));
        helper.succeed();
    }

    // "open": the entity moves several blocks; outside a 1-block test area it can end up in an unloaded chunk,
    // which hangs the server on shutdown.
    @GameTest(template = "open")
    public static void waypointFollowsAnEntity(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        LivingEntity guide = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 2);
        Actions.runAll(LogicCodecs.ACTIONS.parse(ops(helper), JsonParser.parseString(
                        "{ \"type\": \"add_waypoint\", \"id\": \"gametest:guide\", \"name\": \"gametest.guide\", \"follow\": true }"))
                .getOrThrow(GameTestAssertException::new), new StoryContext(player, guide), "gametest", OnFail.ABORT);
        Vec3 before = Waypoints.visible(player).get("player/gametest:guide").pos();
        helper.assertTrue(before.distanceTo(guide.position()) < 3, "the waypoint is at the entity");
        expect(helper, Waypoints.visible(player).get("player/gametest:guide").entity(), guide.getId(), "with the entity's id for the client");
        guide.moveTo(helper.absoluteVec(new Vec3(9.5, 1, 2.5)));
        Vec3 after = Waypoints.visible(player).get("player/gametest:guide").pos();
        helper.assertTrue(after.x - before.x > 4, "and moves with it, got " + before + " -> " + after);
        Waypoints.remove(player.server, key("guide"), StoryScope.PLAYER, player.getUUID());
        guide.discard();
        remove(helper, player);
        helper.succeed();
    }

    /** Entities this test says will come back, as an NPC waiting to respawn does. */
    private static final Set<UUID> COMING_BACK = ConcurrentHashMap.newKeySet();

    static {
        EntityReturns.register((server, uuid) -> COMING_BACK.contains(uuid));
    }

    /** Pigs with a tag no other test uses, so pigs and players of tests running alongside never count. */
    @GameTest(template = "open")
    public static void nearestWaypointPicksAnEntityAndKeepsIt(GameTestHelper helper) {
        WaypointDefinition shorthand = parse(helper, "{ \"name\": \"gametest.z\", \"target\": { \"nearest\": \"minecraft:zombie\" } }");
        helper.assertTrue(shorthand.target() instanceof WaypointTarget.Nearest nearest && nearest.entity().type().isPresent()
                && nearest.range() == WaypointTarget.Nearest.DEFAULT_RANGE, "an entity type alone is a selector by type, range 64");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("quarry_hunter"), new StoryValue.Bool(true)));
        LivingEntity near = helper.spawnWithNoFreeWill(EntityType.PIG, 3, 1, 1);
        LivingEntity far = helper.spawnWithNoFreeWill(EntityType.PIG, 6, 1, 1);
        LivingEntity untagged = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        near.addTag("gametest_quarry");
        far.addTag("gametest_quarry");
        ResourceLocation quarry = key("quarry");
        DefinitionStore<WaypointDefinition> store = WaypointRegistries.WAYPOINTS.serverStore();
        Map<ResourceLocation, WaypointDefinition> previous = store.all();
        Map<ResourceLocation, WaypointDefinition> installed = new LinkedHashMap<>(previous);
        installed.put(quarry, parse(helper, "{ \"name\": \"gametest.quarry\","
                + " \"target\": { \"nearest\": { \"type\": \"minecraft:pig\", \"tag\": \"gametest_quarry\" }, \"range\": 8 },"
                + " \"conditions\": { \"type\": \"flag\", \"key\": \"gametest:quarry_hunter\" } }"));
        store.replace(installed);
        Waypoints.rebuild();
        LivingEntity last = null;
        try {
            assertAt(helper, player, quarry, near, "the nearest tagged pig, not the closer untagged one");
            far.moveTo(helper.absoluteVec(new Vec3(2.5, 1, 1.5)));
            assertAt(helper, player, quarry, near, "kept while it lives and stays in range, though another came closer");
            near.discard();
            assertAt(helper, player, quarry, far, "then the next nearest");
            Vec3 left = far.position();
            far.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            ClientWaypoint unloaded = Waypoints.visible(player).get(quarry.toString());
            helper.assertTrue(unloaded != null && Math.abs(unloaded.pos().x - left.x) < 1 && Math.abs(unloaded.pos().z - left.z) < 1,
                    "one whose chunk unloaded (the player went far away) stays where it was last seen, got " + unloaded);
            expect(helper, unloaded.entity(), ClientWaypoint.NO_ENTITY, "and the client is not told to draw it at an entity");
            helper.assertTrue(unloaded.radius() >= 16, "but the area around that place, got radius " + unloaded.radius());
            last = helper.spawnWithNoFreeWill(EntityType.PIG, 4, 1, 1);
            last.addTag("gametest_quarry");
            assertAt(helper, player, quarry, last, "a loaded one in range comes before it");
            expect(helper, Waypoints.visible(player).get(quarry.toString()).radius(), 0f, "as a point again");
            last.moveTo(helper.absoluteVec(new Vec3(11.5, 1, 11.5)));
            assertAt(helper, player, quarry, last, "kept when the player walks away from it and no other is in range");
            last.remove(Entity.RemovalReason.KILLED);
            helper.assertFalse(Waypoints.visible(player).containsKey(quarry.toString()), "nothing once the pick died");

            // One that dies and waits to respawn (an NPC with respawn) keeps its place, as an area, until it is back.
            LivingEntity phoenix = helper.spawnWithNoFreeWill(EntityType.PIG, 4, 1, 1);
            phoenix.addTag("gametest_quarry");
            assertAt(helper, player, quarry, phoenix, "a new pick");
            Vec3 fell = phoenix.position();
            COMING_BACK.add(phoenix.getUUID());
            try {
                // As in the game: killed, it lies there for its death animation, then leaves the level.
                phoenix.kill();
                helper.assertTrue(!phoenix.isAlive() && !phoenix.isRemoved(), "dying, still in the level");
                ClientWaypoint dying = Waypoints.visible(player).get(quarry.toString());
                helper.assertTrue(dying != null && dying.radius() >= 16, "while it dies: the area, its place kept, got " + dying);
                phoenix.remove(Entity.RemovalReason.KILLED);
                ClientWaypoint waiting = Waypoints.visible(player).get(quarry.toString());
                helper.assertTrue(waiting != null && Math.abs(waiting.pos().x - fell.x) < 1 && waiting.radius() >= 16,
                        "dead but coming back: the area where it was, got " + waiting);
            } finally {
                COMING_BACK.remove(phoenix.getUUID());
            }
            helper.assertFalse(Waypoints.visible(player).containsKey(quarry.toString()), "gone for good once it no longer waits");

            // Joined again far from it: only the saved sighting is known, in a chunk that is not loaded.
            Vec3 away = helper.absoluteVec(new Vec3(5000.5, 70, 5000.5));
            player.setData(WaypointRegistries.SIGHTINGS, new WaypointSightings(Map.of(quarry.toString(),
                    new WaypointSightings.Sighting(last.getUUID(), helper.getLevel().dimension().location(), away))));
            helper.assertFalse(Waypoints.visible(player).containsKey(quarry.toString()),
                    "not for an entity someone killed while the player was away, though nobody keeps its place loaded");
            player.setData(WaypointRegistries.SIGHTINGS, new WaypointSightings(Map.of(quarry.toString(),
                    new WaypointSightings.Sighting(UUID.randomUUID(), helper.getLevel().dimension().location(), away))));
            ClientWaypoint remembered = Waypoints.visible(player).get(quarry.toString());
            helper.assertTrue(remembered != null && remembered.pos().distanceTo(away) < 1 && remembered.radius() >= 16,
                    "after a relog, the area where it was last seen, got " + remembered);
        } finally {
            player.setData(WaypointRegistries.SIGHTINGS, WaypointSightings.EMPTY);
            store.replace(previous);
            Waypoints.rebuild();
            List.of(near, far, untagged).forEach(LivingEntity::discard);
            if (last != null) {
                last.discard();
            }
            remove(helper, player);
        }
        helper.succeed();
    }

    private static void assertAt(GameTestHelper helper, ServerPlayer player, ResourceLocation id, LivingEntity entity, String what) {
        ClientWaypoint waypoint = Waypoints.visible(player).get(id.toString());
        helper.assertTrue(waypoint != null, what + ": no waypoint");
        double dx = waypoint.pos().x - entity.getX();
        double dz = waypoint.pos().z - entity.getZ();
        helper.assertTrue(dx * dx + dz * dz < 1, what + ": at " + waypoint.pos() + ", the entity at " + entity.position());
        expect(helper, waypoint.entity(), entity.getId(), what + ": the client is told which entity to draw it at");
    }

    /**
     * The GameTest world generates no structures (vanilla creates it with {@code generate_structures} off), so every
     * search here ends with nothing found: this checks the search runs in the background and ends without freezing the
     * server or marking anything. Strongholds, because their ring positions are known in advance: the search still
     * loads chunks (from the search thread, through the server thread) but stays quick.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void structureWaypointsAreSearchedInTheBackground(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // Other tests run alongside and list every gametest waypoint they see: these show only for this player.
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("structure_seeker"), new StoryValue.Bool(true)));
        ResourceLocation stronghold = key("stronghold");
        ResourceLocation nowhere = key("nowhere_structure");
        String seeker = ", \"conditions\": { \"type\": \"flag\", \"key\": \"gametest:structure_seeker\" } }";
        DefinitionStore<WaypointDefinition> store = WaypointRegistries.WAYPOINTS.serverStore();
        Map<ResourceLocation, WaypointDefinition> previous = store.all();
        Map<ResourceLocation, WaypointDefinition> installed = new LinkedHashMap<>(previous);
        installed.put(stronghold, parse(helper, "{ \"name\": \"gametest.stronghold\", \"target\": { \"structure\": \"minecraft:stronghold\", \"search_radius\": 256 }" + seeker));
        installed.put(nowhere, parse(helper, "{ \"name\": \"gametest.nowhere\", \"target\": { \"structure\": \"gametest:no_such_structure\" }" + seeker));
        store.replace(installed);
        Waypoints.rebuild();

        helper.assertFalse(Waypoints.visible(player).containsKey(stronghold.toString()), "nothing while the background search runs");
        boolean done = Actions.runAll(LogicCodecs.ACTIONS.parse(ops(helper), JsonParser.parseString(
                "{ \"type\": \"add_waypoint\", \"id\": \"gametest:lost\", \"name\": \"gametest.l\", \"structure\": \"gametest:no_such_structure\" }"))
                .getOrThrow(GameTestAssertException::new), StoryContext.of(player), "gametest", OnFail.ABORT);
        helper.assertFalse(done, "add_waypoint fails at once for an unknown structure");
        StructureLocator.Search search = new StructureLocator.Search(StructureRef.of("minecraft:stronghold").orElseThrow(), Level.OVERWORLD,
                Waypoints.structureSearchOrigin(player, Level.OVERWORLD), 256);
        run(helper, player, "{ \"type\": \"add_waypoint\", \"id\": \"gametest:to_stronghold\", \"name\": \"gametest.s\","
                + " \"structure\": \"minecraft:stronghold\", \"search_radius\": 256 }");
        helper.assertTrue(StructureLocator.result(player.server, search) == null, "the action's search runs in the background");

        helper.succeedWhen(() -> {
            Optional<BlockPos> answer = StructureLocator.result(player.server, search);
            helper.assertTrue(answer != null, "the search ends");
            helper.assertTrue(answer.isEmpty(), "with nothing found, in a world without structures");
            Map<String, ClientWaypoint> now = Waypoints.visible(player);
            helper.assertFalse(now.containsKey(stronghold.toString()), "a structure not found marks nothing");
            helper.assertFalse(now.containsKey("player/gametest:to_stronghold"), "neither does the action's waypoint");
            helper.assertFalse(now.containsKey(nowhere.toString()), "an unknown structure marks nothing and breaks nothing");
            store.replace(previous);
            Waypoints.rebuild();
            Waypoints.remove(player.server, key("to_stronghold"), StoryScope.PLAYER, player.getUUID());
            remove(helper, player);
        });
    }

    /**
     * A structure waypoint follows the player from cell to cell. The world has no structures, so the answers are
     * planted; everything runs within this tick, so no other test's data reload can drop them meanwhile. The search
     * radius is 1 chunk, so the one real search this starts (and finds nothing) ends at once.
     */
    @GameTest(template = "empty")
    public static void structureWaypointMovesToANearerStructure(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("village_seeker"), new StoryValue.Bool(true)));
        ResourceLocation village = key("nearest_village");
        DefinitionStore<WaypointDefinition> store = WaypointRegistries.WAYPOINTS.serverStore();
        Map<ResourceLocation, WaypointDefinition> previous = store.all();
        Map<ResourceLocation, WaypointDefinition> installed = new LinkedHashMap<>(previous);
        installed.put(village, parse(helper, "{ \"name\": \"gametest.village\", \"target\": { \"structure\": \"minecraft:village_plains\", \"search_radius\": 1 },"
                + " \"conditions\": { \"type\": \"flag\", \"key\": \"gametest:village_seeker\" } }"));
        store.replace(installed);
        Waypoints.rebuild();
        StructureRef plains = StructureRef.of("minecraft:village_plains").orElseThrow();
        Vec3 start = player.position();
        try {
            BlockPos first = plant(player, plains, 100);
            assertStructureAt(helper, player, village, first, "the village found near where the player is");

            player.setPos(start.add(1000, 0, 0));
            BlockPos second = plant(player, plains, -40);
            assertStructureAt(helper, player, village, second, "moved to a nearer village once the player is in another cell");

            player.setPos(start.add(1300, 0, 0));
            plant(player, plains, 3000);
            assertStructureAt(helper, player, village, second, "not to one farther away than the current");

            player.setPos(start.add(1600, 0, 0));
            BlockPos searching = Waypoints.structureSearchOrigin(player, Level.OVERWORLD);
            assertStructureAt(helper, player, village, second, "kept while the search from the new cell runs");
            helper.assertTrue(StructureLocator.result(player.server, new StructureLocator.Search(plains, Level.OVERWORLD, searching, 1)) == null,
                    "that search runs in the background");
        } finally {
            player.setPos(start);
            store.replace(previous);
            Waypoints.rebuild();
            remove(helper, player);
        }
        helper.succeed();
    }

    /** Plants a search answer for the player's cell: a structure {@code dx} blocks east of the cell's middle. */
    private static BlockPos plant(ServerPlayer player, StructureRef structure, int dx) {
        BlockPos cell = Waypoints.structureSearchOrigin(player, Level.OVERWORLD);
        BlockPos at = cell.offset(dx, 0, 0);
        StructureLocator.remember(new StructureLocator.Search(structure, Level.OVERWORLD, cell, 1), Optional.of(at));
        return at;
    }

    private static void assertStructureAt(GameTestHelper helper, ServerPlayer player, ResourceLocation id, BlockPos at, String what) {
        ClientWaypoint waypoint = Waypoints.visible(player).get(id.toString());
        helper.assertTrue(waypoint != null, what + ": no waypoint");
        helper.assertTrue(Math.abs(waypoint.pos().x - (at.getX() + 0.5)) < 0.01 && Math.abs(waypoint.pos().z - (at.getZ() + 0.5)) < 0.01,
                what + ": at " + waypoint.pos() + ", expected " + at);
    }

    private static WaypointDefinition parse(GameTestHelper helper, String json) {
        return WaypointDefinition.CODEC.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new);
    }

    @GameTest(template = "empty")
    public static void validatorReportsBrokenWaypointBindings(GameTestHelper helper) {
        ResourceLocation broken = key("broken_waypoint_quest");
        with(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(broken,
                "{ \"title\": \"gametest.q\", \"stages\": [ { \"id\": \"a\", \"waypoints\": [\"gametest:nowhere\"] } ] }"),
                () -> with(helper, WaypointRegistries.WAYPOINTS.serverStore(), WaypointDefinition.CODEC, Map.of(key("loose"),
                        "{ \"name\": \"gametest.loose\", \"target\": { \"pos\": [0, 0, 0] }, \"stage\": \"a\" }"), () -> {
                    List<ValidationIssue> issues = ValidationService.validate().issues();
                    helper.assertTrue(errors(issues, broken).stream().anyMatch(message -> message.contains("unknown waypoint gametest:nowhere")),
                            "a stage listing an unknown waypoint, got " + errors(issues, broken));
                    helper.assertTrue(errors(issues, key("loose")).stream().anyMatch(message -> message.contains("'stage' needs 'quest'")),
                            "stage without quest, got " + errors(issues, key("loose")));
                }));
        helper.succeed();
    }

    private static List<String> keys(ServerPlayer player) {
        return Waypoints.visible(player).keySet().stream().filter(key -> key.startsWith("gametest:")).sorted().toList();
    }

    private static List<String> errors(List<ValidationIssue> issues, ResourceLocation entry) {
        return issues.stream().filter(issue -> entry.equals(issue.entry()) && issue.isError()).map(ValidationIssue::message).toList();
    }

    private static void run(GameTestHelper helper, ServerPlayer player, String json) {
        boolean done = Actions.runAll(LogicCodecs.ACTIONS.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new),
                StoryContext.of(player), "gametest", OnFail.ABORT);
        helper.assertTrue(done, "action failed: " + json);
    }

    private static <T> void with(GameTestHelper helper, DefinitionStore<T> store, Codec<T> codec, Map<ResourceLocation, String> definitions, Runnable body) {
        Map<ResourceLocation, T> previous = store.all();
        Map<ResourceLocation, T> installed = new LinkedHashMap<>(previous);
        definitions.forEach((id, json) -> installed.put(id, codec.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new)));
        store.replace(installed);
        try {
            body.run();
        } finally {
            store.replace(previous);
        }
    }

    private static RegistryOps<JsonElement> ops(GameTestHelper helper) {
        return RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static void ok(ThrowingRunnable action) {
        try {
            action.run();
        } catch (StoryException e) {
            throw new GameTestAssertException(e.getMessage());
        }
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }

    private static void remove(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws StoryException;
    }
}
