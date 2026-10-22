package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.state.Migration;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryMigrations;
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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Migrations: renamed ids carried over into saved progress. */
// makeMockServerPlayerInLevel is marked for removal in later versions, but it is the standard way in 1.21.1.
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MigrationGameTests {
    private static final ResourceLocation MIGRATION = key("migration_test");
    private static final ResourceLocation LIVE_QUEST = key("mig_live_quest");

    private MigrationGameTests() {}

    @GameTest(template = "empty")
    public static void renamedIdsMoveInSaves(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("mig_old_flag"), new StoryValue.Int(3)));
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("mig_old_taken"), new StoryValue.Int(1)));
        ok(() -> StoryState.set(player, StoryScope.PLAYER, key("mig_new_taken"), new StoryValue.Int(7)));
        ok(() -> StoryState.set(player, StoryScope.PLAYER, Storybound.id("once/gametest/mig_old_event"), new StoryValue.Bool(true)));
        ok(() -> StoryState.set(player, StoryScope.PLAYER, Storybound.id("dialogue_once/gametest/mig_old_dialogue/start/yes"), new StoryValue.Bool(true)));
        StoryState.setQuest(player, StoryScope.PLAYER, key("mig_old_quest"),
                new QuestProgress(QuestStatus.ACTIVE, Optional.of("old_stage"), Map.of("old_obj", 2), List.of("first")));
        StoryState.setQuest(player, StoryScope.WORLD, key("mig_old_world_quest"), QuestProgress.started("a"));
        StoryState.setQuest(player, StoryScope.PLAYER, LIVE_QUEST, QuestProgress.started("a"));
        Quests.track(player, key("mig_old_quest"));
        DefinitionStore<QuestDefinition> quests = QuestRegistries.QUESTS.serverStore();
        put(quests, LIVE_QUEST, QuestDefinition.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()),
                JsonParser.parseString("{ \"title\": \"gametest.quest.live\", \"stages\": [ { \"id\": \"a\" } ] }")).getOrThrow(GameTestAssertException::new));
        put(CoreDefinitions.MIGRATIONS.serverStore(), MIGRATION, Migration.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                { "flags": { "gametest:mig_old_flag": "gametest:mig_mid_flag", "gametest:mig_mid_flag": "gametest:mig_new_flag",
                             "gametest:mig_old_taken": "gametest:mig_new_taken" },
                  "quests": { "gametest:mig_old_quest": "gametest:mig_new_quest", "gametest:mig_old_world_quest": "gametest:mig_new_world_quest",
                              "gametest:mig_live_quest": "gametest:mig_elsewhere" },
                  "stages": { "gametest:mig_new_quest": { "old_stage": "new_stage", "first": "opening" } },
                  "objectives": { "gametest:mig_new_quest": { "old_obj": "new_obj" } },
                  "events": { "gametest:mig_old_event": "gametest:mig_new_event" },
                  "dialogues": { "gametest:mig_old_dialogue": "gametest:mig_new_dialogue" } }
                """)).getOrThrow(GameTestAssertException::new));
        try {
            StoryMigrations.reload();
            StoryMigrations.migrate(helper.getLevel().getServer());
            helper.assertTrue(StoryMigrations.migrate(player) > 0, "something moved");
            expect(helper, StoryState.getStored(player, StoryScope.PLAYER, key("mig_new_flag")), Optional.of(new StoryValue.Int(3)), "a flag renamed twice ends at the last name");
            expect(helper, StoryState.getStored(player, StoryScope.PLAYER, key("mig_old_flag")), Optional.empty(), "the old flag is gone");
            expect(helper, StoryState.getStored(player, StoryScope.PLAYER, key("mig_new_taken")), Optional.of(new StoryValue.Int(7)), "a value already under the new id wins");
            expect(helper, StoryState.getStored(player, StoryScope.PLAYER, Storybound.id("once/gametest/mig_new_event")), Optional.of(new StoryValue.Bool(true)), "an event's once mark");
            expect(helper, StoryState.getStored(player, StoryScope.PLAYER, Storybound.id("dialogue_once/gametest/mig_new_dialogue/start/yes")),
                    Optional.of(new StoryValue.Bool(true)), "a dialogue option's once mark keeps its node and option");
            QuestProgress moved = StoryState.getQuest(player, StoryScope.PLAYER, key("mig_new_quest")).orElse(null);
            helper.assertTrue(moved != null && moved.stage().equals(Optional.of("new_stage")) && moved.count("new_obj") == 2 && moved.log().equals(List.of("opening")),
                    "the quest with its stage, objective counter and log renamed: " + moved);
            expect(helper, StoryState.getQuest(player, StoryScope.WORLD, key("mig_new_world_quest")).isPresent(), true, "a world quest");
            expect(helper, StoryState.getQuest(player, StoryScope.PLAYER, LIVE_QUEST).isPresent(), true, "a quest still defined is not moved");
            expect(helper, Quests.pinned(player), List.of(key("mig_new_quest")), "the pin follows the quest");
            expect(helper, StoryMigrations.migrate(player), 0, "a second run changes nothing");
        } finally {
            remove(CoreDefinitions.MIGRATIONS.serverStore(), MIGRATION);
            remove(quests, LIVE_QUEST);
            StoryMigrations.reload();
            StoryState.setQuest(player, StoryScope.WORLD, key("mig_new_world_quest"), null);
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    // Tests run together: only this test's own ids are added and removed, never a whole store restored.
    private static <T> void put(DefinitionStore<T> store, ResourceLocation id, T value) {
        Map<ResourceLocation, T> next = new LinkedHashMap<>(store.all());
        next.put(id, value);
        store.replace(next);
    }

    private static <T> void remove(DefinitionStore<T> store, ResourceLocation id) {
        Map<ResourceLocation, T> next = new LinkedHashMap<>(store.all());
        next.remove(id);
        store.replace(next);
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws StoryException;
    }
}
