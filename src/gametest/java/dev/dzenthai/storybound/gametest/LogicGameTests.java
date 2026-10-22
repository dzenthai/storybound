package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.compat.cutscene.Cutscenes;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.ScheduledActions;
import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import dev.dzenthai.storybound.core.logic.condition.StateConditions;
import dev.dzenthai.storybound.core.logic.trigger.BuiltinTriggers;
import dev.dzenthai.storybound.core.logic.trigger.CoreTriggers;
import dev.dzenthai.storybound.core.logic.trigger.ProgressTriggers;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.NbtOps;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The logic language end to end: JSON parsing, story events, trigger chains, actions and the world conditions and place triggers. */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LogicGameTests {
    private LogicGameTests() {}

    @GameTest(template = "empty")
    public static void parsesStoryEventJson(GameTestHelper helper) {
        StoryEvent event = parse(helper, """
                {
                  "trigger": { "type": "custom", "id": "gametest:bell" },
                  "conditions": [
                    { "type": "storybound:flag", "key": "gametest:met_elder" },
                    { "type": "var", "key": "gametest:gold", "op": ">=", "value": 5 }
                  ],
                  "actions": { "type": "set_var", "key": "gametest:road", "value": "north" },
                  "once": "party"
                }
                """).getOrThrow(message -> new GameTestAssertException("Parse failed: " + message));

        helper.assertTrue(event.trigger() instanceof CoreTriggers.Custom custom && custom.id().equals(key("bell")), "custom trigger");
        helper.assertTrue(event.conditions() instanceof LogicConditions.All all && all.conditions().size() == 2, "array of conditions means all");
        helper.assertTrue(event.actions().size() == 1, "a single action object is a list of one");
        expect(helper, event.once(), Optional.of(StoryScope.PARTY), "once scope");

        helper.assertTrue(parse(helper, "{ \"trigger\": { \"type\": \"no_such_trigger\" }, \"actions\": [] }").isError(), "unknown trigger type must fail");
        helper.assertTrue(parse(helper, "{ \"trigger\": { \"type\": \"custom\", \"id\": \"a:b\" }, \"actions\": [ { \"type\": \"var\" } ] }").isError(),
                "a condition type used as an action must fail");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void varConditionComparesLooseValues(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("gold"), new StoryValue.Int(7)));
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("road"), new StoryValue.Str("north")));
        StoryContext context = StoryContext.of(player);

        expect(helper, condition(helper, "{ \"type\": \"var\", \"key\": \"gametest:gold\", \"op\": \">=\", \"value\": 7 }").test(context), true, "7 >= 7");
        expect(helper, condition(helper, "{ \"type\": \"var\", \"key\": \"gametest:gold\", \"op\": \"<\", \"value\": 7 }").test(context), false, "7 < 7");
        expect(helper, condition(helper, "{ \"type\": \"var\", \"key\": \"gametest:road\", \"value\": \"north\" }").test(context), true, "string equality");
        expect(helper, condition(helper, "{ \"type\": \"var\", \"key\": \"gametest:unset\", \"value\": 0 }").test(context), true, "unset number reads as 0");
        expect(helper, condition(helper, "{ \"type\": \"flag\", \"key\": \"gametest:unset\", \"value\": false }").test(context), true, "unset flag reads as false");
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storyEventRespectsConditionsAndOnce(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withEvents(helper, Map.of(key("bell_event"), """
                {
                  "trigger": { "type": "custom", "id": "gametest:bell" },
                  "conditions": { "type": "var", "key": "gametest:bell_gold", "op": ">=", "value": 5 },
                  "actions": { "type": "add_var", "key": "gametest:bell_count", "amount": 1 },
                  "once": "player"
                }
                """), () -> {
            fireCustom(player, "bell");
            expect(helper, stored(player, "bell_count"), Optional.empty(), "conditions not met yet");

            ok(() -> StoryState.set(player, StoryScope.PLAYER, key("bell_gold"), new StoryValue.Int(5)));
            fireCustom(player, "bell");
            fireCustom(player, "bell");
            expect(helper, stored(player, "bell_count"), Optional.of(new StoryValue.Int(1)), "runs once");
            helper.assertTrue(StoryState.getStored(player, StoryScope.PLAYER, StoryEvents.onceKey(key("bell_event"))).isPresent(), "once flag stored");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void flagChangedChainsEvents(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withEvents(helper, Map.of(key("chain"), """
                {
                  "trigger": { "type": "flag_changed", "key": "gametest:chain_a", "value": true },
                  "actions": { "type": "set_flag", "key": "gametest:chain_b" }
                }
                """), () -> {
            ok(() -> StoryState.set(player, StoryScope.PLAYER, key("chain_a"), new StoryValue.Bool(true)));
            expect(helper, stored(player, "chain_b"), Optional.of(new StoryValue.Bool(true)), "chained flag");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void selfTriggeringEventStops(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withEvents(helper, Map.of(key("loop"), """
                {
                  "trigger": { "type": "flag_changed", "key": "gametest:loop" },
                  "actions": { "type": "add_var", "key": "gametest:loop", "amount": 1 }
                }
                """), () -> {
            ok(() -> StoryState.set(player, StoryScope.PLAYER, key("loop"), new StoryValue.Int(0)));
            int value = stored(player, "loop").map(v -> ((StoryValue.Int) v).value()).orElse(-1);
            helper.assertTrue(value > 0 && value <= 16, "loop must stop at the depth limit, got " + value);
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void itemActionsAndCondition(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        StoryContext context = StoryContext.of(player);
        StoryEvent event = parse(helper, """
                {
                  "trigger": { "type": "custom", "id": "gametest:unused" },
                  "actions": [
                    { "type": "give_item", "item": "minecraft:emerald", "count": 70 },
                    { "type": "take_item", "item": "minecraft:emerald", "count": 65 }
                  ]
                }
                """).getOrThrow(message -> new GameTestAssertException("Parse failed: " + message));

        Actions.runAll(event.actions().subList(0, 1), context, "gametest", OnFail.ABORT);
        expect(helper, condition(helper, "{ \"type\": \"has_item\", \"item\": \"minecraft:emerald\", \"count\": 70 }").test(context), true, "70 emeralds given");
        Actions.runAll(event.actions().subList(1, 2), context, "gametest", OnFail.ABORT);
        expect(helper, player.getInventory().countItem(Items.EMERALD), 5, "emeralds left after take_item");
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardIsNotPaidWithoutPrice(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withEvents(helper, Map.of(key("trade"), """
                {
                  "trigger": { "type": "custom", "id": "gametest:trade" },
                  "actions": [
                    { "type": "take_item", "item": "minecraft:leather", "count": 10 },
                    { "type": "give_item", "item": "minecraft:diamond_sword" }
                  ],
                  "once": "player"
                }
                """), () -> {
            player.getInventory().add(new ItemStack(Items.LEATHER, 4));
            fireCustom(player, "trade");
            expect(helper, player.getInventory().countItem(Items.DIAMOND_SWORD), 0, "no sword without 10 leather");
            expect(helper, player.getInventory().countItem(Items.LEATHER), 4, "failed take_item takes nothing");

            player.getInventory().add(new ItemStack(Items.LEATHER, 6));
            fireCustom(player, "trade");
            expect(helper, player.getInventory().countItem(Items.DIAMOND_SWORD), 1, "aborted once-event can be retried");
            expect(helper, player.getInventory().countItem(Items.LEATHER), 0, "price taken");

            player.getInventory().add(new ItemStack(Items.LEATHER, 10));
            fireCustom(player, "trade");
            expect(helper, player.getInventory().countItem(Items.DIAMOND_SWORD), 1, "completed once-event does not repeat");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onFailContinueRunsTheRest(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withEvents(helper, Map.of(key("lenient"), """
                {
                  "trigger": { "type": "custom", "id": "gametest:lenient" },
                  "actions": [
                    { "type": "take_item", "item": "minecraft:leather", "count": 1 },
                    { "type": "set_flag", "key": "gametest:lenient_done" }
                  ],
                  "on_fail": "continue"
                }
                """), () -> {
            fireCustom(player, "lenient");
            expect(helper, stored(player, "lenient_done"), Optional.of(new StoryValue.Bool(true)), "later actions still run");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void areaEnteredFiresOnLoginOnlyWhenAsked(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 spawn = player.position();
        String area = "\"area\": { \"center\": [" + spawn.x + ", " + spawn.y + ", " + spawn.z + "], \"radius\": 3 }";
        withEvents(helper, Map.of(
                key("cursed_zone"), "{ \"trigger\": { \"type\": \"area_entered\", " + area + ", \"fire_on_login\": true },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:cursed_count\", \"amount\": 1 } }",
                key("plain_zone"), "{ \"trigger\": { \"type\": \"area_entered\", " + area + " },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:plain_count\", \"amount\": 1 } }"
        ), () -> {
            TriggerDispatcher.tickAreas(helper.getLevel().getServer());
            expect(helper, stored(player, "cursed_count"), Optional.of(new StoryValue.Int(1)), "fire_on_login fires right after login");
            expect(helper, stored(player, "plain_count"), Optional.empty(), "default area does not fire on login");

            player.setPos(spawn.x + 100, spawn.y, spawn.z);
            TriggerDispatcher.tickAreas(helper.getLevel().getServer());
            player.setPos(spawn.x, spawn.y, spawn.z);
            TriggerDispatcher.tickAreas(helper.getLevel().getServer());
            expect(helper, stored(player, "cursed_count"), Optional.of(new StoryValue.Int(2)), "walking back in fires again");
            expect(helper, stored(player, "plain_count"), Optional.of(new StoryValue.Int(1)), "default area fires on walking in");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void timeAndWeatherConditions(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        StoryContext context = StoryContext.of(player);
        ServerLevel level = helper.getLevel();
        long dayTime = level.getDayTime();
        float rain = level.getRainLevel(1f);
        float thunder = level.getThunderLevel(1f) / Math.max(rain, 1e-6f);
        Condition day = condition(helper, "{ \"type\": \"time\", \"period\": \"day\" }");
        Condition night = condition(helper, "{ \"type\": \"time\", \"period\": \"night\" }");
        Condition aroundMidnight = condition(helper, "{ \"type\": \"time\", \"min\": 23000, \"max\": 1000 }");
        Condition clear = condition(helper, "{ \"type\": \"weather\", \"weather\": \"clear\" }");
        Condition raining = condition(helper, "{ \"type\": \"weather\", \"weather\": \"rain\" }");
        Condition thundering = condition(helper, "{ \"type\": \"weather\", \"weather\": \"thunder\" }");
        try {
            level.setDayTime(24000L * 3 + 6000);
            expect(helper, List.of(day.test(context), night.test(context), aroundMidnight.test(context)), List.of(true, false, false), "noon on day 4");
            level.setDayTime(18000);
            expect(helper, List.of(day.test(context), night.test(context), aroundMidnight.test(context)), List.of(false, true, false), "midnight");
            level.setDayTime(500);
            expect(helper, aroundMidnight.test(context), true, "a range over the day's end wraps");

            level.setRainLevel(0f);
            level.setThunderLevel(0f);
            expect(helper, List.of(clear.test(context), raining.test(context), thundering.test(context)), List.of(true, false, false), "clear sky");
            level.setRainLevel(1f);
            expect(helper, List.of(clear.test(context), raining.test(context), thundering.test(context)), List.of(false, true, false), "rain");
            level.setThunderLevel(1f);
            expect(helper, List.of(clear.test(context), raining.test(context), thundering.test(context)), List.of(false, true, true), "thunderstorm");
        } finally {
            level.setDayTime(dayTime);
            level.setRainLevel(rain);
            level.setThunderLevel(thunder);
        }
        helper.assertTrue(LogicCodecs.CONDITION.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()),
                JsonParser.parseString("{ \"type\": \"time\" }")).isError(), "time without period, min or max must fail");
        level.getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void biomeEnteredFiresWhereThePlayerStands(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        String here = helper.getLevel().getBiome(player.blockPosition()).unwrapKey().orElseThrow().location().toString();
        String elsewhere = here.equals("minecraft:desert") ? "minecraft:jungle" : "minecraft:desert";
        withEvents(helper, Map.of(
                key("biome_login"), "{ \"trigger\": { \"type\": \"biome_entered\", \"biome\": \"" + here + "\", \"fire_on_login\": true },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:biome_login\", \"amount\": 1 } }",
                key("biome_plain"), "{ \"trigger\": { \"type\": \"biome_entered\", \"biome\": [\"" + here + "\"] },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:biome_plain\", \"amount\": 1 } }",
                key("biome_other"), "{ \"trigger\": { \"type\": \"biome_entered\", \"biome\": \"" + elsewhere + "\", \"fire_on_login\": true },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:biome_other\", \"amount\": 1 } }"
        ), () -> {
            TriggerDispatcher.tickAreas(helper.getLevel().getServer());
            TriggerDispatcher.tickAreas(helper.getLevel().getServer());
            expect(helper, stored(player, "biome_login"), Optional.of(new StoryValue.Int(1)), "fire_on_login fires once after login, in " + here);
            expect(helper, stored(player, "biome_plain"), Optional.empty(), "a biome the player was already in on login does not fire");
            expect(helper, stored(player, "biome_other"), Optional.empty(), "another biome does not fire");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void poiEnteredFiresNearAPointOfInterest(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        PoiManager pois = helper.getLevel().getPoiManager();
        BlockPos bell = player.blockPosition().offset(3, 0, 0);
        Holder<PoiType> meeting = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolderOrThrow(PoiTypes.MEETING);
        withEvents(helper, Map.of(
                key("village_square"), "{ \"trigger\": { \"type\": \"poi_entered\", \"poi\": \"minecraft:meeting\", \"radius\": 4 },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:square_visits\", \"amount\": 1 } }",
                key("far_square"), "{ \"trigger\": { \"type\": \"poi_entered\", \"poi\": \"minecraft:meeting\", \"radius\": 2 },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:far_visits\", \"amount\": 1 } }"
        ), () -> {
            MinecraftServer server = helper.getLevel().getServer();
            TriggerDispatcher.tickAreas(server);
            try {
                pois.add(bell, meeting);
                TriggerDispatcher.tickAreas(server);
                TriggerDispatcher.tickAreas(server);
                expect(helper, stored(player, "square_visits"), Optional.of(new StoryValue.Int(1)), "coming within the radius fires once");
                expect(helper, stored(player, "far_visits"), Optional.empty(), "a point of interest beyond the radius does not fire");
                pois.remove(bell);
                TriggerDispatcher.tickAreas(server);
                pois.add(bell, meeting);
                TriggerDispatcher.tickAreas(server);
                expect(helper, stored(player, "square_visits"), Optional.of(new StoryValue.Int(2)), "coming back fires again");
            } finally {
                if (pois.existsAtPosition(PoiTypes.MEETING, bell)) {
                    pois.remove(bell);
                }
            }
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cutscenesWorkWithAndWithoutTheMod(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        StoryContext context = StoryContext.of(player);
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var play = LogicCodecs.ACTIONS.parse(ops, JsonParser.parseString("{ \"type\": \"play_cutscene\", \"cutscene\": \"gametest:no_such_cutscene\" }"))
                .getOrThrow(GameTestAssertException::new);
        // Without the mod: not installed; with it: no such cutscene. Either way the action fails and says why.
        helper.assertFalse(Actions.runAll(play, context, "gametest cutscene", OnFail.ABORT), "an unknown or impossible cutscene fails");
        expect(helper, condition(helper, "{ \"type\": \"in_cutscene\" }").test(context), false, "watching nothing");
        withEvents(helper, Map.of(key("after_cutscene"), """
                { "trigger": { "type": "cutscene_finished", "cutscene": "gametest:prologue", "reason": "finish" },
                  "actions": { "type": "add_var", "key": "gametest:after_cutscene", "amount": 1 } }
                """), () -> {
            TriggerDispatcher.fire(Cutscenes.CUTSCENE_FINISHED.get(), context, new Cutscenes.Finished(key("prologue"), Cutscenes.Reason.INTERRUPT));
            TriggerDispatcher.fire(Cutscenes.CUTSCENE_FINISHED.get(), context, new Cutscenes.Finished(key("other"), Cutscenes.Reason.FINISH));
            TriggerDispatcher.fire(Cutscenes.CUTSCENE_FINISHED.get(), context, new Cutscenes.Finished(key("prologue"), Cutscenes.Reason.FINISH));
            expect(helper, stored(player, "after_cutscene"), Optional.of(new StoryValue.Int(1)), "only that cutscene played to the end");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void progressTriggersAndConditions(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        MinecraftServer server = helper.getLevel().getServer();
        StoryContext context = StoryContext.of(player);
        player.experienceLevel = 0;
        withEvents(helper, Map.of(
                key("on_level"), "{ \"trigger\": { \"type\": \"level_reached\", \"level\": 5 }, \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:levels\", \"amount\": 1 } }",
                key("on_jumps"), "{ \"trigger\": { \"type\": \"stat_reached\", \"stat\": \"minecraft:jump\", \"value\": 2 }, \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:jumps\", \"amount\": 1 } }",
                key("on_travel"), "{ \"trigger\": { \"type\": \"dimension_changed\", \"to\": \"minecraft:the_nether\" }, \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:travels\", \"amount\": 1 } }",
                key("on_stone"), "{ \"trigger\": { \"type\": \"advancement_earned\", \"advancement\": \"minecraft:story/mine_stone\" }, \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:stone\", \"amount\": 1 } }"), () -> {
            ProgressTriggers.checkValues(server);
            player.experienceLevel = 6;
            player.awardStat(Stats.JUMP);
            ProgressTriggers.checkValues(server);
            expect(helper, stored(player, "levels"), Optional.of(new StoryValue.Int(1)), "level 5 reached");
            expect(helper, stored(player, "jumps"), Optional.empty(), "one jump of two");
            player.awardStat(Stats.JUMP);
            player.experienceLevel = 7;
            ProgressTriggers.checkValues(server);
            expect(helper, stored(player, "jumps"), Optional.of(new StoryValue.Int(1)), "two jumps");
            expect(helper, stored(player, "levels"), Optional.of(new StoryValue.Int(1)), "not again past the level");

            TriggerDispatcher.fire(BuiltinTriggers.DIMENSION_CHANGED.get(), context, new ProgressTriggers.Travel(Level.OVERWORLD, Level.END));
            TriggerDispatcher.fire(BuiltinTriggers.DIMENSION_CHANGED.get(), context, new ProgressTriggers.Travel(Level.OVERWORLD, Level.NETHER));
            expect(helper, stored(player, "travels"), Optional.of(new StoryValue.Int(1)), "only into the Nether");
            var stone = server.getAdvancements().get(ResourceLocation.withDefaultNamespace("story/mine_stone"));
            helper.assertTrue(stone != null, "the vanilla advancement is loaded");
            TriggerDispatcher.fire(BuiltinTriggers.ADVANCEMENT_EARNED.get(), context, stone);
            expect(helper, stored(player, "stone"), Optional.of(new StoryValue.Int(1)), "the advancement");
        });
        expect(helper, condition(helper, "{ \"type\": \"level\", \"min\": 7, \"max\": 10 }").test(context), true, "level in range");
        expect(helper, condition(helper, "{ \"type\": \"stat\", \"stat\": \"minecraft:jump\", \"min\": 3 }").test(context), false, "two jumps of three");
        expect(helper, condition(helper, "{ \"type\": \"advancement\", \"advancement\": \"minecraft:story/mine_stone\", \"done\": false }").test(context), true,
                "not earned (the trigger was fired by hand)");
        expect(helper, condition(helper, "{ \"type\": \"advancement\", \"advancement\": \"gametest:no_such\" }").test(context), false, "unknown: never done");
        server.getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void waitAndScheduleRunTheRestLater(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        StoryContext context = StoryContext.of(player);
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var actions = LogicCodecs.ACTIONS.parse(ops, JsonParser.parseString("""
                [ { "type": "add_var", "key": "gametest:waited", "amount": 1 },
                  { "type": "schedule", "seconds": 0.25, "actions": { "type": "add_var", "key": "gametest:scheduled", "amount": 1 } },
                  { "type": "add_var", "key": "gametest:scheduled", "amount": 10 },
                  { "type": "wait", "seconds": 0.5 },
                  { "type": "add_var", "key": "gametest:waited", "amount": 1 } ]
                """)).getOrThrow(GameTestAssertException::new);
        helper.assertTrue(Actions.runAll(actions, context, "gametest wait", OnFail.ABORT), "the list is done at once");
        expect(helper, stored(player, "waited"), Optional.of(new StoryValue.Int(1)), "before the wait, at once");
        expect(helper, stored(player, "scheduled"), Optional.of(new StoryValue.Int(10)), "schedule lets the list go on");
        helper.assertTrue(ScheduledActions.pending(helper.getLevel().getServer(), player.getUUID()) == 2, "two tasks wait");
        helper.runAfterDelay(30, () -> {
            expect(helper, stored(player, "waited"), Optional.of(new StoryValue.Int(2)), "after the wait");
            expect(helper, stored(player, "scheduled"), Optional.of(new StoryValue.Int(11)), "the scheduled actions ran");
            helper.assertTrue(ScheduledActions.pending(helper.getLevel().getServer(), player.getUUID()) == 0, "nothing waits");
            helper.getLevel().getServer().getPlayerList().remove(player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void sceneActionsStageAScene(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        player.moveTo(origin.getX() + 0.5, origin.getY() + 1, origin.getZ() + 0.5);
        StoryContext context = StoryContext.of(player);
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        String json = """
                [ { "type": "spawn_entity", "entity": "minecraft:zombie", "count": 2, "offset": [2, 0, 0],
                    "tag": ["gametest_kairen", "gametest_boss"], "name": { "literal": "Kairen" }, "nbt": { "NoAI": true } },
                  { "type": "effect", "effect": "minecraft:slowness", "duration": 5, "amplifier": 1 },
                  { "type": "title", "title": { "literal": "Chapter II" }, "subtitle": { "literal": "The ash" } },
                  { "type": "sound_at", "sound": "minecraft:entity.wither.spawn", "offset": [0, 2, 0] },
                  { "type": "teleport", "pos": [%d, %d, %d], "yaw": 90 } ]
                """.formatted(origin.getX() + 3, origin.getY() + 1, origin.getZ() + 3);
        var actions = LogicCodecs.ACTIONS.parse(ops, JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new);
        helper.assertTrue(Actions.runAll(actions, context, "gametest scene", OnFail.ABORT), "every scene action runs");

        var spawned = helper.getLevel().getEntitiesOfClass(Zombie.class, player.getBoundingBox().inflate(16), zombie -> zombie.getTags().contains("gametest_kairen"));
        helper.assertTrue(spawned.size() == 2, "two zombies: " + spawned.size());
        Zombie kairen = spawned.getFirst();
        helper.assertTrue(kairen.getTags().contains("gametest_boss") && kairen.isNoAi() && kairen.isPersistenceRequired()
                && kairen.getCustomName() != null && kairen.getCustomName().getString().equals("Kairen"), "tags, NBT, name, kept from despawning");
        helper.assertTrue(player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) && player.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getAmplifier() == 1
                && player.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getDuration() <= 100, "the effect for 5 seconds at level II");
        helper.assertTrue(Math.abs(player.getX() - (origin.getX() + 3)) < 0.01 && Math.abs(player.getYRot() - 90) < 0.01, "teleported and turned");

        var clear = LogicCodecs.ACTIONS.parse(ops, JsonParser.parseString("{ \"type\": \"effect\", \"effect\": \"minecraft:slowness\", \"clear\": true }"))
                .getOrThrow(GameTestAssertException::new);
        Actions.runAll(clear, context, "gametest scene", OnFail.ABORT);
        helper.assertFalse(player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "clear takes it away");

        var onEntity = LogicCodecs.ACTIONS.parse(ops, JsonParser.parseString("{ \"type\": \"effect\", \"effect\": \"minecraft:glowing\", \"target\": \"entity\" }"))
                .getOrThrow(GameTestAssertException::new);
        helper.assertFalse(Actions.runAll(onEntity, context, "gametest scene", OnFail.ABORT), "\"entity\" without one fails");
        helper.assertTrue(Actions.runAll(onEntity, new StoryContext(player, kairen), "gametest scene", OnFail.ABORT) && kairen.hasEffect(MobEffects.GLOWING),
                "and works on the context entity");
        spawned.forEach(Entity::discard);
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scriptArgsReachTheHandlerAndSurviveEncoding(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        StoryContext context = StoryContext.of(player);
        List<JsonObject> received = new ArrayList<>();
        ScriptHooks.registerAction(key("spawn"), (ctx, args) -> {
            received.add(args.deepCopy());
            // A handler changing its arguments must not change the next run's.
            args.addProperty("boss", "changed");
        });
        ScriptHooks.registerCondition(key("near"), (ctx, args) -> args.has("radius") && args.get("radius").getAsInt() == 8);
        String json = "{ \"type\": \"kubejs\", \"id\": \"gametest:spawn\", \"args\": { \"boss\": \"gametest:wraith\", \"at\": [1, 64, 2] } }";
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var actions = LogicCodecs.ACTIONS.parse(ops, JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new);
        Actions.runAll(actions, context, "gametest", OnFail.ABORT);
        Actions.runAll(actions, context, "gametest", OnFail.ABORT);
        helper.assertTrue(received.size() == 2 && received.get(1).get("boss").getAsString().equals("gametest:wraith")
                && received.get(1).getAsJsonArray("at").size() == 3, "the handler got the args, unchanged by the run before: " + received);
        expect(helper, condition(helper, "{ \"type\": \"kubejs\", \"id\": \"gametest:near\", \"args\": { \"radius\": 8 } }").test(context), true, "condition args");
        expect(helper, condition(helper, "{ \"type\": \"kubejs\", \"id\": \"gametest:near\" }").test(context), false, "no args: an empty object");
        // Written back as read (files, the dev mode), and through NBT as definitions are synced.
        JsonObject written = LogicCodecs.ACTION.encodeStart(ops, actions.getFirst()).getOrThrow().getAsJsonObject();
        expect(helper, written.get("args"), JsonParser.parseString(json).getAsJsonObject().get("args"), "JSON round trip");
        var nbtOps = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
        var nbt = LogicCodecs.ACTION.encodeStart(nbtOps, actions.getFirst()).getOrThrow();
        expect(helper, LogicCodecs.ACTION.parse(nbtOps, nbt).getOrThrow(), actions.getFirst(), "NBT round trip");
        ScriptHooks.clear();
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scriptHooksAreUsedById(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        StoryContext context = StoryContext.of(player);
        Condition hooked = condition(helper, "{ \"type\": \"kubejs\", \"id\": \"gametest:always\" }");

        expect(helper, hooked.test(context), false, "missing hook fails closed");
        ScriptHooks.registerCondition(key("always"), ctx -> ctx.player() == player);
        expect(helper, hooked.test(context), true, "registered hook");
        helper.assertTrue(hooked instanceof LogicConditions.Script, "kubejs type");
        helper.assertTrue(!(hooked instanceof StateConditions.Flag), "sanity");
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static Optional<StoryValue> stored(ServerPlayer player, String path) {
        return StoryState.getStored(player, StoryScope.PLAYER, key(path));
    }

    @GameTest(template = "empty")
    public static void traceSaysWhichConditionFailedAndWhatRan(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        MinecraftServer server = helper.getLevel().getServer();
        Path file = StoryTrace.file(server);
        long start = io(() -> Files.exists(file) ? Files.size(file) : 0L);
        StoryTrace.enable(server, Set.of(player.getUUID()), List.of(player.getGameProfile().getName()));
        try {
            withEvents(helper, Map.of(key("traced_event"), """
                    {
                      "trigger": { "type": "custom", "id": "gametest:traced" },
                      "conditions": [
                        { "type": "flag", "key": "gametest:trace_gate" },
                        { "type": "any", "conditions": [
                          { "type": "var", "key": "gametest:trace_gold", "op": ">=", "value": 5 },
                          { "type": "has_item", "item": "minecraft:diamond" } ] } ],
                      "actions": [
                        { "type": "add_var", "key": "gametest:trace_count", "amount": 1 },
                        { "type": "kubejs", "id": "gametest:no_such_script" } ]
                    }
                    """), () -> {
                fireCustom(player, "traced");
                ok(() -> StoryState.set(player, StoryScope.PLAYER, key("trace_gate"), new StoryValue.Bool(true)));
                fireCustom(player, "traced");
                ok(() -> StoryState.set(player, StoryScope.PLAYER, key("trace_gold"), new StoryValue.Int(5)));
                fireCustom(player, "traced");
            });
        } finally {
            StoryTrace.disable();
        }
        List<JsonObject> lines = io(() -> {
            List<JsonObject> read = new ArrayList<>();
            try (var in = Files.newInputStream(file)) {
                in.skipNBytes(start);
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isBlank()) {
                        read.add(JsonParser.parseString(line).getAsJsonObject());
                    }
                }
            }
            return read;
        });
        List<JsonObject> checks = lines.stream().filter(line -> line.get("kind").getAsString().equals("conditions")).toList();
        helper.assertTrue(lines.stream().filter(line -> line.get("kind").getAsString().equals("trigger")
                && line.get("source").getAsString().equals("story event gametest:traced_event")).count() == 3, "three triggers traced: " + lines);
        helper.assertTrue(checks.size() == 3, "three checks traced: " + checks);
        JsonObject first = checks.get(0).getAsJsonArray("failed").get(0).getAsJsonObject();
        helper.assertTrue(first.get("path").getAsString().equals("[0]") && first.get("why").getAsString().contains("gametest:trace_gate"),
                "the flag is the reason first: " + first);
        var second = checks.get(1).getAsJsonArray("failed");
        helper.assertTrue(second.size() == 2 && second.get(0).getAsJsonObject().get("path").getAsString().equals("[1].conditions[0]")
                && second.get(0).getAsJsonObject().get("why").getAsString().contains("is unset, wanted >= 5")
                && second.get(1).getAsJsonObject().get("why").getAsString().equals("has 0, wanted 1"), "both alternatives of any: " + second);
        helper.assertTrue(checks.get(2).get("passed").getAsBoolean(), "then it passes");
        helper.assertTrue(lines.stream().anyMatch(line -> line.get("kind").getAsString().equals("action") && line.get("result").getAsString().equals("ok")
                && line.get("type").getAsString().equals("storybound:add_var")), "the action that ran");
        helper.assertTrue(lines.stream().anyMatch(line -> line.get("kind").getAsString().equals("note")
                && line.get("message").getAsString().contains("no script registers the action")), "the missing script");
        helper.assertTrue(lines.getLast().get("kind").getAsString().equals("trace_off"), "the end is marked");
        server.getPlayerList().remove(player);
        helper.succeed();
    }

    private static void fireCustom(ServerPlayer player, String id) {
        TriggerDispatcher.fire(BuiltinTriggers.CUSTOM.get(), StoryContext.of(player), key(id));
    }

    private static DataResult<StoryEvent> parse(GameTestHelper helper, String json) {
        return StoryEvent.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), JsonParser.parseString(json));
    }

    private static Condition condition(GameTestHelper helper, String json) {
        return LogicCodecs.CONDITION.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()), JsonParser.parseString(json))
                .getOrThrow(message -> new GameTestAssertException("Condition parse failed: " + message));
    }

    /** Installs story events for the duration of {@code body}, then restores the loaded ones. */
    private static void withEvents(GameTestHelper helper, Map<ResourceLocation, String> events, Runnable body) {
        Map<ResourceLocation, StoryEvent> previous = CoreDefinitions.EVENTS.serverStore().all();
        Map<ResourceLocation, StoryEvent> installed = new LinkedHashMap<>(previous);
        events.forEach((id, json) -> installed.put(id, parse(helper, json).getOrThrow(message -> new GameTestAssertException("Parse failed: " + message))));
        CoreDefinitions.EVENTS.serverStore().replace(installed);
        StoryEvents.rebuild();
        try {
            body.run();
        } finally {
            CoreDefinitions.EVENTS.serverStore().replace(previous);
            StoryEvents.rebuild();
        }
    }

    private static void ok(StoryAction action) {
        try {
            action.run();
        } catch (StoryException e) {
            throw new GameTestAssertException("Unexpected failure: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static <T> T io(IoSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new GameTestAssertException("Unexpected I/O failure: " + e.getMessage());
        }
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }

    @FunctionalInterface
    private interface StoryAction {
        void run() throws StoryException;
    }
}
