package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.scene.SceneDefinition;
import dev.dzenthai.storybound.module.scene.SceneRegistries;
import dev.dzenthai.storybound.module.scene.Scenes;
import java.util.LinkedHashMap;
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
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Scenes on the server: their clock (what happens at its seconds, on_end, scene_finished), shots left out when their
 * anchor is not there, one scene at a time, the watcher kept safe and the mobs near calm, a scene seen again after
 * leaving the game, a death cutting it short. Each runs in a batch of its own: scenes share the server's clock.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SceneGameTests {
    private SceneGameTests() {}

    private static final String ONE_SECOND = """
            { "shots": [ { "camera": { "from": [0, 3, -4], "to": [2, 3, -4], "duration": 1 },
                           "during": [ { "at": 0.5, "actions": { "type": "add_var", "key": "gametest:scene_cue", "amount": 1 } } ] } ],
              "on_end": { "type": "add_var", "key": "gametest:scene_end", "amount": 1 } }
            """;

    /** What is wrong in a scene's file is said when it loads. */
    @GameTest(template = "empty")
    public static void aSceneThatCannotBeIsRefusedWhenItLoads(GameTestHelper helper) {
        for (String wrong : new String[] {
                "{ \"shots\": [] }",
                "{ \"shots\": [ { \"camera\": { \"from\": [0, 1, 0] } } ] }",
                "{ \"shots\": [ { \"camera\": { \"from\": [0, 1, 0], \"duration\": 2, \"speed\": 3 } } ] }",
                "{ \"shots\": [ { \"anchor\": { \"npc\": \"a:b\", \"waypoint\": \"a:c\" }, \"camera\": { \"from\": [0, 1, 0], \"duration\": 2 } } ] }",
                "{ \"shots\": [ { \"camera\": { \"from\": [0, 1, 0], \"duration\": 2 }, \"during\": [ { \"title\": { \"text\": { \"literal\": \"a\" } },"
                        + " \"sound\": { \"sound\": \"minecraft:block.bell.use\" } } ] } ] }"}) {
            helper.assertTrue(parse(helper, wrong).isError(), "refused: " + wrong);
        }
        SceneDefinition speed = parse(helper, "{ \"shots\": [ { \"camera\": { \"from\": [0, 1, 0], \"to\": [12, 1, 0], \"speed\": 4 } } ] }")
                .getOrThrow(GameTestAssertException::new);
        helper.assertTrue(Math.abs(speed.shots().getFirst().camera().seconds() - 3f) < 0.01f, "12 blocks at 4 a second: 3 seconds");
        helper.succeed();
    }

    /** A cue happens at its second, on_end and scene_finished at the end; while it plays the player watches it. */
    @GameTest(template = "empty", batch = "scene_clock", timeoutTicks = 100)
    public static void aScenePlaysItsCuesAndEnds(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Map<ResourceLocation, StoryEvent> events = new LinkedHashMap<>(CoreDefinitions.EVENTS.serverStore().all());
        Map<ResourceLocation, StoryEvent> before = Map.copyOf(events);
        events.put(key("on_scene_end"), StoryEvent.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), JsonParser.parseString(
                "{ \"trigger\": { \"type\": \"scene_finished\", \"scene\": \"gametest:short\", \"reason\": \"finish\" },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:scene_finished\", \"amount\": 1 } }")).getOrThrow(GameTestAssertException::new));
        CoreDefinitions.EVENTS.serverStore().replace(events);
        StoryEvents.rebuild();
        withScene(helper, "short", ONE_SECOND, () -> {
            dev.dzenthai.storybound.module.scene.Scenes.play(player, key("short"));
            expect(helper, Scenes.watching(player), key("short"), "watching it");
            expect(helper, Scenes.lengthOf(player), 20, "a second long");
            helper.assertTrue(StoryState.getStored(player, StoryScope.PLAYER, Storybound.id("scene/pending")).isPresent(), "remembered while it plays");
        });
        helper.runAfterDelay(12, () -> expect(helper, stored(player, "scene_cue"), Optional.of(new StoryValue.Int(1)), "the cue at half a second"));
        helper.runAfterDelay(30, () -> {
            try {
                expect(helper, Scenes.watching(player), null, "over");
                expect(helper, stored(player, "scene_end"), Optional.of(new StoryValue.Int(1)), "on_end ran");
                expect(helper, stored(player, "scene_finished"), Optional.of(new StoryValue.Int(1)), "scene_finished fired");
                expect(helper, StoryState.getStored(player, StoryScope.PLAYER, Storybound.id("scene/pending")), Optional.empty(), "forgotten once over");
            } finally {
                CoreDefinitions.EVENTS.serverStore().replace(before);
                StoryEvents.rebuild();
                SceneRegistries.SCENES.serverStore().replace(Map.of());
                remove(helper, player);
            }
            helper.succeed();
        });
    }

    /**
     * A shot whose anchor is not near is left out; a scene with nothing left still ends (on_end runs at once). A scene
     * asked for while another plays waits for it.
     */
    @GameTest(template = "empty", batch = "scene_queue", timeoutTicks = 100)
    public static void missingAnchorsAreLeftOutAndScenesWaitTheirTurn(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        SceneRegistries.SCENES.serverStore().replace(Map.of(
                key("nobody"), parse(helper, "{ \"shots\": [ { \"anchor\": { \"entity\": { \"tag\": \"gametest_nobody_here\" } },"
                        + " \"camera\": { \"from\": [0, 2, 0], \"duration\": 3 } } ], \"on_end\": { \"type\": \"add_var\", \"key\": \"gametest:nobody_end\", \"amount\": 1 } }")
                        .getOrThrow(GameTestAssertException::new),
                key("first"), parse(helper, ONE_SECOND).getOrThrow(GameTestAssertException::new),
                key("second"), parse(helper, "{ \"shots\": [ { \"camera\": { \"from\": [0, 3, 0], \"duration\": 1 } } ] }").getOrThrow(GameTestAssertException::new)));
        Scenes.play(player, key("nobody"));
        expect(helper, Scenes.watching(player), null, "nothing to show: over at once");
        expect(helper, stored(player, "nobody_end"), Optional.of(new StoryValue.Int(1)), "and its on_end ran, so nothing waits for ever");
        Scenes.play(player, key("first"));
        Scenes.play(player, key("second"));
        expect(helper, Scenes.watching(player), key("first"), "the first plays");
        helper.runAfterDelay(25, () -> {
            try {
                expect(helper, Scenes.watching(player), key("second"), "the second after it");
            } finally {
                Scenes.clear();
                SceneRegistries.SCENES.serverStore().replace(Map.of());
                remove(helper, player);
            }
            helper.succeed();
        });
    }

    /**
     * While a scene plays nothing hurts the watcher and a mob near cannot take them or a villager as its target; after
     * it, both come back. A death (the void, /kill) cuts it short: no on_end.
     */
    @GameTest(template = "empty", batch = "scene_safe", timeoutTicks = 100)
    public static void theWatcherIsSafeAndTheMobsCalm(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 2, 1.5)));
        net.minecraft.world.entity.npc.Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 2, 2);
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 4, 2, 4);
        SceneRegistries.SCENES.serverStore().replace(Map.of(key("safe"), parse(helper,
                "{ \"shots\": [ { \"camera\": { \"from\": [0, 3, 0], \"duration\": 3 } } ], \"on_end\": { \"type\": \"add_var\", \"key\": \"gametest:safe_end\", \"amount\": 1 } }")
                .getOrThrow(GameTestAssertException::new)));
        try {
            Scenes.play(player, key("safe"));
            float health = player.getHealth();
            player.hurt(player.damageSources().generic(), 5);
            expect(helper, player.getHealth(), health, "not hurt while it plays");
            zombie.setTarget(player);
            expect(helper, zombie.getTarget(), null, "the zombie cannot go for the watcher");
            zombie.setTarget(villager);
            expect(helper, zombie.getTarget(), null, "nor for a villager near");
            player.kill();
            expect(helper, Scenes.watching(player), null, "/kill ends it");
            expect(helper, stored(player, "safe_end"), Optional.empty(), "cut short: no on_end");
            zombie.setTarget(villager);
            expect(helper, zombie.getTarget(), villager, "after it the world is as before");
        } finally {
            Scenes.clear();
            SceneRegistries.SCENES.serverStore().replace(Map.of());
            remove(helper, player);
        }
        helper.succeed();
    }

    /** A player who left mid-scene sees it again from the start when they come back; no scene_finished meanwhile. */
    @GameTest(template = "empty", batch = "scene_back", timeoutTicks = 100)
    public static void aSceneLeftUnfinishedPlaysAgainOnComingBack(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        SceneRegistries.SCENES.serverStore().replace(Map.of(key("again"), parse(helper, ONE_SECOND).getOrThrow(GameTestAssertException::new)));
        Scenes.play(player, key("again"));
        Scenes.leaveForTests(player);
        expect(helper, Scenes.watching(player), null, "gone with the player");
        helper.assertTrue(StoryState.getStored(player, StoryScope.PLAYER, Storybound.id("scene/pending")).isPresent(), "still remembered");
        Scenes.comeBackForTests(player);
        helper.runAfterDelay(2, () -> {
            try {
                expect(helper, Scenes.watching(player), key("again"), "back: it plays again");
                helper.assertTrue(Scenes.tickOf(player) <= 2, "from the start: " + Scenes.tickOf(player));
                expect(helper, stored(player, "scene_end"), Optional.empty(), "not finished meanwhile");
            } finally {
                Scenes.clear();
                SceneRegistries.SCENES.serverStore().replace(Map.of());
                remove(helper, player);
            }
            helper.succeed();
        });
    }

    private static void withScene(GameTestHelper helper, String name, String json, Runnable body) {
        SceneRegistries.SCENES.serverStore().replace(Map.of(key(name), parse(helper, json).getOrThrow(GameTestAssertException::new)));
        body.run();
    }

    private static DataResult<SceneDefinition> parse(GameTestHelper helper, String json) {
        return SceneDefinition.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), JsonParser.parseString(json));
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static Optional<StoryValue> stored(ServerPlayer player, String path) {
        return StoryState.getStored(player, StoryScope.PLAYER, key(path));
    }

    private static void remove(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }
}
