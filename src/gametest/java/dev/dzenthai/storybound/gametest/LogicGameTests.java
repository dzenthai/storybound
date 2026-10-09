package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import dev.dzenthai.storybound.core.logic.condition.StateConditions;
import dev.dzenthai.storybound.core.logic.trigger.BuiltinTriggers;
import dev.dzenthai.storybound.core.logic.trigger.CoreTriggers;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The logic language end to end: JSON parsing, story events, trigger chains and actions. */
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

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }

    @FunctionalInterface
    private interface StoryAction {
        void run() throws StoryException;
    }
}
