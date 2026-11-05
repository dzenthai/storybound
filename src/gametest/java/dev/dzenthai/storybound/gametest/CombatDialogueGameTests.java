package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.WorldStoryData;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
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
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Dialogues in a fight (November 5, 2026): a story told in subtitles pauses while the player fights and goes on after
 * it, with "Where was I" first; a choice comes back; a dialogue started in a fight waits for it; callouts play over a
 * paused one; a long fight gives up; the modes continue and stop; the dialogue screen is left alone; the NPC far away
 * or gone. Each test runs on real server ticks, with {@code quiet} at 1 second. They have a batch of their own: zombies of other
 * tests go for villagers, and a mob after the NPC talked to is a fight.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatDialogueGameTests {
    /** A story in subtitles a fight pauses; %1$s prefixes its counter. "b" has a resume of its own. */
    private static final String STORY_JSON = """
            { "presentation": "hud", "npc_stops": false,
              "combat": { "mode": "pause", "resume": "where", "quiet": 1, "forget_after": 5 },
              "on_abort": { "type": "add_var", "key": "gametest:%1$s_aborts", "scope": "world", "amount": 1 },
              "start": "a",
              "nodes": {
                "a": { "type": "line", "speaker": "npc", "text": "gametest.a", "next": "b" },
                "b": { "type": "line", "speaker": "npc", "text": "gametest.b", "next": "ask", "resume": "b_where" },
                "ask": { "type": "choice", "options": [ { "text": "gametest.yes" } ] },
                "where": { "type": "line", "speaker": "npc", "text": "gametest.where" },
                "b_where": { "type": "line", "speaker": "npc", "text": "gametest.b_where" }
              } }
            """;
    private static final String CALLOUT_JSON = """
            { "presentation": "hud", "start": "a", "nodes": { "a": { "type": "line", "speaker": "npc", "text": "gametest.left" } } }
            """;
    private static final String STOP_JSON = """
            { "presentation": "bubble", "combat": { "mode": "stop" },
              "on_abort": { "type": "add_var", "key": "gametest:%1$s_stops", "scope": "world", "amount": 1 },
              "start": "a", "nodes": { "a": { "type": "line", "speaker": "npc", "text": "gametest.a" } } }
            """;
    private static final String SCREEN_JSON = """
            { "combat": { "mode": "pause" }, "start": "a", "nodes": { "a": { "type": "line", "speaker": "npc", "text": "gametest.a" } } }
            """;

    /** How each player's dialogues ended, for the failure messages. */
    private static final Map<java.util.UUID, String> ENDED = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((dev.dzenthai.storybound.core.event.DialogueEvent.Ended event) ->
                ENDED.merge(event.getPlayer().getUUID(), event.getDialogue().getPath() + "@" + event.getNode() + ":" + event.getReason(), (a, b) -> a + ", " + b));
    }

    private CombatDialogueGameTests() {}

    @GameTest(template = "empty", batch = "combat_dialogues", timeoutTicks = 400)
    public static void aFightPausesTheStoryAndItGoesOnAfter(GameTestHelper helper) {
        Fixture f = fixture(helper, "pause");
        helper.startSequence()
                .thenExecute(() -> {
                    Dialogues.start(f.player, f.story, f.npc);
                    expect(helper, node(f.player), "a", "the story starts");
                    hit(f);
                    expect(helper, Dialogues.state(f.player), Optional.empty(), "a hit: the subtitles go away");
                    expect(helper, Dialogues.paused(f.player).map(Dialogues.Paused::node), Optional.of("a"), "paused at its line");
                    expect(helper, Dialogues.start(f.player, f.callout, f.npc), true, "a callout plays over the paused story");
                    expect(helper, node(f.player), "a", "the callout's line");
                    helper.assertTrue(Dialogues.end(f.player), "the callout ends");
                })
                .thenIdle(10)
                .thenExecute(() -> expect(helper, Dialogues.paused(f.player).isPresent(), true, "half a second after the hit: still a fight"))
                .thenWaitUntil(() -> expect(helper, node(f.player), "where", "a second of quiet: \"Where was I\" first"))
                .thenExecute(() -> {
                    advance(f.player);
                    expect(helper, node(f.player), "a", "then the line the fight took away, again");
                    advance(f.player);
                    expect(helper, node(f.player), "b", "and the story goes on");
                    expect(helper, counter(helper, "pause_aborts"), 0, "nothing was interrupted");
                })
                .thenExecute(() -> done(helper, f))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "combat_dialogues", timeoutTicks = 400)
    public static void aLinesOwnResumeAndAChoiceComeBack(GameTestHelper helper) {
        Fixture f = fixture(helper, "choice");
        helper.startSequence()
                .thenExecute(() -> {
                    Dialogues.start(f.player, f.story, f.npc);
                    advance(f.player);
                    expect(helper, node(f.player), "b", "on the line with its own resume");
                    hit(f);
                })
                .thenWaitUntil(() -> expect(helper, node(f.player), "b_where", "its own resume, not the dialogue's"))
                .thenExecute(() -> {
                    advance(f.player);
                    expect(helper, node(f.player), "b", "back to its line");
                    advance(f.player);
                    helper.assertTrue(waitsForChoice(f.player), "the choice");
                    hit(f);
                    expect(helper, Dialogues.paused(f.player).map(Dialogues.Paused::node), Optional.of("ask"), "a hit takes the choice away");
                })
                .thenWaitUntil(() -> expect(helper, node(f.player), "where", "after the fight, the dialogue's resume"))
                .thenExecute(() -> {
                    // Paused again in the middle of "Where was I".
                    hit(f);
                    expect(helper, Dialogues.paused(f.player).map(Dialogues.Paused::node), Optional.of("where"), "paused in the resume");
                })
                .thenWaitUntil(() -> expect(helper, node(f.player), "where", "the resume again, without another one before it"))
                .thenExecute(() -> {
                    advance(f.player);
                    helper.assertTrue(waitsForChoice(f.player), "then the choice comes back");
                })
                .thenExecute(() -> done(helper, f))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "combat_dialogues", timeoutTicks = 400)
    public static void aStoryStartedInAFightWaitsForIt(GameTestHelper helper) {
        Fixture f = fixture(helper, "late");
        helper.startSequence()
                .thenExecute(() -> {
                    hit(f);
                    expect(helper, Dialogues.start(f.player, f.story, f.npc), true, "started in a fight");
                    expect(helper, Dialogues.state(f.player), Optional.empty(), "nothing is shown");
                    expect(helper, Dialogues.paused(f.player).map(Dialogues.Paused::notStarted), Optional.of(true), "it waits, not started");
                    expect(helper, Dialogues.start(f.player, f.story, f.npc), false, "the same one does not start twice");
                    expect(helper, Dialogues.startOrQueue(f.player, f.story, f.npc), true, "nor queued");
                    expect(helper, Dialogues.queued(f.player), List.of(), "the queue stays empty");
                    expect(helper, Dialogues.start(f.player, f.other, f.npc), false, "another one a fight pauses cannot start meanwhile");
                    expect(helper, Dialogues.startOrQueue(f.player, f.other, f.npc), true, "it is queued");
                })
                .thenWaitUntil(() -> expect(helper, node(f.player), "a", "the fight over: from its first line, no resume"))
                .thenExecute(() -> {
                    advance(f.player);
                    advance(f.player);
                    choose(f.player);
                    expect(helper, Dialogues.state(f.player), Optional.empty(), "the story is over");
                })
                .thenWaitUntil(() -> expect(helper, Dialogues.state(f.player).map(Dialogues.State::dialogue), Optional.of(f.other), "then the queued one starts"))
                .thenExecute(() -> done(helper, f))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "combat_dialogues", timeoutTicks = 400)
    public static void aLongFightGivesUp(GameTestHelper helper) {
        Fixture f = fixture(helper, "long");
        helper.startSequence()
                .thenExecute(() -> {
                    Dialogues.start(f.player, f.story, f.npc);
                    // A zombie after the player is a fight, with no hit.
                    f.zombie.setTarget(f.player);
                })
                .thenWaitUntil(() -> expect(helper, Dialogues.paused(f.player).isPresent(), true, "a mob targeting the player pauses it"))
                .thenIdle(60)
                .thenExecute(() -> expect(helper, Dialogues.paused(f.player).isPresent(), true, "three seconds on: still waiting"))
                .thenWaitUntil(() -> expect(helper, counter(helper, "long_aborts"), 1, "past forget_after (5 s): interrupted, on_abort ran"))
                .thenExecute(() -> {
                    expect(helper, Dialogues.paused(f.player), Optional.empty(), "nothing waits any more");
                    expect(helper, Dialogues.state(f.player), Optional.empty(), "nothing is shown");
                })
                .thenExecute(() -> done(helper, f))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "combat_dialogues", timeoutTicks = 200)
    public static void continueStopAndTheScreen(GameTestHelper helper) {
        Fixture f = fixture(helper, "modes");
        helper.startSequence()
                .thenExecute(() -> {
                    Dialogues.start(f.player, f.callout, f.npc);
                    hit(f);
                    expect(helper, node(f.player), "a", "continue (the default): a callout goes on in a fight");
                    Dialogues.interrupt(f.player, EndReason.STOPPED);

                    Dialogues.start(f.player, f.stopper, f.npc);
                    hit(f);
                    expect(helper, Dialogues.state(f.player), Optional.empty(), "stop: a hit interrupts it");
                    expect(helper, counter(helper, "modes_stops"), 1, "on_abort ran");

                    Dialogues.start(f.player, f.screen, f.npc);
                    hit(f);
                    expect(helper, node(f.player), "a", "on the dialogue screen pause does nothing");
                    expect(helper, Dialogues.paused(f.player), Optional.empty(), "nothing is paused");
                })
                .thenExecute(() -> done(helper, f))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "combat_dialogues", timeoutTicks = 400)
    public static void theNpcAwayOrGoneDuringAPause(GameTestHelper helper) {
        Fixture f = fixture(helper, "away");
        Vec3 home = f.npc.position();
        helper.startSequence()
                .thenExecute(() -> {
                    Dialogues.start(f.player, f.story, f.npc);
                    hit(f);
                    // The companion ran off chasing something, past the 8 blocks a dialogue allows: no "too far" while paused.
                    // (Not much farther: the GameTest world does not load chunks far off, which would unload the NPC.)
                    f.npc.teleportTo(home.x + 10, home.y, home.z);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    expect(helper, counter(helper, "away_aborts"), 0, "not interrupted while away in a fight");
                    expect(helper, Dialogues.paused(f.player).isPresent(), true, "the fight over, it waits for the NPC to come back");
                    f.npc.teleportTo(home.x, home.y, home.z);
                })
                .thenWaitUntil(() -> expect(helper, node(f.player), "where", "back near: it goes on"))
                .thenExecute(() -> {
                    hit(f);
                    f.npc.kill();
                })
                .thenWaitUntil(() -> expect(helper, counter(helper, "away_aborts"), 1, "the NPC died during a pause: interrupted, on_abort ran"))
                .thenExecute(() -> expect(helper, Dialogues.paused(f.player), Optional.empty(), "nothing waits"))
                .thenExecute(() -> done(helper, f))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "combat_dialogues", timeoutTicks = 200)
    public static void dyingOrLeavingInterruptsAPausedStory(GameTestHelper helper) {
        Fixture f = fixture(helper, "died");
        helper.startSequence()
                .thenExecute(() -> {
                    Dialogues.start(f.player, f.story, f.npc);
                    hit(f);
                    Dialogues.start(f.player, f.callout, f.npc);
                    // As a death or a logout does.
                    helper.assertTrue(Dialogues.interrupt(f.player, EndReason.DIED), "interrupted");
                    expect(helper, Dialogues.state(f.player), Optional.empty(), "the callout is gone");
                    expect(helper, Dialogues.paused(f.player), Optional.empty(), "and the paused story");
                    expect(helper, counter(helper, "died_aborts"), 1, "its on_abort ran");
                })
                .thenExecute(() -> done(helper, f))
                .thenSucceed();
    }

    /** The player, the NPC they talk to, a zombie to fight, and this test's own dialogues. */
    private record Fixture(ServerPlayer player, Villager npc, Zombie zombie, ResourceLocation story, ResourceLocation other,
                           ResourceLocation callout, ResourceLocation stopper, ResourceLocation screen) {}

    private static Fixture fixture(GameTestHelper helper, String prefix) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 3, 2, 3);
        // No AI at all: without free will a zombie still picks targets, and one going for the player is a fight.
        zombie.setNoAi(true);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new Vec3(2.5, 2, 1.5)));
        Fixture f = new Fixture(player, npc, zombie, key(prefix + "_story"), key(prefix + "_other"), key(prefix + "_callout"),
                key(prefix + "_stopper"), key(prefix + "_screen"));
        // Counters as key() names them: gametest:combat_<prefix>_aborts.
        String counters = "combat_" + prefix;
        install(helper, Map.of(f.story, STORY_JSON.formatted(counters), f.other, STORY_JSON.formatted(counters + "_other"),
                f.callout, CALLOUT_JSON, f.stopper, STOP_JSON.formatted(counters), f.screen, SCREEN_JSON));
        return f;
    }

    /**
     * The player hits the zombie: both are in a fight. The fight is the hit alone: the zombie's target is taken back and
     * any zombie it called for help (vanilla reinforcements, which come after the player) is removed.
     */
    private static void hit(Fixture f) {
        f.zombie.invulnerableTime = 0;
        f.zombie.hurt(f.player.damageSources().playerAttack(f.player), 1f);
        f.zombie.setTarget(null);
        f.player.level().getEntitiesOfClass(Zombie.class, f.player.getBoundingBox().inflate(32), zombie -> zombie != f.zombie && zombie.getTarget() == f.player)
                .forEach(Zombie::discard);
    }

    private static void install(GameTestHelper helper, Map<ResourceLocation, String> definitions) {
        DefinitionStore<DialogueDefinition> store = DialogueRegistries.DIALOGUES.serverStore();
        Map<ResourceLocation, DialogueDefinition> installed = new LinkedHashMap<>(store.all());
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        definitions.forEach((id, json) -> installed.put(id, DialogueDefinition.CODEC.parse(ops, JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new)));
        store.replace(installed);
    }

    /** Takes out only this test's dialogues: others running in parallel keep theirs. */
    private static void done(GameTestHelper helper, Fixture f) {
        Dialogues.interrupt(f.player, EndReason.STOPPED);
        DefinitionStore<DialogueDefinition> store = DialogueRegistries.DIALOGUES.serverStore();
        Map<ResourceLocation, DialogueDefinition> rest = new LinkedHashMap<>(store.all());
        rest.keySet().removeAll(List.of(f.story, f.other, f.callout, f.stopper, f.screen));
        store.replace(rest);
        helper.getLevel().getServer().getPlayerList().remove(f.player);
    }

    private static String node(ServerPlayer player) {
        return Dialogues.state(player).map(Dialogues.State::node).orElse("(none; paused: " + Dialogues.paused(player) + "; ended: " + ENDED.get(player.getUUID()) + ")");
    }

    private static boolean waitsForChoice(ServerPlayer player) {
        return Dialogues.state(player).map(Dialogues.State::waitingForChoice).orElse(false);
    }

    private static void advance(ServerPlayer player) {
        Dialogues.State state = Dialogues.state(player).orElseThrow(() -> new GameTestAssertException("no line to move past"));
        if (!Dialogues.advance(player, state.session(), state.step())) {
            throw new GameTestAssertException("advance was refused at " + state);
        }
    }

    private static void choose(ServerPlayer player) {
        Dialogues.State state = Dialogues.state(player).orElseThrow(() -> new GameTestAssertException("no choice"));
        if (!Dialogues.choose(player, state.session(), state.step(), 0)) {
            throw new GameTestAssertException("the option was refused at " + state);
        }
    }

    private static int counter(GameTestHelper helper, String path) {
        return WorldStoryData.get(helper.getLevel().getServer()).container().get(key(path))
                .map(value -> value instanceof StoryValue.Int number ? number.value() : -1).orElse(0);
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", "combat_" + path);
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }
}
