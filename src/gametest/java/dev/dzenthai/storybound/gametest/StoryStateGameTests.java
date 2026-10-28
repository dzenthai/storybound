package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.state.FlagDeclaration;
import dev.dzenthai.storybound.core.state.PartyManager;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.WorldStoryData;
import java.util.ArrayList;
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
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Story State on a real server: player attachment, world and party SavedData, events. */
// makeMockServerPlayerInLevel is marked for removal in later versions, but it is the standard way in 1.21.1.
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoryStateGameTests {
    private StoryStateGameTests() {}

    @GameTest(template = "empty")
    public static void playerValueSurvivesDeath(GameTestHelper helper) {
        PlayerList players = helper.getLevel().getServer().getPlayerList();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ResourceLocation key = key("player_death");

        ok(() -> StoryState.set(player, StoryScope.PLAYER, key, new StoryValue.Int(5)));
        ServerPlayer respawned = players.respawn(player, false, Entity.RemovalReason.KILLED);

        expect(helper, StoryState.getStored(respawned, StoryScope.PLAYER, key), Optional.of(new StoryValue.Int(5)), "player value after respawn");
        players.remove(respawned);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void worldValueIsSharedAndPersisted(GameTestHelper helper) {
        ServerPlayer first = helper.makeMockServerPlayerInLevel();
        ServerPlayer second = helper.makeMockServerPlayerInLevel();
        ResourceLocation key = key("world_shared");

        ok(() -> StoryState.set(first, StoryScope.WORLD, key, new StoryValue.Bool(true)));

        WorldStoryData world = WorldStoryData.get(helper.getLevel().getServer());
        expect(helper, StoryState.getStored(second, StoryScope.WORLD, key), Optional.of(new StoryValue.Bool(true)), "world value seen by another player");
        expect(helper, world.container().get(key), Optional.of(new StoryValue.Bool(true)), "world SavedData content");
        helper.assertTrue(world.isDirty(), "world SavedData should be marked dirty");
        removeAll(helper, first, second);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partyProgressIsSharedAndCopiedOnLeave(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ResourceLocation key = key("party_progress");

        ok(() -> PartyManager.create(leader, "Test party"));
        ok(() -> StoryState.set(leader, StoryScope.PARTY, key, new StoryValue.Int(1)));
        expect(helper, StoryState.getStored(member, StoryScope.PARTY, key), Optional.empty(), "outsider party value");

        ok(() -> PartyManager.invite(leader, member));
        ok(() -> PartyManager.accept(member));
        expect(helper, StoryState.getStored(member, StoryScope.PARTY, key), Optional.of(new StoryValue.Int(1)), "joined member party value");

        ok(() -> StoryState.set(member, StoryScope.PARTY, key, new StoryValue.Int(2)));
        expect(helper, StoryState.getStored(leader, StoryScope.PARTY, key), Optional.of(new StoryValue.Int(2)), "leader sees member's change");

        ok(() -> PartyManager.leave(member));
        ok(() -> StoryState.set(leader, StoryScope.PARTY, key, new StoryValue.Int(3)));
        expect(helper, StoryState.getStored(member, StoryScope.PARTY, key), Optional.of(new StoryValue.Int(2)), "leaver keeps a copy");

        ok(() -> PartyManager.disband(leader));
        expect(helper, StoryState.getStored(leader, StoryScope.PARTY, key), Optional.of(new StoryValue.Int(3)), "leader keeps a copy after disband");
        removeAll(helper, leader, member);
        helper.succeed();
    }

    /**
     * A newcomer brings what the party has not got and the party keeps what it has; a leaver gets the party's laid over
     * their own; a party flag's change reaches the stories of every member online.
     */
    @GameTest(template = "empty")
    public static void joiningBringsWhatThePartyLacksAndPartyFlagsReachEveryone(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ResourceLocation shared = key("join_shared");
        ResourceLocation brought = key("join_brought");
        ResourceLocation quest = key("join_quest");
        ResourceLocation gate = key("join_gate");

        ok(() -> PartyManager.create(leader, "Merging party"));
        ok(() -> StoryState.set(leader, StoryScope.PARTY, shared, new StoryValue.Int(1)));
        // The newcomer's own, from before joining: a value the party has too, one it has not, a quest half done alone.
        ok(() -> StoryState.set(member, StoryScope.PARTY, shared, new StoryValue.Int(9)));
        ok(() -> StoryState.set(member, StoryScope.PARTY, brought, new StoryValue.Int(5)));
        StoryState.setQuest(member, StoryScope.PARTY, quest, QuestProgress.started("half"));
        // A quest both have: the newcomer finished what the party has only begun; another the party is ahead in.
        ResourceLocation raced = key("join_raced");
        ResourceLocation ahead = key("join_ahead");
        StoryState.setQuest(leader, StoryScope.PARTY, raced, QuestProgress.started("road"));
        StoryState.setQuest(member, StoryScope.PARTY, raced, QuestProgress.started("road").finished(QuestStatus.COMPLETED, 1));
        StoryState.setQuest(leader, StoryScope.PARTY, ahead, QuestProgress.started("road").withStage("village"));
        StoryState.setQuest(member, StoryScope.PARTY, ahead, QuestProgress.started("road"));

        ok(() -> PartyManager.invite(leader, member));
        ok(() -> PartyManager.accept(member));
        expect(helper, StoryState.getQuest(leader, StoryScope.PARTY, raced).map(QuestProgress::status), Optional.of(QuestStatus.COMPLETED),
                "the newcomer's further progress wins: the party goes on from there");
        expect(helper, StoryState.getQuest(leader, StoryScope.PARTY, ahead).flatMap(QuestProgress::stage), Optional.of("village"),
                "and the party's, where it is further");
        expect(helper, StoryState.getStored(leader, StoryScope.PARTY, shared), Optional.of(new StoryValue.Int(1)), "what the party has stays");
        expect(helper, StoryState.getStored(leader, StoryScope.PARTY, brought), Optional.of(new StoryValue.Int(5)), "what it lacked is brought");
        expect(helper, StoryState.getQuest(leader, StoryScope.PARTY, quest).flatMap(QuestProgress::stage), Optional.of("half"),
                "so is a quest half done alone");

        Map<ResourceLocation, StoryEvent> previous = CoreDefinitions.EVENTS.serverStore().all();
        Map<ResourceLocation, StoryEvent> events = new LinkedHashMap<>(previous);
        events.put(key("join_gate_opened"), StoryEvent.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()),
                JsonParser.parseString("{ \"trigger\": { \"type\": \"flag_changed\", \"key\": \"gametest:join_gate\", \"value\": true },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:join_gate_seen\", \"scope\": \"world\", \"amount\": 1 } }"))
                .getOrThrow(GameTestAssertException::new));
        CoreDefinitions.EVENTS.serverStore().replace(events);
        StoryEvents.rebuild();
        try {
            ok(() -> StoryState.set(member, StoryScope.PARTY, gate, new StoryValue.Bool(true)));
            expect(helper, StoryState.getStored(leader, StoryScope.WORLD, key("join_gate_seen")), Optional.of(new StoryValue.Int(2)),
                    "the party flag's story ran for both members");
        } finally {
            CoreDefinitions.EVENTS.serverStore().replace(previous);
            StoryEvents.rebuild();
        }

        ok(() -> StoryState.set(leader, StoryScope.PARTY, shared, new StoryValue.Int(3)));
        ok(() -> PartyManager.leave(member));
        expect(helper, StoryState.getStored(member, StoryScope.PARTY, shared), Optional.of(new StoryValue.Int(3)), "the party's wins on leaving");
        expect(helper, StoryState.getStored(member, StoryScope.PARTY, brought), Optional.of(new StoryValue.Int(5)), "and the leaver keeps the rest");
        ok(() -> PartyManager.disband(leader));
        removeAll(helper, leader, member);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void declarationFixesScopeAndType(GameTestHelper helper) {
        ResourceLocation key = key("declared");
        Map<ResourceLocation, FlagDeclaration> previous = CoreDefinitions.FLAGS.serverStore().all();
        Map<ResourceLocation, FlagDeclaration> withTestFlag = new LinkedHashMap<>(previous);
        withTestFlag.put(key, new FlagDeclaration(new StoryValue.Int(7), StoryScope.WORLD, false, Optional.empty()));
        CoreDefinitions.FLAGS.serverStore().replace(withTestFlag);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            expect(helper, StoryState.declaredScope(key), StoryScope.WORLD, "declared scope");
            expect(helper, StoryState.get(player, StoryScope.WORLD, key), Optional.of(new StoryValue.Int(7)), "declared default");
            fails(helper, () -> StoryState.set(player, StoryScope.PLAYER, key, new StoryValue.Int(1)), "write to a foreign scope");
            fails(helper, () -> StoryState.set(player, StoryScope.WORLD, key, new StoryValue.Bool(true)), "write of a foreign type");
            ok(() -> StoryState.add(player, StoryScope.WORLD, key, 3));
            expect(helper, StoryState.get(player, StoryScope.WORLD, key), Optional.of(new StoryValue.Int(10)), "default + 3");
        } finally {
            CoreDefinitions.FLAGS.serverStore().replace(previous);
            removeAll(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changesPublishEvents(GameTestHelper helper) {
        ResourceLocation key = key("events");
        Recorder recorder = new Recorder(key);
        NeoForge.EVENT_BUS.register(recorder);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            ok(() -> StoryState.set(player, StoryScope.PLAYER, key, new StoryValue.Int(1)));
            ok(() -> StoryState.set(player, StoryScope.PLAYER, key, new StoryValue.Int(1)));
            ok(() -> StoryState.clear(player, StoryScope.PLAYER, key));
        } finally {
            NeoForge.EVENT_BUS.unregister(recorder);
            removeAll(helper, player);
        }

        expect(helper, recorder.events.size(), 2, "events (unchanged value must not fire)");
        StoryValueChangedEvent set = recorder.events.get(0);
        expect(helper, set.getNewValue(), Optional.of(new StoryValue.Int(1)), "first event new value");
        expect(helper, set.getOwner(), player.getUUID(), "first event owner");
        expect(helper, recorder.events.get(1).getNewValue(), Optional.empty(), "clear event new value");
        helper.succeed();
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static void ok(StoryAction action) {
        try {
            action.run();
        } catch (StoryException e) {
            throw new GameTestAssertException("Unexpected failure: " + e.getMessage());
        }
    }

    private static void fails(GameTestHelper helper, StoryAction action, String what) {
        try {
            action.run();
        } catch (StoryException expected) {
            return;
        }
        helper.fail("Expected rejection: " + what);
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }

    private static void removeAll(GameTestHelper helper, ServerPlayer... players) {
        for (ServerPlayer player : players) {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    @FunctionalInterface
    private interface StoryAction {
        void run() throws StoryException;
    }

    public static final class Recorder {
        private final ResourceLocation key;
        private final List<StoryValueChangedEvent> events = new ArrayList<>();

        Recorder(ResourceLocation key) {
            this.key = key;
        }

        @SubscribeEvent
        public void onChanged(StoryValueChangedEvent event) {
            if (event.getKey().equals(key)) {
                events.add(event);
            }
        }
    }
}
