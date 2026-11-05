package dev.dzenthai.storybound.gametest;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.module.quest.Quests;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The debug scenarios (November 5, 2026), loaded as if installed (the build copies them into the GameTest story
 * folder): {@code /storybound debug scenario <name>} fires {@code scenario:<name>}, and each sets its scene; Brann's
 * story pauses for a fight and goes on with "Where was I".
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScenarioGameTests {
    /** How each player's dialogues ended, for the failure messages. */
    private static final java.util.Map<java.util.UUID, String> ENDED = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((dev.dzenthai.storybound.core.event.DialogueEvent.Ended event) ->
                ENDED.merge(event.getPlayer().getUUID(), event.getDialogue().getPath() + "@" + event.getNode() + ":" + event.getReason(), (a, b) -> a + ", " + b));
    }

    private ScenarioGameTests() {}

    @GameTest(template = "open", batch = "scenario_scenes", timeoutTicks = 200)
    public static void eachScenarioSetsItsScene(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    fire(player, "campfire");
                    fire(player, "last_stand");
                    expect(helper, tagged(helper, player, EntityType.IRON_GOLEM, "scenario_brann").size(), 1, "campfire: Brann the golem");
                    expect(helper, tagged(helper, player, EntityType.VILLAGER, "scenario_tikhon").size(), 1, "last_stand: Tikhon");
                    expect(helper, status(player, "campfire"), Optional.of(QuestStatus.ACTIVE), "campfire's quest");
                    expect(helper, status(player, "last_stand"), Optional.of(QuestStatus.ACTIVE), "last_stand's quest");
                    fire(player, "campfire");
                    expect(helper, tagged(helper, player, EntityType.IRON_GOLEM, "scenario_brann").size(), 1, "run again: still one Brann");
                    fire(player, "ambush");
                    expect(helper, tagged(helper, player, EntityType.VILLAGER, "scenario_mira").size(), 1, "ambush: Mira");
                    expect(helper, tagged(helper, player, EntityType.HUSK, "scenario_ambush_husk").size(), 3, "and the husks");
                    // Gone at once: husks go for villagers and golems, a fight for the NPC of a test beside this one.
                    tagged(helper, player, EntityType.HUSK, "scenario_ambush_husk").forEach(Entity::discard);
                })
                .thenWaitUntil(() -> expect(helper, Dialogues.state(player).map(Dialogues.State::dialogue), Optional.of(id("ambush/story")),
                        "out of a fight (the mock player is not targeted), Mira's story starts"))
                .thenExecute(() -> done(helper, player))
                .thenSucceed();
    }

    @GameTest(template = "open", batch = "scenario_brann", timeoutTicks = 400)
    public static void bransStoryPausesForTheFightAndGoesOn(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        // A batch of its own: Brann is unique in the level, and the other test's clean-up would take him away.
        Entity[] brann = new Entity[1];
        helper.startSequence()
                .thenExecute(() -> {
                    fire(player, "campfire");
                    brann[0] = tagged(helper, player, EntityType.IRON_GOLEM, "scenario_brann").getFirst();
                    helper.assertTrue(Dialogues.start(player, id("campfire/story"), brann[0]), "Brann starts his story");
                    advance(player);
                    advance(player);
                    expect(helper, node(player), "l3", "the husks come at the third line");
                    List<Husk> husks = tagged(helper, player, EntityType.HUSK, "scenario_campfire_husk");
                    expect(helper, husks.size(), 3, "three husks");
                    husks.getFirst().hurt(player.damageSources().playerAttack(player), 1f);
                    // The fight is the hit alone (husks call others for help, as zombies do).
                    helper.getLevel().getEntitiesOfClass(Husk.class, player.getBoundingBox().inflate(32)).forEach(Entity::discard);
                    expect(helper, Dialogues.paused(player).map(Dialogues.Paused::node), Optional.of("l3"), "a hit: the story pauses");
                })
                .thenWaitUntil(() -> expect(helper, node(player), "where", "four quiet seconds later: \"So. Where was I...\""))
                .thenExecute(() -> {
                    advance(player);
                    expect(helper, node(player), "l3", "the line again");
                    advance(player);
                    expect(helper, node(player), "l4", "the story goes on");
                })
                .thenExecute(() -> done(helper, player))
                .thenSucceed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        return player;
    }

    private static void fire(ServerPlayer player, String scenario) {
        TriggerDispatcher.fireCustom(player, ResourceLocation.fromNamespaceAndPath("scenario", scenario));
    }

    private static <T extends Entity> List<T> tagged(GameTestHelper helper, ServerPlayer player, EntityType<T> type, String tag) {
        return helper.getLevel().getEntities(type, new AABB(player.blockPosition()).inflate(32), entity -> entity.getTags().contains(tag));
    }

    private static Optional<QuestStatus> status(ServerPlayer player, String scenario) {
        return Quests.progress(player, id(scenario + "/quest")).map(QuestProgress::status);
    }

    private static void done(GameTestHelper helper, ServerPlayer player) {
        Dialogues.interrupt(player, EndReason.STOPPED);
        for (String tag : List.of("scenario_brann", "scenario_tikhon", "scenario_mira", "scenario_ambush_husk", "scenario_campfire_husk")) {
            helper.getLevel().getEntities((Entity) null, new AABB(player.blockPosition()).inflate(32), entity -> entity.getTags().contains(tag))
                    .forEach(Entity::discard);
        }
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    private static String node(ServerPlayer player) {
        return Dialogues.state(player).map(Dialogues.State::node).orElse("(none; paused: " + Dialogues.paused(player) + "; ended: " + ENDED.get(player.getUUID()) + ")");
    }

    private static void advance(ServerPlayer player) {
        Dialogues.State state = Dialogues.state(player).orElseThrow(() -> new GameTestAssertException("no line to move past"));
        if (!Dialogues.advance(player, state.session(), state.step())) {
            throw new GameTestAssertException("advance was refused at " + state);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("scenario", path);
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }
}
