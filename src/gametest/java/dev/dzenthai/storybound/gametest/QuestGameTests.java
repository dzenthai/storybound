package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
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
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestEvents;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.Quests;
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
        DefinitionStore<QuestDefinition> store = QuestRegistries.QUESTS.serverStore();
        Map<ResourceLocation, QuestDefinition> previous = store.all();
        ResourceLocation escort = key("escort");
        install(helper, store, QuestDefinition.CODEC, Map.of(escort, """
                { "title": "gametest.quest.escort",
                  "stages": [ { "id": "walk" } ],
                  "fail_when": { "type": "flag", "key": "gametest:escort_dead" } }
                """));
        Quests.start(player, escort);
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("escort_dead"), new StoryValue.Bool(true)));
        helper.succeedWhen(() -> {
            expect(helper, Quests.progress(player, escort).map(QuestProgress::status), Optional.of(QuestStatus.FAILED), "failed by fail_when");
            store.replace(previous);
            remove(helper, player);
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
            for (String expected : List.of("Duplicate stage id 'a'", "leads to unknown stage 'nowhere'", "has no trigger", "Unknown quest gametest:missing")) {
                helper.assertTrue(errors.stream().anyMatch(message -> message.contains(expected)), "expected an error containing '" + expected + "', got " + errors);
            }
        });
        QuestEvents.rebuild();
        helper.succeed();
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
