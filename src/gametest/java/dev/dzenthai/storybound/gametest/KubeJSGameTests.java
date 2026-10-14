package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.Quests;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The KubeJS integration against {@code src/gametest/kubejs/server_scripts/storybound_test.js}.
 * Lives in its own namespace and runs only with {@code -Pdev_with_kubejs=true}.
 */
@GameTestHolder(Storybound.MOD_ID + "_kubejs")
@PrefixGameTestTemplate(false)
public final class KubeJSGameTests {
    private KubeJSGameTests() {}

    @GameTest(template = "empty")
    public static void scriptsDriveStoryLogic(GameTestHelper helper) {
        if (!ModList.get().isLoaded("kubejs")) {
            helper.fail("KubeJS is not installed; run with -Pdev_with_kubejs=true");
            return;
        }
        // Not logged in through PlayerList: KubeJS would send its sync packet to the fake connection and crash.
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "kubejs-test"), ClientInformation.createDefault());
        StoryContext context = StoryContext.of(player);

        helper.assertTrue(ScriptHooks.hasCondition(key("script_condition")), "registerConditions ran");
        helper.assertTrue(ScriptHooks.hasAction(key("script_action")), "registerActions ran");

        Condition condition = LogicCodecs.CONDITION.parse(ops(helper), JsonParser.parseString("{ \"type\": \"kubejs\", \"id\": \"gametest:script_condition\" }"))
                .getOrThrow(message -> new GameTestAssertException(message));
        helper.assertTrue(condition.test(context), "script condition returns true");

        List<Action> actions = LogicCodecs.ACTIONS.parse(ops(helper), JsonParser.parseString("{ \"type\": \"kubejs\", \"id\": \"gametest:script_action\" }"))
                .getOrThrow(message -> new GameTestAssertException(message));
        Actions.runAll(actions, context, "gametest", OnFail.ABORT);
        expect(helper, stored(player, "script_action_ran"), Optional.of(new StoryValue.Bool(true)), "script action wrote a flag");

        try {
            StoryState.set(player, StoryScope.PLAYER, key("watched"), new StoryValue.Int(7));
        } catch (StoryException e) {
            throw new GameTestAssertException(e.getMessage());
        }
        expect(helper, stored(player, "watched_seen"), Optional.of(new StoryValue.Int(7)), "flagChanged listener got the new value");

        TriggerDispatcher.fireCustom(player, key("script_trigger"));
        expect(helper, stored(player, "script_trigger_count"), Optional.of(new StoryValue.Int(1)), "customTrigger listener ran once");

        ResourceLocation quest = key("kjs_quest");
        Map<ResourceLocation, QuestDefinition> previous = QuestRegistries.QUESTS.serverStore().all();
        Map<ResourceLocation, QuestDefinition> installed = new LinkedHashMap<>(previous);
        installed.put(quest, QuestDefinition.CODEC.parse(ops(helper), JsonParser.parseString(
                "{ \"title\": \"gametest.quest\", \"stages\": [ { \"id\": \"a\" }, { \"id\": \"b\" } ] }"))
                .getOrThrow(message -> new GameTestAssertException(message)));
        QuestRegistries.QUESTS.serverStore().replace(installed);
        try {
            Quests.start(player, quest);
            expect(helper, stored(player, "kjs_quest_state"), Optional.of(new StoryValue.Str("active")), "questStateChanged on start");
            expect(helper, stored(player, "kjs_quest_stage"), Optional.of(new StoryValue.Str("a")), "questStageChanged on start");
            Quests.setStage(player, quest, "b");
            expect(helper, stored(player, "kjs_quest_stage"), Optional.of(new StoryValue.Str("b")), "questStageChanged on a new stage");
            TriggerDispatcher.fireCustom(player, key("kjs_complete"));
            expect(helper, stored(player, "kjs_quest_state"), Optional.of(new StoryValue.Str("completed")), "binding completed the quest");
            expect(helper, stored(player, "kjs_read_state"), Optional.of(new StoryValue.Str("completed")), "binding reads the quest state");
        } finally {
            QuestRegistries.QUESTS.serverStore().replace(previous);
        }
        helper.succeed();
    }

    private static RegistryOps<com.google.gson.JsonElement> ops(GameTestHelper helper) {
        return RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static Optional<StoryValue> stored(ServerPlayer player, String path) {
        return StoryState.getStored(player, StoryScope.PLAYER, key(path));
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }
}
