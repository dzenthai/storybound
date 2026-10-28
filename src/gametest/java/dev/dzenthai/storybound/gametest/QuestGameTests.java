package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.compat.jei.QuestRecipe;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.ChapterDefinition;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.logic.trigger.BuiltinTriggers;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.PartyManager;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.WorldStoryData;
import dev.dzenthai.storybound.module.quest.ChapterMap;
import dev.dzenthai.storybound.module.quest.DifficultyScale;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestHints;
import dev.dzenthai.storybound.module.quest.QuestEvents;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.QuestRewardData;
import dev.dzenthai.storybound.module.quest.QuestRewards;
import dev.dzenthai.storybound.module.quest.QuestRewardsOwed;
import dev.dzenthai.storybound.module.quest.Quests;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Quests on a real server: start, objectives, stages, rewards, party sharing, failure and validation. */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestGameTests {
    private static final ResourceLocation QUEST = key("hunt");

    /** Two stages driven by custom triggers so the test controls every step. */
    private static final String HUNT = """
            { "title": "gametest.quest.hunt", "scope": "%1$s",
              "start": { "trigger": { "type": "custom", "id": "gametest:begin" } },
              "stages": [
                { "id": "kill", "log": "gametest.quest.hunt.kill",
                  "objectives": [
                    { "id": "wolves", "trigger": { "type": "custom", "id": "gametest:wolf" }, "count": 2 },
                    { "id": "bonus", "trigger": { "type": "custom", "id": "gametest:bonus" }, "optional": true }
                  ],
                  "on_enter": { "type": "add_var", "key": "gametest:%2$s_kill_entered", "scope": "world", "amount": 1 } },
                { "id": "report",
                  "objectives": [ { "trigger": { "type": "custom", "id": "gametest:report" } } ] }
              ],
              "on_complete": { "type": "add_var", "key": "gametest:%2$s_completions", "scope": "world", "amount": 1 },
              "rewards": { "type": "give_item", "item": "minecraft:emerald", "count": 3 } }
            """;

    private QuestGameTests() {}

    @GameTest(template = "empty")
    public static void questRunsFromStartToReward(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withQuest(helper, HUNT.formatted("player", "solo"), () -> with(helper, CoreDefinitions.EVENTS.serverStore(), StoryEvent.CODEC, Map.of(
                key("on_done"), "{ \"trigger\": { \"type\": \"quest_state_changed\", \"quest\": \"gametest:hunt\", \"state\": \"completed\" },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:done_events\", \"scope\": \"world\", \"amount\": 1 } }",
                key("on_stage"), "{ \"trigger\": { \"type\": \"quest_stage_changed\", \"quest\": \"gametest:hunt\", \"stage\": \"report\" },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:report_events\", \"scope\": \"world\", \"amount\": 1 } }"
        ), () -> {
            StoryEvents.rebuild();
            expect(helper, Quests.progress(player, QUEST), Optional.empty(), "unknown before the start trigger");

            fire(player, "wolf");
            expect(helper, Quests.progress(player, QUEST), Optional.empty(), "objectives do nothing before the start");

            fire(player, "begin");
            QuestProgress started = progress(player);
            expect(helper, started.status(), QuestStatus.ACTIVE, "started");
            expect(helper, started.stage(), Optional.of("kill"), "first stage");
            expect(helper, Quests.tracked(player), Optional.of(QUEST), "a new quest is tracked when nothing else is");
            expect(helper, world(helper, "solo_kill_entered"), Optional.of(new StoryValue.Int(1)), "on_enter of the first stage");

            fire(player, "wolf");
            expect(helper, progress(player).count("wolves"), 1, "counted");
            fire(player, "bonus");
            expect(helper, progress(player).stage(), Optional.of("kill"), "optional objective does not finish the stage");
            fire(player, "wolf");
            expect(helper, progress(player).stage(), Optional.of("report"), "required objectives done: next stage");
            expect(helper, progress(player).log(), List.of("kill"), "passed stage logged");
            expect(helper, progress(player).count("wolves"), 0, "counters reset on the new stage");
            expect(helper, world(helper, "report_events"), Optional.of(new StoryValue.Int(1)), "quest_stage_changed trigger");

            fire(player, "report");
            expect(helper, progress(player).status(), QuestStatus.COMPLETED, "completed after the last stage");
            expect(helper, world(helper, "solo_completions"), Optional.of(new StoryValue.Int(1)), "on_complete ran");
            expect(helper, world(helper, "done_events"), Optional.of(new StoryValue.Int(1)), "quest_state_changed trigger");
            expect(helper, player.getInventory().countItem(Items.EMERALD), 3, "reward paid");
            expect(helper, Quests.tracked(player), Optional.empty(), "finished quest is no longer tracked");

            fire(player, "begin");
            expect(helper, progress(player).status(), QuestStatus.COMPLETED, "the start trigger does not restart a known quest");
        }));
        StoryEvents.rebuild();
        QuestEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partyQuestIsSharedAndRewardsEveryMember(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ok(() -> PartyManager.create(leader, "Hunters"));
        ok(() -> PartyManager.invite(leader, member));
        ok(() -> PartyManager.accept(member));
        withQuest(helper, HUNT.formatted("party", "party"), () -> {
            fire(leader, "begin");
            expect(helper, Quests.progress(member, QUEST).map(QuestProgress::status), Optional.of(QuestStatus.ACTIVE), "member sees the leader's quest");
            expect(helper, Quests.tracked(member), Optional.of(QUEST), "tracked for every member");

            fire(leader, "wolf");
            fire(member, "wolf");
            expect(helper, progress(leader).stage(), Optional.of("report"), "kills of both members count towards one objective");

            fire(member, "report");
            expect(helper, progress(leader).status(), QuestStatus.COMPLETED, "completed by the member for the party");
            expect(helper, world(helper, "party_completions"), Optional.of(new StoryValue.Int(1)), "on_complete runs once for the party");
            expect(helper, leader.getInventory().countItem(Items.EMERALD), 3, "leader rewarded");
            expect(helper, member.getInventory().countItem(Items.EMERALD), 3, "member rewarded");
        });
        QuestEvents.rebuild();
        ok(() -> PartyManager.disband(leader));
        remove(helper, leader);
        remove(helper, member);
        helper.succeed();
    }

    /**
     * A party quest's triggers fire for every member online (a personal quest that follows starts for each), its own
     * {@code on_complete} once; a member away when it is completed is paid when they join.
     */
    @GameTest(template = "empty")
    public static void partyQuestFollowUpsReachEveryMemberAndAbsentOnesArePaidLater(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ok(() -> PartyManager.create(leader, "Followers"));
        ok(() -> PartyManager.invite(leader, member));
        ok(() -> PartyManager.accept(member));
        ResourceLocation follow = key("follow");
        withQuest(helper, HUNT.formatted("party", "fanout"), () -> with(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(follow, """
                { "title": "gametest.quest.follow", "start": { "trigger": { "type": "quest_state_changed", "quest": "gametest:hunt", "state": "completed" } },
                  "stages": [ { "id": "next" } ] }
                """), () -> with(helper, CoreDefinitions.EVENTS.serverStore(), StoryEvent.CODEC, Map.of(key("fanout_done"),
                "{ \"trigger\": { \"type\": \"quest_state_changed\", \"quest\": \"gametest:hunt\", \"state\": \"completed\" },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:fanout_events\", \"scope\": \"world\", \"amount\": 1 } }"), () -> {
            QuestEvents.rebuild();
            StoryEvents.rebuild();
            fire(leader, "begin");
            fire(leader, "wolf");
            fire(leader, "wolf");
            fire(leader, "report");
            expect(helper, world(helper, "fanout_completions"), Optional.of(new StoryValue.Int(1)), "on_complete runs once");
            expect(helper, world(helper, "fanout_events"), Optional.of(new StoryValue.Int(2)), "the trigger fires for each member online");
            expect(helper, Quests.progress(leader, follow).map(QuestProgress::status), Optional.of(QuestStatus.ACTIVE), "the follow-up starts for the one who finished");
            expect(helper, Quests.progress(member, follow).map(QuestProgress::status), Optional.of(QuestStatus.ACTIVE), "and for the other member");

            // The member leaves the game; the party does it again without them.
            helper.getLevel().getServer().getPlayerList().remove(member);
            int before = member.getInventory().countItem(Items.EMERALD);
            run(helper, leader, "{ \"type\": \"set_quest_state\", \"quest\": \"gametest:hunt\", \"state\": \"unknown\" }");
            fire(leader, "begin");
            fire(leader, "wolf");
            fire(leader, "wolf");
            fire(leader, "report");
            expect(helper, member.getInventory().countItem(Items.EMERALD), before, "not paid while away");
            expect(helper, QuestRewardsOwed.get(helper.getLevel().getServer()).of(member.getUUID()), List.of(QUEST), "owed to the absent member");
            QuestRewards.payOwed(member);
            expect(helper, member.getInventory().countItem(Items.EMERALD), before + 3, "paid on joining");
            expect(helper, QuestRewardsOwed.get(helper.getLevel().getServer()).of(member.getUUID()), List.of(), "once");
        })));
        StoryEvents.rebuild();
        QuestEvents.rebuild();
        ok(() -> PartyManager.disband(leader));
        remove(helper, leader);
        helper.succeed();
    }

    /**
     * Whoever skips a completed party quest by a join is paid for it once, as if they had been there: the party behind
     * the newcomer (a member away when they next join), a newcomer behind the party; not for a repeatable quest, not
     * the one who already completed it.
     */
    @GameTest(template = "empty")
    public static void skippingACompletedPartyQuestByJoiningPaysItsRewardsOnce(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer away = helper.makeMockServerPlayerInLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ServerPlayer latecomer = helper.makeMockServerPlayerInLevel();
        ResourceLocation chore = key("catch_up_chore");
        withQuest(helper, HUNT.formatted("party", "catch_up"), () -> with(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(chore, """
                { "title": "gametest.quest.chore", "scope": "party", "repeatable": { "cooldown": 1200 },
                  "start": { "trigger": { "type": "custom", "id": "gametest:chore_begin" } },
                  "stages": [ { "id": "do", "objectives": [ { "trigger": { "type": "custom", "id": "gametest:chore" } } ] } ],
                  "rewards": { "type": "give_item", "item": "minecraft:diamond", "count": 1 } }
                """), () -> {
            QuestEvents.rebuild();
            ok(() -> PartyManager.create(leader, "Catching up"));
            ok(() -> PartyManager.invite(leader, away));
            ok(() -> PartyManager.accept(away));
            remove(helper, away);
            // The member does both quests alone, before joining.
            fire(member, "begin");
            fire(member, "wolf");
            fire(member, "wolf");
            fire(member, "report");
            fire(member, "chore_begin");
            fire(member, "chore");
            expect(helper, member.getInventory().countItem(Items.EMERALD), 3, "the member's own rewards");

            ok(() -> PartyManager.invite(leader, member));
            ok(() -> PartyManager.accept(member));
            expect(helper, progress(leader).status(), QuestStatus.COMPLETED, "the party goes on from the member's progress");
            expect(helper, leader.getInventory().countItem(Items.EMERALD), 3, "the leader skipped the quest: paid as if they had been there");
            expect(helper, member.getInventory().countItem(Items.EMERALD), 3, "the member is not paid again");
            expect(helper, QuestRewardsOwed.get(helper.getLevel().getServer()).of(away.getUUID()), List.of(QUEST), "the member away is owed it");
            expect(helper, leader.getInventory().countItem(Items.DIAMOND), 0, "a repeatable quest: the next round is the leader's to do");

            ok(() -> PartyManager.invite(leader, latecomer));
            ok(() -> PartyManager.accept(latecomer));
            expect(helper, latecomer.getInventory().countItem(Items.EMERALD), 3, "a newcomer behind the party is paid");
            expect(helper, leader.getInventory().countItem(Items.EMERALD), 3, "the others are not paid again");

            ok(() -> PartyManager.leave(member));
            ok(() -> PartyManager.invite(leader, member));
            ok(() -> PartyManager.accept(member));
            expect(helper, member.getInventory().countItem(Items.EMERALD), 3, "leaving and joining again pays nothing");
            expect(helper, QuestRewardsOwed.get(helper.getLevel().getServer()).of(away.getUUID()), List.of(QUEST), "owed once");
        }));
        QuestEvents.rebuild();
        ok(() -> PartyManager.disband(leader));
        remove(helper, leader);
        remove(helper, member);
        remove(helper, latecomer);
        helper.succeed();
    }

    /** A completed quest made active again starts over; a failed one goes on where it failed. */
    @GameTest(template = "empty")
    public static void finishedQuestsMadeActiveAgainStartOverOrGoOn(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        String active = "{ \"type\": \"set_quest_state\", \"quest\": \"gametest:hunt\", \"state\": \"active\" }";
        withQuest(helper, HUNT.formatted("player", "reopen"), () -> {
            fire(player, "begin");
            fire(player, "wolf");
            fire(player, "wolf");
            fire(player, "report");
            expect(helper, progress(player).status(), QuestStatus.COMPLETED, "completed");
            run(helper, player, active);
            expect(helper, progress(player).stage(), Optional.of("kill"), "a completed quest starts over at its first stage");
            expect(helper, progress(player).count("wolves"), 0, "with fresh counters");
            expect(helper, progress(player).log(), List.of(), "and a fresh log");
            expect(helper, progress(player).completions(), 1, "keeping its completions");
            expect(helper, world(helper, "reopen_kill_entered"), Optional.of(new StoryValue.Int(2)), "the first stage is entered again");
            fire(player, "wolf");
            expect(helper, progress(player).count("wolves"), 1, "and counts again");

            run(helper, player, "{ \"type\": \"set_quest_state\", \"quest\": \"gametest:hunt\", \"state\": \"failed\" }");
            run(helper, player, active);
            expect(helper, progress(player).stage(), Optional.of("kill"), "a failed quest goes on at its stage");
            expect(helper, progress(player).count("wolves"), 1, "with its counters");
            expect(helper, progress(player).log(), List.of(), "its stage not logged twice");
            fire(player, "wolf");
            expect(helper, progress(player).stage(), Optional.of("report"), "and can be finished");

            // Failed with every objective of its stage done: going on as it was could never move again.
            StoryState.setQuest(player, StoryScope.PLAYER, QUEST, new QuestProgress(QuestStatus.FAILED, Optional.of("kill"), Map.of("wolves", 2), List.of("kill")));
            run(helper, player, active);
            expect(helper, progress(player).stage(), Optional.of("kill"), "goes on at that stage");
            expect(helper, progress(player).count("wolves"), 0, "with the stage's counters anew");
        });
        QuestEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    /**
     * A player already in the place when an "enter it" objective becomes current (they walked in during the opening
     * scene, or joined there) gets it counted, without walking out and back in.
     */
    @GameTest(template = "empty")
    public static void beingAlreadyInsideCountsAsEntering(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 at = player.position();
        String area = "\"area\": { \"center\": [" + at.x + ", " + at.y + ", " + at.z + "], \"radius\": 4 }";
        withQuest(helper, """
                { "title": "gametest.quest.arrive",
                  "stages": [
                    { "id": "go", "objectives": [ { "id": "there", "trigger": { "type": "area_entered", %1$s } } ] },
                    { "id": "twice", "objectives": [ { "id": "again", "trigger": { "type": "area_entered", %1$s }, "count": 2 } ] } ] }
                """.formatted(area), () -> {
            Quests.start(player, QUEST);
            Quests.checkStates(player);
            expect(helper, progress(player).stage(), Optional.of("twice"), "already inside: the entry counts");
            Quests.checkStates(player);
            expect(helper, progress(player).count("again"), 0, "but not towards entering several times");
        });
        QuestEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    /**
     * A trigger's state that already holds counts: a past event ("history") in a quest that is not repeatable, unless
     * the objective says since_stage; a quest added after what starts it happened starts.
     */
    @GameTest(template = "empty")
    public static void statesThatAlreadyHoldCountAndStartQuests(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.awardStat(Stats.JUMP);
        ResourceLocation past = key("past");
        ResourceLocation pastAgain = key("past_again");
        ResourceLocation next = key("next");
        String jumped = "{ \"type\": \"stat_reached\", \"stat\": \"minecraft:jump\", \"value\": 1 }";
        with(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(
                past, """
                        { "title": "gametest.quest.past", "stages": [
                            { "id": "jump", "objectives": [ { "id": "j", "trigger": %1$s } ] },
                            { "id": "fresh", "objectives": [ { "id": "j", "trigger": %1$s, "since_stage": true } ] } ] }
                        """.formatted(jumped),
                pastAgain, """
                        { "title": "gametest.quest.past_again", "repeatable": true, "stages": [
                            { "id": "jump", "objectives": [ { "id": "j", "trigger": %1$s } ] } ] }
                        """.formatted(jumped)), () -> {
            QuestEvents.rebuild();
            Quests.start(player, past);
            Quests.start(player, pastAgain);
            Quests.checkStates(player);
            expect(helper, Quests.progress(player, past).flatMap(QuestProgress::stage), Optional.of("fresh"), "a jump made before counts");
            Quests.checkStates(player);
            expect(helper, Quests.progress(player, past).flatMap(QuestProgress::stage), Optional.of("fresh"), "but not with since_stage");
            expect(helper, Quests.progress(player, pastAgain).map(progress -> progress.count("j")), Optional.of(0),
                    "nor in a repeatable quest, whose every repetition it would finish");

            Quests.setStatus(player, past, QuestStatus.COMPLETED);
            // Added after the quest it waits for was completed: the change it listens for is long gone.
            install(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(next, """
                    { "title": "gametest.quest.next",
                      "start": { "trigger": { "type": "quest_state_changed", "quest": "gametest:past", "state": "completed" } },
                      "stages": [ { "id": "go" } ] }
                    """));
            QuestEvents.rebuild();
            expect(helper, Quests.progress(player, next), Optional.empty(), "not started before the check");
            Quests.checkStates(player);
            expect(helper, Quests.progress(player, next).map(QuestProgress::status), Optional.of(QuestStatus.ACTIVE), "started by the state of its start");
        });
        QuestEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    /**
     * An author turns a personal quest into a party one after players have it: the furthest progress of the members
     * becomes the party's; turned back, every member gets a copy. Nothing fires and nothing is paid.
     */
    @GameTest(template = "empty")
    public static void changingAQuestsScopeKeepsItsProgress(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ok(() -> PartyManager.create(leader, "Movers"));
        ok(() -> PartyManager.invite(leader, member));
        ok(() -> PartyManager.accept(member));
        withQuest(helper, HUNT.formatted("player", "moved"), () -> {
            fire(leader, "begin");
            fire(leader, "wolf");
            fire(leader, "wolf");
            fire(member, "begin");
            expect(helper, progress(leader).stage(), Optional.of("report"), "the leader is further");
            int emeralds = leader.getInventory().countItem(Items.EMERALD);

            install(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(QUEST, HUNT.formatted("party", "moved")));
            expect(helper, Quests.progress(member, QUEST), Optional.empty(), "read from the party, nothing is there yet");
            Quests.adoptScopes(member);
            expect(helper, progress(member).stage(), Optional.of("report"), "the furthest progress became the party's");
            expect(helper, progress(leader).stage(), Optional.of("report"), "shared by the leader");
            expect(helper, StoryState.getQuest(leader, StoryScope.PLAYER, QUEST), Optional.empty(), "replacing their own copies");
            expect(helper, StoryState.getQuest(member, StoryScope.PLAYER, QUEST), Optional.empty(), "both of them");
            expect(helper, world(helper, "moved_kill_entered"), Optional.of(new StoryValue.Int(2)), "no stage entered again");
            expect(helper, leader.getInventory().countItem(Items.EMERALD), emeralds, "nothing paid");

            install(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(QUEST, HUNT.formatted("player", "moved")));
            Quests.adoptScopes(member);
            Quests.adoptScopes(leader);
            expect(helper, progress(member).stage(), Optional.of("report"), "back to personal: a copy for the member");
            expect(helper, progress(leader).stage(), Optional.of("report"), "and for the leader");
        });
        QuestEvents.rebuild();
        ok(() -> PartyManager.disband(leader));
        remove(helper, leader);
        remove(helper, member);
        helper.succeed();
    }

    /** An active quest at a stage its definition no longer has is moved to the first stage on joining or reloading. */
    @GameTest(template = "empty")
    public static void aQuestAtALostStageMovesToTheFirstStage(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withQuest(helper, HUNT.formatted("player", "lost"), () -> {
            StoryState.setQuest(player, StoryScope.PLAYER, QUEST, new QuestProgress(QuestStatus.ACTIVE, Optional.of("renamed_away"), Map.of("0", 1), List.of("kill")));
            Quests.repairStages(player);
            expect(helper, progress(player).stage(), Optional.of("kill"), "moved to the first stage");
            expect(helper, progress(player).objectives(), Map.of(), "with fresh counters");
            expect(helper, progress(player).log(), List.of("kill"), "the story so far kept");
            expect(helper, world(helper, "lost_kill_entered"), Optional.of(new StoryValue.Int(1)), "the stage is entered");
            Quests.repairStages(player);
            expect(helper, world(helper, "lost_kill_entered"), Optional.of(new StoryValue.Int(1)), "a stage that exists is left alone");
        });
        QuestEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actionsConditionsAndReset(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withQuest(helper, HUNT.formatted("player", "actions"), () -> {
            helper.assertTrue(test(helper, player, "{ \"type\": \"quest\", \"quest\": \"gametest:hunt\", \"state\": \"unknown\" }"), "unknown condition");
            run(helper, player, "{ \"type\": \"set_quest_stage\", \"quest\": \"gametest:hunt\", \"stage\": \"report\" }");
            expect(helper, progress(player).stage(), Optional.of("report"), "set_quest_stage starts an unknown quest");
            helper.assertTrue(test(helper, player, "{ \"type\": \"quest\", \"quest\": \"gametest:hunt\", \"stage\": \"report\" }"), "stage condition");
            helper.assertTrue(test(helper, player, "{ \"type\": \"quest\", \"quest\": \"gametest:hunt\", \"state\": \"active\" }"), "active condition");

            run(helper, player, "{ \"type\": \"set_quest_state\", \"quest\": \"gametest:hunt\", \"state\": \"failed\" }");
            expect(helper, progress(player).status(), QuestStatus.FAILED, "failed by action");
            expect(helper, player.getInventory().countItem(Items.EMERALD), 0, "no rewards for a failed quest");
            helper.assertTrue(!test(helper, player, "{ \"type\": \"quest\", \"quest\": \"gametest:hunt\", \"stage\": \"report\" }"),
                    "stage condition is false once the quest is finished");

            run(helper, player, "{ \"type\": \"set_quest_state\", \"quest\": \"gametest:hunt\", \"state\": \"unknown\" }");
            expect(helper, Quests.progress(player, QUEST), Optional.empty(), "reset forgets the quest");
            helper.assertTrue(!Quests.setStage(player, QUEST, "missing"), "unknown stage is refused");
        });
        QuestEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failWhenFailsAnActiveQuest(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ResourceLocation escort = key("escort");
        install(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(escort, """
                { "title": "gametest.quest.escort",
                  "stages": [ { "id": "walk" } ],
                  "fail_when": { "type": "flag", "key": "gametest:escort_dead" } }
                """));
        Quests.start(player, escort);
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("escort_dead"), new StoryValue.Bool(true)));
        helper.succeedWhen(() -> {
            expect(helper, Quests.progress(player, escort).map(QuestProgress::status), Optional.of(QuestStatus.FAILED), "failed by fail_when");
            // Only its own quest: a snapshot restored ticks later would drop quests other tests installed meanwhile.
            forget(escort);
            remove(helper, player);
        });
    }

    @GameTest(template = "empty")
    public static void itemObjectivesCountWhatIsHeldAndTakeWhatIsHandedIn(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withQuest(helper, """
                { "title": "gametest.quest.tanner",
                  "stages": [
                    { "id": "gather", "objectives": [ { "id": "leather", "items": { "item": "minecraft:leather" }, "count": 5 } ] },
                    { "id": "hand_in", "objectives": [ { "id": "emeralds", "items": { "item": "minecraft:emerald", "consume": true }, "count": 3,
                        "trigger": { "type": "custom", "id": "gametest:tanner" } } ] } ] }
                """, () -> {
            player.getInventory().add(new ItemStack(Items.LEATHER, 2));
            Quests.start(player, QUEST);
            Quests.checkItems(player);
            expect(helper, progress(player).count("leather"), 2, "what was held before the quest counts");
            player.getInventory().add(new ItemStack(Items.LEATHER, 3));
            Quests.checkItems(player);
            expect(helper, progress(player).stage(), Optional.of("hand_in"), "five held: the stage is done");
            helper.assertTrue(player.getInventory().countItem(Items.LEATHER) == 5, "checking takes nothing");

            player.getInventory().add(new ItemStack(Items.EMERALD, 2));
            Quests.checkItems(player);
            expect(helper, progress(player).count("emeralds"), 0, "with a trigger, nothing happens every second");
            fire(player, "tanner");
            expect(helper, progress(player).count("emeralds"), 2, "two handed in");
            helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 0, "and taken");
            player.getInventory().add(new ItemStack(Items.EMERALD, 5));
            fire(player, "tanner");
            expect(helper, Quests.progress(player, QUEST).map(QuestProgress::status), Optional.of(QuestStatus.COMPLETED), "the third completes it");
            helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 4, "only what was missing is taken: " + player.getInventory().countItem(Items.EMERALD));
            remove(helper, player);
        });
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void repeatableQuestsWaitForTheirCooldown(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ResourceLocation daily = key("daily");
        install(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(daily, """
                { "title": "gametest.quest.daily", "repeatable": { "cooldown": 1, "times": 2 },
                  "start": { "trigger": { "type": "custom", "id": "gametest:again" } },
                  "stages": [ { "id": "do", "objectives": [ { "trigger": { "type": "custom", "id": "gametest:done" } } ] } ] }
                """));
        QuestEvents.rebuild();
        fire(player, "again");
        fire(player, "done");
        expect(helper, Quests.progress(player, daily).orElseThrow().status(), QuestStatus.COMPLETED, "done once");
        fire(player, "again");
        expect(helper, Quests.progress(player, daily).orElseThrow().status(), QuestStatus.COMPLETED, "not again before the cooldown");
        helper.runAfterDelay(30, () -> {
            fire(player, "again");
            expect(helper, Quests.progress(player, daily).orElseThrow().status(), QuestStatus.ACTIVE, "again after the cooldown");
            expect(helper, Quests.progress(player, daily).orElseThrow().count("0"), 0, "the counters start over");
            fire(player, "done");
            expect(helper, Quests.progress(player, daily).orElseThrow().completions(), 2, "two completions");
            helper.runAfterDelay(30, () -> {
                fire(player, "again");
                expect(helper, Quests.progress(player, daily).orElseThrow().status(), QuestStatus.COMPLETED, "not a third time");
                forget(daily);
                QuestEvents.rebuild();
                remove(helper, player);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aQuestFailsWhenItsTimeIsUp(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ResourceLocation hurry = key("hurry");
        install(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(hurry, """
                { "title": "gametest.quest.hurry", "time_limit": 1, "difficulty": "deadly",
                  "stages": [ { "id": "run", "objectives": [ { "trigger": { "type": "custom", "id": "gametest:never" } } ] } ],
                  "on_failed": { "type": "add_var", "key": "gametest:too_late", "scope": "world", "amount": 1 } }
                """));
        QuestEvents.rebuild();
        Quests.start(player, hurry);
        helper.assertTrue(Quests.progress(player, hurry).orElseThrow().startedAt() > 0, "the start time is kept");
        helper.runAfterDelay(60, () -> {
            expect(helper, Quests.progress(player, hurry).orElseThrow().status(), QuestStatus.FAILED, "failed when the time was up");
            expect(helper, world(helper, "too_late"), Optional.of(new StoryValue.Int(1)), "on_failed ran");
            forget(hurry);
            QuestEvents.rebuild();
            remove(helper, player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void difficultyScaleMapsStarsAndLevels(GameTestHelper helper) {
        DifficultyScale scale = DifficultyScale.DEFAULT;
        QuestDefinition.Difficulty hard = new QuestDefinition.Difficulty(Optional.empty(), Optional.of("hard"));
        QuestDefinition.Difficulty four = new QuestDefinition.Difficulty(Optional.of(4), Optional.empty());
        expect(helper, scale.stars(hard), 3, "the third level is three stars");
        expect(helper, scale.level(four).map(DifficultyScale.Level::id), Optional.of("deadly"), "four stars is the fourth level");
        DifficultyScale stars = new DifficultyScale(DifficultyScale.Style.STARS, scale.levels());
        expect(helper, stars.render(hard).getString(), "★★★☆☆", "stars as in Stardew Valley");
        expect(helper, scale.render(hard).getString(), "Hard", "a level by its name");
        // The editor offers exactly the scale: five numbers of stars, or the levels.
        expect(helper, stars.choices().stream().map(choice -> stars.render(choice).getString()).toList(),
                List.of("★☆☆☆☆", "★★☆☆☆", "★★★☆☆", "★★★★☆", "★★★★★"), "1 to 5 stars");
        expect(helper, scale.choices().stream().map(choice -> choice.level().orElse("?")).toList(),
                List.of("easy", "normal", "hard", "deadly"), "the levels, easiest first");
        helper.assertTrue(QuestDefinition.Difficulty.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(6)).isError(), "no more than 5 stars");
        expect(helper, DifficultyScale.parse(JsonParser.parseString("""
                { "levels": [ { "id": "a" }, { "id": "b" }, { "id": "c" }, { "id": "d" }, { "id": "e" }, { "id": "f" } ] }
                """).getAsJsonObject()).levels().size(), 5, "no more than 5 levels");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardsWaitToBeClaimedAndNeverDrop(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withQuest(helper, """
                { "title": "gametest.quest.paid",
                  "stages": [ { "id": "do", "objectives": [ { "trigger": { "type": "custom", "id": "gametest:paid_done" } } ] } ],
                  "rewards": { "items": [ { "item": "minecraft:emerald", "count": 3 } ],
                    "choice": [ { "item": "minecraft:iron_sword" }, { "id": "minecraft:bow" } ],
                    "loot": "minecraft:chests/simple_dungeon", "xp": 10,
                    "actions": { "type": "add_var", "key": "gametest:paid", "scope": "world", "amount": 1 } } }
                """, () -> {
            Quests.start(player, QUEST);
            fire(player, "paid_done");
            expect(helper, progress(player).status(), QuestStatus.COMPLETED, "completed");
            helper.assertTrue(QuestRewardData.of(player).pending().size() == 1, "the rewards wait");
            helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 0 && world(helper, "paid").isEmpty(), "nothing given yet");
            List<ItemStack> loot = QuestRewardData.of(player).pending().getFirst().loot();
            helper.assertTrue(!loot.isEmpty(), "the loot was rolled at completion");

            helper.assertTrue(QuestRewards.claim(player, QUEST, -1) != null, "a choice is needed");
            // A full inventory: refused, nothing dropped.
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
            }
            helper.assertTrue(QuestRewards.claim(player, QUEST, 1) != null, "no room: refused");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8)).isEmpty(), "nothing on the ground");
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
            int lootCount = loot.stream().mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(QuestRewards.claim(player, QUEST, 1) == null, "claimed");
            helper.assertTrue(player.getInventory().countItem(Items.EMERALD) >= 3 && player.getInventory().countItem(Items.BOW) == 1
                    && player.getInventory().countItem(Items.IRON_SWORD) == 0, "the items and the chosen bow");
            int held = 0;
            for (ItemStack stack : player.getInventory().items) {
                held += stack.getCount();
            }
            expect(helper, held, 3 + 1 + lootCount, "and exactly the loot that was rolled");
            expect(helper, world(helper, "paid"), Optional.of(new StoryValue.Int(1)), "the actions ran");
            helper.assertTrue(QuestRewardData.of(player).pending().isEmpty(), "nothing waits");
            QuestRewardData.Received received = QuestRewardData.of(player).received(QUEST).orElse(null);
            helper.assertTrue(received != null && received.xp() == 10 && received.items().stream().mapToInt(ItemStack::getCount).sum() == 3 + 1 + lootCount
                    && received.items().stream().anyMatch(stack -> stack.is(Items.BOW)), "the Journal keeps what was received, the chosen bow and the loot included");
            helper.assertTrue(QuestRewards.claim(player, QUEST, 1) != null, "and nothing can be claimed twice");
            remove(helper, player);
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardsWithoutClaimAreGivenAtOnce(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withQuest(helper, """
                { "title": "gametest.quest.instant",
                  "stages": [ { "id": "do", "objectives": [ { "trigger": { "type": "custom", "id": "gametest:instant_done" } } ] } ],
                  "rewards": { "items": [ { "item": "minecraft:gold_ingot", "count": 2 } ], "claim": false } }
                """, () -> {
            Quests.start(player, QUEST);
            fire(player, "instant_done");
            helper.assertTrue(player.getInventory().countItem(Items.GOLD_INGOT) == 2 && QuestRewardData.of(player).pending().isEmpty(), "given at once");
            helper.assertTrue(QuestRewardData.of(player).received(QUEST).isPresent(), "and kept as received");
            remove(helper, player);
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void severalQuestsArePinnedTheMainOneFirst(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        List<ResourceLocation> quests = new ArrayList<>();
        for (int i = 0; i < Quests.MAX_PINNED + 1; i++) {
            quests.add(key("pin_" + i));
            Quests.track(player, quests.getLast());
        }
        expect(helper, Quests.pinned(player).size(), Quests.MAX_PINNED, "at most " + Quests.MAX_PINNED);
        expect(helper, Quests.tracked(player), Optional.of(quests.getLast()), "the last pinned is the main one");
        helper.assertFalse(Quests.pinned(player).contains(quests.getFirst()), "the oldest went");
        Quests.unpin(player, quests.getLast());
        helper.assertFalse(Quests.pinned(player).contains(quests.getLast()), "unpinned");
        Quests.track(player, null);
        helper.assertTrue(Quests.pinned(player).isEmpty(), "none");
        // A save from before several could be pinned.
        var old = QuestRegistries.TrackedQuest.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{ \"quest\": \"gametest:old\" }")).getOrThrow();
        expect(helper, old.quests(), List.of(key("old")), "the old save keeps its quest");
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void hintsShowByConditionsAndWhenStuck(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ResourceLocation stuck = key("stuck");
        install(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(stuck, """
                { "title": "gametest.quest.stuck",
                  "stages": [ { "id": "hunt", "objectives": [ { "id": "wolves", "count": 3,
                    "trigger": { "type": "custom", "id": "gametest:stuck_wolf" },
                    "hints": [ { "text": "gametest.hint.night", "conditions": { "type": "flag", "key": "gametest:stuck_night" } },
                               { "text": "gametest.hint.stuck", "after": 1 } ] } ] } ] }
                """));
        QuestEvents.rebuild();
        String night = QuestHints.key(stuck, "hunt", "wolves", 0);
        String waited = QuestHints.key(stuck, "hunt", "wolves", 1);
        Quests.start(player, stuck);
        QuestHints.check(player);
        helper.assertTrue(QuestHints.shown(player).isEmpty(), "nothing yet: the flag is off and nothing was waited for");
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("stuck_night"), new StoryValue.Bool(true)));
        QuestHints.check(player);
        helper.assertTrue(QuestHints.shown(player).equals(Set.of(night)), "the hint whose condition holds: " + QuestHints.shown(player));
        helper.runAfterDelay(30, () -> {
            QuestHints.check(player);
            helper.assertTrue(QuestHints.shown(player).contains(waited), "stuck for over a second: the other hint too; shown " + QuestHints.shown(player)
                    + ", quest " + Quests.progress(player, stuck).orElse(null) + ", game time " + helper.getLevel().getServer().overworld().getGameTime());
            fire(player, "stuck_wolf");
            QuestHints.check(player);
            helper.assertFalse(QuestHints.shown(player).contains(waited), "progress: the waiting starts again");
            forget(stuck);
            QuestEvents.rebuild();
            remove(helper, player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void validatorReportsBrokenQuests(GameTestHelper helper) {
        withQuest(helper, """
                { "title": "gametest.quest.broken",
                  "stages": [
                    { "id": "a", "next": "nowhere", "objectives": [ { "text": "gametest.no_trigger" } ] },
                    { "id": "a" }
                  ],
                  "on_complete": { "type": "set_quest_stage", "quest": "gametest:missing", "stage": "x" } }
                """, () -> {
            List<String> errors = ValidationService.validate().issues().stream()
                    .filter(issue -> QUEST.equals(issue.entry()) && issue.isError()).map(ValidationIssue::message).toList();
            for (String expected : List.of("Duplicate stage id 'a'", "leads to unknown stage 'nowhere'", "has neither a trigger nor items", "Unknown quest gametest:missing")) {
                helper.assertTrue(errors.stream().anyMatch(message -> message.contains(expected)), "expected an error containing '" + expected + "', got " + errors);
            }
        });
        QuestEvents.rebuild();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chapterMapPlacesQuestsAndLinksTheOnesThatFollow(GameTestHelper helper) {
        ResourceLocation first = key("map_first");
        ResourceLocation second = key("map_second");
        ResourceLocation loose = key("map_loose");
        ResourceLocation chapter = key("map_chapter");
        install(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(
                first, """
                        { "title": "gametest.quest.map_first", "stages": [ { "id": "a" } ],
                          "on_complete": { "type": "set_quest_state", "quest": "gametest:map_second", "state": "active" } }
                        """,
                second, "{ \"title\": \"gametest.quest.map_second\", \"stages\": [ { \"id\": \"a\" } ], \"map_visibility\": \"secret\" }",
                loose, "{ \"title\": \"gametest.quest.map_loose\", \"stages\": [ { \"id\": \"a\" } ] }"));
        install(helper, CoreDefinitions.CHAPTERS.serverStore(), ChapterDefinition.CODEC, Map.of(chapter, """
                { "title": { "literal": "Act I" }, "order": -1000,
                  "nodes": [ { "type": "quest", "id": "gametest:map_first", "x": 0, "y": 0 }, { "type": "quest", "id": "gametest:map_second", "x": 200, "y": 40 } ] }
                """));
        try {
            ChapterMap map = ChapterMap.assemble(helper.getLevel().getServer());
            ChapterMap.Chapter act = map.chapters().stream().filter(candidate -> candidate.id().equals(chapter)).findFirst().orElse(null);
            helper.assertTrue(act != null && act.nodes().equals(List.of(new ChapterMap.Node(first, 0, 0), new ChapterMap.Node(second, 200, 40))),
                    "the chapter's quests where the author placed them: " + act);
            helper.assertTrue(map.links().contains(new ChapterMap.Link(first, second)) && map.before(second).contains(first),
                    "an arrow to the quest that follows: " + map.links());
            ChapterMap.Chapter other = map.chapters().stream().filter(candidate -> candidate.id().equals(ChapterMap.OTHER)).findFirst().orElse(null);
            helper.assertTrue(other != null && other.nodes().stream().anyMatch(node -> node.quest().equals(loose))
                    && other.nodes().stream().noneMatch(node -> node.quest().equals(first)), "a quest in no chapter goes to the last one");
            expect(helper, QuestRegistries.QUESTS.serverStore().getOrNull(second).visibility(), QuestDefinition.Visibility.SECRET, "map_visibility");
            ChapterMap decoded = ChapterMap.CODEC.parse(JsonOps.INSTANCE, ChapterMap.CODEC.encodeStart(JsonOps.INSTANCE, map).getOrThrow()).getOrThrow();
            expect(helper, decoded, map, "the map survives its codec");
        } finally {
            forget(first);
            forget(second);
            forget(loose);
            Map<ResourceLocation, ChapterDefinition> chapters = new LinkedHashMap<>(CoreDefinitions.CHAPTERS.serverStore().all());
            chapters.remove(chapter);
            CoreDefinitions.CHAPTERS.serverStore().replace(chapters);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void jeiRecipesShowWhatAQuestAsksForAndGives(GameTestHelper helper) {
        QuestDefinition quest = QuestDefinition.CODEC.parse(ops(helper), JsonParser.parseString("""
                { "title": "gametest.quest.jei",
                  "stages": [ { "id": "a", "objectives": [
                    { "items": { "item": "minecraft:emerald", "consume": true }, "count": 12 },
                    { "items": { "tag": "minecraft:logs" }, "count": 4 } ] } ],
                  "rewards": { "items": [ { "item": "minecraft:iron_ingot", "count": 3 } ], "choice": [ "minecraft:bow", "minecraft:shield" ] } }
                """)).getOrThrow(GameTestAssertException::new);
        List<QuestRecipe> recipes = QuestRecipe.of(key("jei"), quest);
        helper.assertTrue(recipes.stream().map(QuestRecipe::kind).toList().equals(List.of(QuestRecipe.Kind.HAND_IN, QuestRecipe.Kind.BRING,
                QuestRecipe.Kind.REWARD, QuestRecipe.Kind.CHOICE)), "asked for, then given: " + recipes.stream().map(QuestRecipe::kind).toList());
        helper.assertTrue(recipes.getFirst().count() == 12 && recipes.getFirst().slots().getFirst().getFirst().is(Items.EMERALD), "the emeralds to hand in");
        helper.assertTrue(recipes.get(1).slots().getFirst().size() > 1, "a tag cycles through its items");
        ItemStack iron = recipes.get(2).slots().getFirst().getFirst();
        helper.assertTrue(iron.is(Items.IRON_INGOT) && iron.getCount() == 3, "the reward with its count");
        helper.assertTrue(recipes.get(3).slots().size() == 2, "one slot per item to choose from");
        helper.succeed();
    }

    /** Removes one test quest only: tests running at the same time keep theirs. */
    private static void forget(ResourceLocation quest) {
        Map<ResourceLocation, QuestDefinition> left = new LinkedHashMap<>(QuestRegistries.QUESTS.serverStore().all());
        left.remove(quest);
        QuestRegistries.QUESTS.serverStore().replace(left);
    }

    private static QuestProgress progress(ServerPlayer player) {
        return Quests.progress(player, QUEST).orElseThrow(() -> new GameTestAssertException("quest should be known"));
    }

    private static Optional<StoryValue> world(GameTestHelper helper, String path) {
        return WorldStoryData.get(helper.getLevel().getServer()).container().get(key(path));
    }

    private static void fire(ServerPlayer player, String id) {
        TriggerDispatcher.fire(BuiltinTriggers.CUSTOM.get(), StoryContext.of(player), key(id));
    }

    private static boolean test(GameTestHelper helper, ServerPlayer player, String json) {
        return LogicCodecs.CONDITION.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new).test(StoryContext.of(player));
    }

    private static void run(GameTestHelper helper, ServerPlayer player, String json) {
        Actions.runAll(LogicCodecs.ACTIONS.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new),
                StoryContext.of(player), "gametest", OnFail.ABORT);
    }

    /** Installs the test quest under {@link #QUEST} and subscribes it, then restores the loaded quests. */
    private static void withQuest(GameTestHelper helper, String json, Runnable body) {
        with(helper, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, Map.of(QUEST, json), () -> {
            QuestEvents.rebuild();
            body.run();
        });
    }

    private static <T> void with(GameTestHelper helper, DefinitionStore<T> store, Codec<T> codec, Map<ResourceLocation, String> definitions, Runnable body) {
        Map<ResourceLocation, T> previous = store.all();
        install(helper, store, codec, definitions);
        try {
            body.run();
        } finally {
            store.replace(previous);
        }
    }

    private static <T> void install(GameTestHelper helper, DefinitionStore<T> store, Codec<T> codec, Map<ResourceLocation, String> definitions) {
        Map<ResourceLocation, T> installed = new LinkedHashMap<>(store.all());
        definitions.forEach((id, json) -> installed.put(id, codec.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new)));
        store.replace(installed);
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
