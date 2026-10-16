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
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.state.PartyManager;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestEvents;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import dev.dzenthai.storybound.module.waypoint.WaypointRegistries;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
        guide.moveTo(helper.absoluteVec(new Vec3(9.5, 1, 2.5)));
        Vec3 after = Waypoints.visible(player).get("player/gametest:guide").pos();
        helper.assertTrue(after.x - before.x > 4, "and moves with it, got " + before + " -> " + after);
        Waypoints.remove(player.server, key("guide"), StoryScope.PLAYER, player.getUUID());
        guide.discard();
        remove(helper, player);
        helper.succeed();
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
