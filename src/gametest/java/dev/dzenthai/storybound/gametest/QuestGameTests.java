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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
