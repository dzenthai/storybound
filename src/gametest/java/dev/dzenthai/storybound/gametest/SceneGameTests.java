package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.scene.SceneDefinition;
import dev.dzenthai.storybound.module.scene.SceneRegistries;
import dev.dzenthai.storybound.module.scene.Scenes;
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
                        + " \"sound\": { \"sound\": \"minecraft:block.bell.use\" } } ] } ] }",
                "{ \"shots\": [ { \"camera\": { \"from\": [0, 1, 0], \"duration\": 2, \"fov\": 15 } } ] }",
                "{ \"shots\": [ { \"camera\": { \"from\": [0, 1, 0], \"duration\": 2, \"fov\": 130 } } ] }"}) {
            helper.assertTrue(parse(helper, wrong).isError(), "refused: " + wrong);
        }
        SceneDefinition speed = parse(helper, "{ \"shots\": [ { \"camera\": { \"from\": [0, 1, 0], \"to\": [12, 1, 0], \"speed\": 4 } } ] }")
                .getOrThrow(GameTestAssertException::new);
        helper.assertTrue(Math.abs(speed.shots().getFirst().camera().orElseThrow().seconds() - 3f) < 0.01f, "12 blocks at 4 a second: 3 seconds");
        SceneDefinition curved = parse(helper, """
                { "shots": [ { "camera": { "from": [0, 1, 0], "via": [[6, 5, 6], [12, 5, 6]], "to": [12, 1, 0], "speed": 4, "fov": 45 } } ] }
                """).getOrThrow(GameTestAssertException::new);
        SceneDefinition.Camera curvedCam = curved.shots().getFirst().camera().orElseThrow();
        helper.assertTrue(curvedCam.via().size() == 2 && Math.abs(curvedCam.fov().orElse(0f) - 45f) < 0.01f, "spline via points and custom fov loaded");
        helper.assertTrue(curvedCam.seconds() > 4.0f, "speed uses spline arc length, longer than straight 12-block chord (3s)");
        helper.assertTrue(parse(helper, "{ \"shots\": [ { \"dialogue\": \"gametest:talk\", \"camera\": { \"from\": [0, 1, 0], \"duration\": 1 } } ] }").isError(),
                "a shot is a flight or a talk, not both");
        SceneDefinition rich = parse(helper, """
                { "music": "minecraft:music.menu", "shots": [ { "dialogue": "gametest:talk" },
                  { "camera": { "from": [0, 1, 0], "duration": 1 }, "during": [
                    { "caption": { "text": { "literal": "Three days later" }, "position": "center", "black": true } },
                    { "at": 0.5, "music": "minecraft:music.game" } ] } ] }
                """).getOrThrow(GameTestAssertException::new);
        helper.assertTrue(rich.shots().getFirst().talk() && rich.music().isPresent()
                && rich.shots().get(1).during().getFirst().caption().map(caption -> caption.black()).orElse(false), "a talk, a caption on black, music");
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

    /** Skipping a scene ends it at once, running any remaining cue actions and its on_end. */
    @GameTest(template = "empty", batch = "scene_skip", timeoutTicks = 60)
    public static void aSkippedSceneRunsItsCueActionsAndEnds(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        SceneRegistries.SCENES.serverStore().replace(Map.of(key("skip_me"), parse(helper, ONE_SECOND).getOrThrow(GameTestAssertException::new)));
        try {
            Scenes.play(player, key("skip_me"));
            expect(helper, Scenes.watching(player), key("skip_me"), "watching it");
            Scenes.skip(player);
            expect(helper, Scenes.watching(player), null, "skipped at once");
            expect(helper, stored(player, "scene_cue"), Optional.of(new StoryValue.Int(1)), "its remaining cue action still ran");
            expect(helper, stored(player, "scene_end"), Optional.of(new StoryValue.Int(1)), "its on_end ran");
            expect(helper, StoryState.getStored(player, StoryScope.PLAYER, Storybound.id("scene/pending")), Optional.empty(), "forgotten once skipped");
        } finally {
            Scenes.clear();
            SceneRegistries.SCENES.serverStore().replace(Map.of());
            remove(helper, player);
        }
        helper.succeed();
    }

    /** A newer music cue on the timeline immediately switches the active scene track and clears on end. */
    @GameTest(template = "empty", batch = "scene_music_switch", timeoutTicks = 80)
    public static void aLaterMusicCueReplacesThePreviousTrack(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        String twoTracks = """
                { "shots": [ { "camera": { "from": [0, 3, -4], "to": [2, 3, -4], "duration": 1.2 },
                               "during": [
                                 { "at": 0, "music": "minecraft:music.menu" },
                                 { "at": 0.5, "music": "minecraft:music.game" }
                               ] } ] }
                """;
        SceneRegistries.SCENES.serverStore().replace(Map.of(key("music_switch"), parse(helper, twoTracks).getOrThrow(GameTestAssertException::new)));
        Scenes.play(player, key("music_switch"));
        expect(helper, Scenes.music(player), Optional.of(ResourceLocation.parse("minecraft:music.menu")), "first track starts at 0s");
        helper.runAfterDelay(12, () -> expect(helper, Scenes.music(player), Optional.of(ResourceLocation.parse("minecraft:music.game")),
                "second track replaces the first at 0.5s"));
        helper.runAfterDelay(32, () -> {
            try {
                expect(helper, Scenes.watching(player), null, "scene finished");
                expect(helper, Scenes.music(player), Optional.empty(), "scene music cleared on end");
            } finally {
                Scenes.clear();
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

    /**
     * A talk inside a scene: the dialogue starts (a scene's own talk is let through), the scene's clock waits while it
     * goes on, and once it ends the scene goes on to its end.
     */
    @GameTest(template = "empty", batch = "scene_talk", timeoutTicks = 120)
    public static void aSceneWaitsForItsTalk(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        var dialogues = dev.dzenthai.storybound.module.dialogue.DialogueRegistries.DIALOGUES.serverStore();
        Map<ResourceLocation, dev.dzenthai.storybound.module.dialogue.DialogueDefinition> before = Map.copyOf(dialogues.all());
        Map<ResourceLocation, dev.dzenthai.storybound.module.dialogue.DialogueDefinition> installed = new LinkedHashMap<>(before);
        installed.put(key("talk"), dev.dzenthai.storybound.module.dialogue.DialogueDefinition.CODEC.parse(
                RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), JsonParser.parseString(
                        "{ \"start\": \"a\", \"nodes\": { \"a\": { \"type\": \"line\", \"speaker\": \"player\", \"text\": { \"literal\": \"Hm.\" } } } }"))
                .getOrThrow(GameTestAssertException::new));
        dialogues.replace(installed);
        SceneRegistries.SCENES.serverStore().replace(Map.of(key("talking"), parse(helper, """
                { "shots": [ { "dialogue": "gametest:talk" }, { "camera": { "from": [0, 2, 0], "duration": 1 } } ],
                  "on_end": { "type": "add_var", "key": "gametest:talk_end", "amount": 1 } }
                """).getOrThrow(GameTestAssertException::new)));
        Scenes.play(player, key("talking"));
        expect(helper, Scenes.watching(player), key("talking"), "the scene plays");
        helper.assertTrue(dev.dzenthai.storybound.module.dialogue.Dialogues.state(player).isPresent(), "its talk started inside it");
        helper.runAfterDelay(30, () -> {
            expect(helper, Scenes.tickOf(player), 0, "the scene's clock waits while they talk");
            dev.dzenthai.storybound.module.dialogue.Dialogues.interrupt(player, dev.dzenthai.storybound.core.event.DialogueEvent.EndReason.STOPPED);
        });
        helper.runAfterDelay(70, () -> {
            try {
                expect(helper, Scenes.watching(player), null, "after the talk the scene went on to its end");
                expect(helper, stored(player, "talk_end"), Optional.of(new StoryValue.Int(1)), "and its on_end ran");
            } finally {
                Scenes.clear();
                SceneRegistries.SCENES.serverStore().replace(Map.of());
                dialogues.replace(before);
                remove(helper, player);
            }
            helper.succeed();
        });
    }

    /** A dialogue's scene node: the talk waits while the scene plays, then goes on at the node's next. */
    @GameTest(template = "empty", batch = "scene_in_talk", timeoutTicks = 120)
    public static void aDialogueWaitsForItsScene(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        var dialogues = dev.dzenthai.storybound.module.dialogue.DialogueRegistries.DIALOGUES.serverStore();
        Map<ResourceLocation, dev.dzenthai.storybound.module.dialogue.DialogueDefinition> before = Map.copyOf(dialogues.all());
        Map<ResourceLocation, dev.dzenthai.storybound.module.dialogue.DialogueDefinition> installed = new LinkedHashMap<>(before);
        installed.put(key("story"), dev.dzenthai.storybound.module.dialogue.DialogueDefinition.CODEC.parse(
                RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), JsonParser.parseString("""
                        { "start": "show", "nodes": {
                          "show": { "type": "scene", "scene": "gametest:memory", "next": "after" },
                          "after": { "type": "action", "actions": { "type": "add_var", "key": "gametest:after_scene", "amount": 1 }, "next": "line" },
                          "line": { "type": "line", "speaker": "player", "text": { "literal": "So that is how it was." } } } }
                        """)).getOrThrow(GameTestAssertException::new));
        dialogues.replace(installed);
        SceneRegistries.SCENES.serverStore().replace(Map.of(key("memory"), parse(helper,
                "{ \"shots\": [ { \"camera\": { \"from\": [0, 3, 0], \"duration\": 1 } } ] }").getOrThrow(GameTestAssertException::new)));
        helper.assertTrue(dev.dzenthai.storybound.module.dialogue.Dialogues.start(player, key("story"), null), "the talk starts");
        expect(helper, Scenes.watching(player), key("memory"), "its scene plays at once");
        expect(helper, stored(player, "after_scene"), Optional.empty(), "the talk waits for it");
        helper.runAfterDelay(30, () -> {
            try {
                expect(helper, Scenes.watching(player), null, "the scene is over");
                expect(helper, stored(player, "after_scene"), Optional.of(new StoryValue.Int(1)), "the talk went on after it");
                helper.assertTrue(dev.dzenthai.storybound.module.dialogue.Dialogues.state(player).map(state -> state.node().equals("line")).orElse(false),
                        "at its next line");
            } finally {
                dev.dzenthai.storybound.module.dialogue.Dialogues.interrupt(player, dev.dzenthai.storybound.core.event.DialogueEvent.EndReason.STOPPED);
                Scenes.clear();
                SceneRegistries.SCENES.serverStore().replace(Map.of());
                dialogues.replace(before);
                remove(helper, player);
            }
            helper.succeed();
        });
    }

    /**
     * What a scene names must exist: an NPC, a waypoint, a structure of the game, the dialogue of a talk, the scene a
     * scene plays. The report says which shot, so the author finds it.
     */
    @GameTest(template = "empty", batch = "scene_validate")
    public static void aSceneNamingWhatDoesNotExistIsReported(GameTestHelper helper) {
        ResourceLocation broken = key("broken_places");
        SceneRegistries.SCENES.serverStore().replace(Map.of(broken, parse(helper, """
                { "shots": [
                    { "anchor": { "npc": "gametest:no_such_npc" }, "camera": { "from": [0, 1, 0], "duration": 1 } },
                    { "anchor": { "waypoint": "gametest:no_such_waypoint" },
                      "camera": { "from": [0, 1, 0], "duration": 1, "look": { "npc": "gametest:other_missing_npc" } } },
                    { "anchor": { "structure": "gametest:no_such_structure" }, "camera": { "from": [0, 1, 0], "duration": 1 } },
                    { "dialogue": "gametest:no_such_dialogue" } ],
                  "on_end": { "type": "play_scene", "scene": "gametest:no_such_scene" } }
                """).getOrThrow(GameTestAssertException::new)));
        try {
            List<String> errors = ValidationService.validate().issues().stream()
                    .filter(issue -> broken.equals(issue.entry())).map(ValidationIssue::message).toList();
            for (String expected : new String[] {"Shot 1 is placed against unknown NPC gametest:no_such_npc",
                    "Shot 2 is placed against unknown waypoint gametest:no_such_waypoint",
                    "Shot 2 looks at unknown NPC gametest:other_missing_npc",
                    "Shot 3 is placed against unknown structure gametest:no_such_structure",
                    "Unknown dialogue gametest:no_such_dialogue", "Unknown scene gametest:no_such_scene"}) {
                helper.assertTrue(errors.contains(expected), "reported: " + expected + ", got " + errors);
            }
        } finally {
            SceneRegistries.SCENES.serverStore().replace(Map.of());
            ValidationService.validate();
        }
        helper.succeed();
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
