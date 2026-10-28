package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.state.PartyManager;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.module.boss.BossEvents;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import dev.dzenthai.storybound.module.dialogue.NpcRespawns;
import dev.dzenthai.storybound.module.document.Documents;
import dev.dzenthai.storybound.module.quest.QuestEvents;
import dev.dzenthai.storybound.module.quest.QuestRewards;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.module.waypoint.StructureLocator;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * The demo story as shipped (the GameTest story folder is a copy of the starter files), played through its own
 * triggers, dialogues and scenes while things go wrong on the way: leaving mid-monologue, the elder killed mid-talk,
 * a quest reset and replayed, a party join, an idle player, a golem stealing the boss, a reload, a full bag, a short
 * sack, a search run out of time. Each test runs in a batch of its own: the demo's NPCs are unique in the whole level.
 * Only ids of quests, stages, nodes, options and tags are relied on, never texts. A world-dependent step (entering a
 * village) is stood in for by the action an author would use, {@code set_quest_stage}.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DemoGameTests {
    private static final ResourceLocation SMOKE = demo("smoke_on_the_horizon");
    private static final ResourceLocation NIGHT = demo("night_watch");
    private static final ResourceLocation HARVEST = demo("harvest");
    private static final ResourceLocation MURKA = demo("murka");
    private static final ResourceLocation FESTIVAL = demo("festival");
    private static final List<String> DEMO_TAGS = List.of("demo_elder", "demo_murka", "demo_murka_home", "demo_reaper", "demo_horde", "demo_new");
    private static final int TIMEOUT = 3000;

    private DemoGameTests() {}

    /** Leaving while the whisper plays still gives the first quest, and the whisper does not come again on joining. */
    @GameTest(template = "empty", batch = "demo_whisper", timeoutTicks = TIMEOUT)
    public static void leavingDuringTheWhisperStillGivesTheFirstQuest(GameTestHelper helper) {
        cleanUp(helper);
        // Joining (the mock player is placed like a real one) runs demo:awakening: 7 s later the whisper starts.
        ServerPlayer player = newPlayer(helper, 1, 2, 1);
        helper.startSequence()
                .thenWaitUntil(() -> inDialogue(player, "awakening"))
                .thenExecute(() -> {
                    NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
                    expect(helper, Dialogues.state(player), Optional.empty(), "the whisper ends with the player gone");
                    expect(helper, stage(player, SMOKE), Optional.of("road"), "its on_abort gives the quest");
                    NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
                })
                .thenIdle(200)
                .thenExecute(() -> {
                    expect(helper, Dialogues.state(player), Optional.empty(), "the whisper is once per player");
                    expect(helper, stage(player, SMOKE), Optional.of("road"), "the quest is where it was");
                    remove(helper, player);
                })
                .thenSucceed();
    }

    /**
     * The arrival brings the elder once; killed mid-meeting he comes back and the meeting can still be had; a reset and
     * replayed arrival brings no second elder; a friend joining the player's party changes nothing of their own quests.
     */
    @GameTest(template = "open", batch = "demo_meeting", timeoutTicks = TIMEOUT)
    public static void theElderComesOnceComesBackAndTheMeetingStillHappens(GameTestHelper helper) {
        cleanUp(helper);
        ServerPlayer player = newPlayer(helper, 6, 2, 6);
        Entity[] first = new Entity[1];
        arrive(helper, player)
                .thenWaitUntil(() -> inDialogue(player, "elder_meeting"))
                .thenExecute(() -> {
                    first[0] = elders(helper).getFirst();
                    first[0].kill();
                })
                .thenExecuteAfter(2, () -> {
                    expect(helper, Dialogues.state(player), Optional.empty(), "the meeting ends with the elder dead");
                    expect(helper, stage(player, SMOKE), Optional.of("meeting"), "and the quest waits for it");
                })
                .thenWaitUntil(() -> helper.assertTrue(elders(helper).size() == 1, "the elder comes back"))
                .thenExecute(() -> {
                    Entity elder = elders(helper).getFirst();
                    expect(helper, elder.getUUID(), first[0].getUUID(), "the same elder");
                    interact(player, elder);
                    inDialogue(player, "elder_meeting");
                    pick(player, "smoke");
                    pick(player, "help");
                    talkToEnd(player);
                    expect(helper, status(player, SMOKE), Optional.of(QuestStatus.COMPLETED), "the meeting completes the first quest");
                    expect(helper, stage(player, NIGHT), Optional.of("prepare"), "and the help offered starts the next");
                    // Reset and replayed (an author testing, an admin command): the scene runs again.
                    run(helper, player, "{ \"type\": \"set_quest_state\", \"quest\": \"demo:smoke_on_the_horizon\", \"state\": \"unknown\" }");
                    run(helper, player, "{ \"type\": \"set_quest_state\", \"quest\": \"demo:smoke_on_the_horizon\", \"state\": \"active\" }");
                    run(helper, player, "{ \"type\": \"set_quest_stage\", \"quest\": \"demo:smoke_on_the_horizon\", \"stage\": \"meeting\" }");
                })
                .thenIdle(120)
                .thenExecute(() -> {
                    expect(helper, elders(helper).size(), 1, "the replayed arrival brings no second elder");
                    ServerPlayer friend = newPlayer(helper, 5, 2, 5);
                    ok(() -> PartyManager.create(player, "Demo party"));
                    ok(() -> PartyManager.invite(player, friend));
                    ok(() -> PartyManager.accept(friend));
                    expect(helper, stage(player, NIGHT), Optional.of("prepare"), "the demo's quests are personal: joining moves nothing");
                    expect(helper, status(friend, NIGHT), Optional.empty(), "and gives the friend nothing of them");
                    ok(() -> PartyManager.disband(player));
                    remove(helper, friend);
                    remove(helper, player);
                    cleanUp(helper);
                })
                .thenSucceed();
    }

    /**
     * Night Watch with a player who lets the night scene's choice run out, a golem that kills the boss before the player
     * does, a reload in the middle of the fight and a bag too full for the reward.
     */
    @GameTest(template = "open", batch = "demo_night", timeoutTicks = TIMEOUT)
    public static void theNightWatchSurvivesAnIdlePlayerAGolemAReloadAndAFullBag(GameTestHelper helper) {
        cleanUp(helper);
        ServerLevel level = helper.getLevel();
        long dayTime = level.getDayTime();
        ServerPlayer player = newPlayer(helper, 6, 2, 6);
        arrive(helper, player)
                .thenWaitUntil(() -> inDialogue(player, "elder_meeting"))
                .thenExecute(() -> {
                    pick(player, "smoke");
                    pick(player, "help");
                    talkToEnd(player);
                    expect(helper, stage(player, NIGHT), Optional.of("prepare"), "the watch begins");
                    player.getInventory().add(new ItemStack(Items.TORCH, 8));
                    helper.assertTrue(Documents.read(player, demo("watchman_journal"), false), "the watchman's journal");
                })
                .thenWaitUntil(() -> expect(helper, stage(player, NIGHT), Optional.of("dusk"), "torches and the journal: on to dusk"))
                .thenExecute(() -> {
                    level.setDayTime(6000);
                    interact(player, elders(helper).getFirst());
                    inDialogue(player, "elder_not_yet");
                    talkToEnd(player);
                    level.setDayTime(18000);
                    interact(player, elders(helper).getFirst());
                    inDialogue(player, "elder_night");
                    helper.assertTrue(talkOn(player), "the night scene asks what to do");
                })
                // The player does nothing: after 8 s the scene goes on by itself.
                .thenWaitUntil(() -> expect(helper, Dialogues.state(player).map(Dialogues.State::node), Optional.of("frozen"), "the choice ran out"))
                .thenExecute(() -> {
                    talkToEnd(player);
                    level.setWeatherParameters(6000, 0, false, false);
                    expect(helper, stage(player, NIGHT), Optional.of("fight"), "the fight begins");
                    expect(helper, tagged(helper, EntityType.ZOMBIE, "demo_reaper").size(), 1, "the reaper is summoned");
                })
                .thenWaitUntil(() -> inDialogue(player, "reaper_sighted"))
                .thenExecute(() -> {
                    talkToEnd(player);
                    // A golem gets him first: no player's kill, so the boss objective is not counted.
                    tagged(helper, EntityType.ZOMBIE, "demo_reaper").forEach(Entity::kill);
                })
                .thenExecuteAfter(2, () -> {
                    expect(helper, stage(player, NIGHT), Optional.of("fight"), "a kill nobody can claim does not count");
                    interact(player, elders(helper).getFirst());
                    inDialogue(player, "elder");
                    pick(player, "reaper_lost");
                    talkToEnd(player);
                    expect(helper, tagged(helper, EntityType.ZOMBIE, "demo_reaper").size(), 1, "the elder calls the reaper again");
                    // A reload in the middle of the fight: the subscriptions are built anew.
                    QuestEvents.rebuild();
                    StoryEvents.rebuild();
                    NpcBindings.rebuild();
                    BossEvents.rebuild();
                    for (Entity zombie : tagged(helper, EntityType.ZOMBIE, null)) {
                        if (zombie.getTags().contains("demo_reaper") || zombie.getTags().contains("demo_horde")) {
                            zombie.hurt(player.damageSources().playerAttack(player), 1000f);
                        }
                    }
                })
                .thenWaitUntil(() -> expect(helper, stage(player, NIGHT), Optional.of("report"), "the reaper and the horde are down"))
                .thenExecute(() -> {
                    level.setDayTime(dayTime);
                    interact(player, elders(helper).getFirst());
                    pick(player, "reaper_report");
                    talkToEnd(player);
                    expect(helper, status(player, NIGHT), Optional.of(QuestStatus.COMPLETED), "the report completes the watch");
                    level.setWeatherParameters(6000, 0, false, false);
                    for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
                        player.getInventory().items.set(slot, new ItemStack(Items.STONE, 64));
                    }
                    helper.assertTrue(QuestRewards.claim(player, NIGHT, 0) != null, "a full bag: the reward waits");
                    player.getInventory().clearContent();
                    helper.assertTrue(QuestRewards.claim(player, NIGHT, 0) == null, "room made: claimed");
                    expect(helper, player.getInventory().countItem(Items.IRON_SWORD), 1, "the sword chosen");
                    helper.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 5, "and the emeralds (the rolled loot may add more)");
                    remove(helper, player);
                    cleanUp(helper);
                })
                .thenSucceed();
    }

    /**
     * The contract with a sack too light, then full; Murka offered, lost to the time limit and offered again; her return;
     * the festival that follows, at the bell after dark.
     */
    @GameTest(template = "open", batch = "demo_village", timeoutTicks = TIMEOUT)
    public static void theVillageQuestsSurviveAShortSackAndASearchRunOutOfTime(GameTestHelper helper) {
        cleanUp(helper);
        ServerLevel level = helper.getLevel();
        long dayTime = level.getDayTime();
        BlockPos bell = helper.absolutePos(new BlockPos(3, 2, 3));
        ServerPlayer player = newPlayer(helper, 6, 2, 6);
        Vec3 home = player.position();
        arrive(helper, player)
                .thenWaitUntil(() -> inDialogue(player, "elder_meeting"))
                .thenExecute(() -> {
                    pick(player, "smoke");
                    pick(player, "help");
                    talkToEnd(player);
                    run(helper, player, "{ \"type\": \"set_quest_state\", \"quest\": \"demo:night_watch\", \"state\": \"completed\" }");
                    level.setWeatherParameters(6000, 0, false, false);
                    helper.assertTrue(Documents.read(player, demo("harvest_notice"), false), "the contract notice the watch gave");
                    expect(helper, stage(player, HARVEST), Optional.of("deliver"), "reading it takes the contract");
                    // A sack too light: nothing is taken, the contract waits.
                    player.getInventory().add(new ItemStack(Items.WHEAT, 5));
                    interact(player, elders(helper).getFirst());
                    pick(player, "harvest");
                    helper.assertTrue(talkOn(player), "short: back to the topics");
                    expect(helper, player.getInventory().countItem(Items.WHEAT), 5, "nothing taken");
                    pick(player, "bye");
                    talkToEnd(player);
                    expect(helper, status(player, HARVEST), Optional.of(QuestStatus.ACTIVE), "the contract still open");
                    player.getInventory().add(new ItemStack(Items.WHEAT, 7));
                    interact(player, elders(helper).getFirst());
                    pick(player, "harvest");
                    talkToEnd(player);
                    expect(helper, player.getInventory().countItem(Items.WHEAT), 0, "twelve handed in");
                    expect(helper, status(player, HARVEST), Optional.of(QuestStatus.COMPLETED), "the contract done");
                    // Murka: offered, and lost to the time limit.
                    interact(player, elders(helper).getFirst());
                    pick(player, "more_work");
                    talkToEnd(player);
                    expect(helper, stage(player, MURKA), Optional.of("search"), "the search begins");
                    expect(helper, tagged(helper, EntityType.CAT, "demo_murka").size(), 1, "Murka is out there");
                    Entity lost = tagged(helper, EntityType.CAT, "demo_murka").getFirst();
                    // Asked for more work while she is out: she is out, not "asleep by the stove".
                    askForMoreWork(helper, player, "murka_searching");
                    // The fish lost: without it a skittish cat cannot be lured, so the elder gives more.
                    player.getInventory().items.replaceAll(stack -> stack.is(Items.COD) ? ItemStack.EMPTY : stack);
                    askForMoreWork(helper, player, "murka_fish_again");
                    expect(helper, player.getInventory().countItem(Items.COD), 2, "fish again");
                    failMurka(player);
                    expect(helper, status(player, MURKA), Optional.of(QuestStatus.FAILED), "the time is up");
                    expect(helper, tagged(helper, EntityType.CAT, "demo_murka").size(), 0, "she came home on her own: nobody is searched for");
                    expect(helper, tagged(helper, EntityType.CAT, "demo_murka_home").size(), 1, "she is at home");
                    // Offered again: she runs off again, the same cat.
                    interact(player, elders(helper).getFirst());
                    pick(player, "more_work");
                    talkToEnd(player);
                    expect(helper, status(player, MURKA), Optional.of(QuestStatus.ACTIVE), "the search again");
                    expect(helper, murkas(helper), List.of(lost.getUUID()), "the same Murka runs off again, and no other");
                    Entity murka = tagged(helper, EntityType.CAT, "demo_murka").getFirst();
                    player.teleportTo(murka.getX(), murka.getY(), murka.getZ());
                    interact(player, murka);
                    inDialogue(player, "murka");
                    talkToEnd(player);
                    expect(helper, stage(player, MURKA), Optional.of("return"), "found: now home");
                    player.teleportTo(home.x, home.y, home.z);
                    interact(player, elders(helper).getFirst());
                    pick(player, "more_work");
                    expect(helper, node(player), "murka_following", "found and following: the elder sees her");
                    pick(player, "murka_back");
                    talkToEnd(player);
                    expect(helper, status(player, MURKA), Optional.of(QuestStatus.COMPLETED), "Murka home");
                    askForMoreWork(helper, player, "no_work");
                    expect(helper, stage(player, FESTIVAL), Optional.of("invite"), "the watch and Murka done: the festival");
                    helper.assertTrue(Documents.read(player, demo("festival_invite"), false), "the invitation");
                    expect(helper, stage(player, FESTIVAL), Optional.of("bell"), "on to the bell");
                    level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
                    level.setDayTime(18000);
                })
                .thenWaitUntil(() -> expect(helper, status(player, FESTIVAL), Optional.of(QuestStatus.COMPLETED), "at the bell after dark"))
                .thenExecute(() -> {
                    level.setDayTime(dayTime);
                    level.setBlockAndUpdate(bell, Blocks.AIR.defaultBlockState());
                    remove(helper, player);
                    cleanUp(helper);
                })
                .thenSucceed();
    }

    /**
     * However Murka's search ends before it is taken up again (reset by a command, failed, or with her dead and about to
     * come back), there is one Murka afterwards, the same cat: the reset, then the start, as the elder gives the quest.
     */
    @GameTest(template = "open", batch = "demo_murka", timeoutTicks = TIMEOUT)
    public static void aRetriedSearchNeverLeavesTwoMurkas(GameTestHelper helper) {
        cleanUp(helper);
        ServerPlayer player = newPlayer(helper, 6, 2, 6);
        String retry = "[ { \"type\": \"set_quest_state\", \"quest\": \"demo:murka\", \"state\": \"unknown\" },"
                + " { \"type\": \"set_quest_state\", \"quest\": \"demo:murka\", \"state\": \"active\" } ]";
        UUID[] murka = new UUID[1];
        Entity[] dead = new Entity[1];
        helper.startSequence()
                .thenExecute(() -> {
                    run(helper, player, retry);
                    expect(helper, murkas(helper).size(), 1, "the first search: a Murka is spawned");
                    murka[0] = murkas(helper).getFirst();
                    // Reset by a command: no on_failed ran, she is still out there with her tag.
                    run(helper, player, retry);
                    expect(helper, murkas(helper), List.of(murka[0]), "after a reset the one out there is searched for again");
                    failMurka(player);
                    expect(helper, tagged(helper, EntityType.CAT, "demo_murka_home").size(), 1, "failed: she is at home");
                    run(helper, player, retry);
                    expect(helper, murkas(helper), List.of(murka[0]), "after a failure she runs off again");
                    expect(helper, tagged(helper, EntityType.CAT, "demo_murka_home").size(), 0, "and is no longer at home");
                    // Killed where chunks tick (the search spread her up to 40 blocks off, where the GameTest world does not).
                    dead[0] = tagged(helper, EntityType.CAT, "demo_murka").getFirst();
                    dead[0].teleportTo(player.getX(), player.getY(), player.getZ());
                    dead[0].kill();
                })
                .thenWaitUntil(() -> helper.assertTrue(NpcRespawns.waiting(player.server) > 0, "she waits to come back"))
                .thenExecute(() -> {
                    run(helper, player, retry);
                    expect(helper, murkas(helper), List.of(), "dead and about to come back: no other is spawned meanwhile");
                })
                .thenWaitUntil(() -> expect(helper, murkas(helper), List.of(murka[0]), "she comes back, the only Murka"))
                .thenExecute(() -> {
                    remove(helper, player);
                    cleanUp(helper);
                })
                .thenSucceed();
    }

    /** Asks the elder for more work, checks which line he answers with and says goodbye. */
    private static void askForMoreWork(GameTestHelper helper, ServerPlayer player, String answer) {
        interact(player, elders(helper).getFirst());
        pick(player, "more_work");
        expect(helper, node(player), answer, "asked for more work");
        pick(player, "bye");
        talkToEnd(player);
    }

    private static String node(ServerPlayer player) {
        return Dialogues.state(player).map(Dialogues.State::node).orElse(null);
    }

    /** Murka's time runs out, as if fifteen minutes had passed. */
    private static void failMurka(ServerPlayer player) {
        QuestProgress search = Quests.progress(player, MURKA).orElseThrow();
        StoryState.setQuest(player, StoryScope.PLAYER, MURKA, search.startedNow(player.server.overworld().getGameTime() - 901 * 20));
        Quests.checkFailures(player);
    }

    /** The cats that are Murka now: tagged as searched for. */
    private static List<UUID> murkas(GameTestHelper helper) {
        return tagged(helper, EntityType.CAT, "demo_murka").stream().map(Entity::getUUID).toList();
    }

    /** The start every new player gets: the whisper, then the road; then the village is reached (as by entering it). */
    private static GameTestSequence arrive(GameTestHelper helper, ServerPlayer player) {
        return helper.startSequence()
                .thenWaitUntil(() -> inDialogue(player, "awakening"))
                .thenExecute(() -> {
                    talkToEnd(player);
                    expect(helper, stage(player, SMOKE), Optional.of("road"), "the whisper gives the first quest");
                    run(helper, player, "{ \"type\": \"set_quest_stage\", \"quest\": \"demo:smoke_on_the_horizon\", \"stage\": \"meeting\" }");
                })
                .thenWaitUntil(() -> helper.assertTrue(elders(helper).size() == 1, "the bell rings and the elder comes"));
    }

    private static void inDialogue(ServerPlayer player, String dialogue) {
        Optional<ResourceLocation> current = Dialogues.state(player).map(Dialogues.State::dialogue);
        if (!current.equals(Optional.of(demo(dialogue)))) {
            throw new GameTestAssertException("expected the dialogue demo:" + dialogue + ", got " + current);
        }
    }

    /** Clicks past lines until a choice is shown (true) or the dialogue ends (false). */
    private static boolean talkOn(ServerPlayer player) {
        for (int i = 0; i < 60; i++) {
            Optional<Dialogues.State> state = Dialogues.state(player);
            if (state.isEmpty()) {
                return false;
            }
            if (state.get().waitingForChoice()) {
                return true;
            }
            if (!Dialogues.advance(player, state.get().session(), state.get().step())) {
                throw new GameTestAssertException("could not click past " + state.get());
            }
        }
        throw new GameTestAssertException("the dialogue never stops: " + Dialogues.state(player));
    }

    private static void talkToEnd(ServerPlayer player) {
        if (talkOn(player)) {
            throw new GameTestAssertException("a choice came instead of the end: " + Dialogues.shownOptionIds(player) + " at " + Dialogues.state(player));
        }
    }

    /** Talks on to the next choice and picks the option by its id. */
    private static void pick(ServerPlayer player, String option) {
        if (!talkOn(player)) {
            throw new GameTestAssertException("the dialogue ended before the option " + option);
        }
        List<String> shown = Dialogues.shownOptionIds(player);
        Dialogues.State state = Dialogues.state(player).orElseThrow();
        int index = shown.indexOf(option);
        if (index < 0 || !Dialogues.choose(player, state.session(), state.step(), index)) {
            throw new GameTestAssertException("could not pick " + option + " of " + shown + " at " + state);
        }
    }

    private static void interact(ServerPlayer player, Entity target) {
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, target));
    }

    private static List<Entity> elders(GameTestHelper helper) {
        return tagged(helper, EntityType.VILLAGER, "demo_elder");
    }

    private static List<Entity> tagged(GameTestHelper helper, EntityType<?> type, @Nullable String tag) {
        return List.copyOf(helper.getLevel().getEntities(type, entity -> entity.isAlive() && (tag == null || entity.getTags().contains(tag))));
    }

    /**
     * The demo's NPCs and monsters go without coming back: untagged first, so no binding asks for their return; and
     * those already waiting to come back (one that died in another demo test) are forgotten, or "unique" would see them.
     */
    private static void cleanUp(GameTestHelper helper) {
        NpcRespawns.forget(helper.getLevel().getServer(), DEMO_TAGS);
        List<Entity> demo = new ArrayList<>();
        helper.getLevel().getAllEntities().forEach(candidate -> {
            if (DEMO_TAGS.stream().anyMatch(candidate.getTags()::contains)) {
                demo.add(candidate);
            }
        });
        for (Entity entity : demo) {
            DEMO_TAGS.forEach(entity::removeTag);
            entity.discard();
        }
    }

    private static Optional<String> stage(ServerPlayer player, ResourceLocation quest) {
        return Quests.progress(player, quest).filter(progress -> progress.status() == QuestStatus.ACTIVE).flatMap(QuestProgress::stage);
    }

    private static Optional<QuestStatus> status(ServerPlayer player, ResourceLocation quest) {
        return Quests.progress(player, quest).map(QuestProgress::status);
    }

    private static void run(GameTestHelper helper, ServerPlayer player, String json) {
        Actions.runAll(LogicCodecs.ACTIONS.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), JsonParser.parseString(json))
                .getOrThrow(GameTestAssertException::new), StoryContext.of(player), "gametest", OnFail.ABORT);
    }

    /**
     * A player joining at that spot. The GameTest world has no structures, so the answer to the first quest's village
     * search is planted: a real search would scan the whole radius and hold up the one search thread other tests use.
     */
    private static ServerPlayer newPlayer(GameTestHelper helper, double x, double y, double z) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new Vec3(x, y, z)));
        StructureLocator.remember(new StructureLocator.Search(StructureRef.of("#minecraft:village").orElseThrow(), Level.OVERWORLD,
                Waypoints.structureSearchOrigin(player, Level.OVERWORLD), StructureRef.DEFAULT_SEARCH_RADIUS), Optional.empty());
        return player;
    }

    private static ResourceLocation demo(String path) {
        return ResourceLocation.fromNamespaceAndPath("demo", path);
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
