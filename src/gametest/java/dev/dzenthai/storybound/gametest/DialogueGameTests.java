package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.WorldStoryData;
import dev.dzenthai.storybound.module.dialogue.CameraCue;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition;
import dev.dzenthai.storybound.module.dialogue.DialogueLogic;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.dialogue.NpcBinding;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dialogues on a real server: the node graph, reply checks, parallel sessions, interruptions, timers, NPC bindings, validation. */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DialogueGameTests {
    private static final ResourceLocation TALK = key("talk");
    private static final ResourceLocation ASIDE = key("aside");

    /** Every node type; %1$s prefixes the world counters so parallel tests do not share them. */
    private static final String TALK_JSON = """
            { "on_abort": { "type": "add_var", "key": "gametest:%1$s_aborts", "scope": "world", "amount": 1 },
              "closable": %2$s,
              "start": "hello",
              "nodes": {
                "hello": { "type": "line", "speaker": "npc", "text": "gametest.hello", "next": "ask" },
                "ask": { "type": "choice", "options": [
                  { "id": "hidden", "text": "gametest.hidden", "conditions": { "type": "flag", "key": "gametest:never" }, "next": "@end" },
                  { "id": "locked", "text": "gametest.locked", "conditions": { "type": "flag", "key": "gametest:never" },
                    "locked": "show", "locked_text": "gametest.locked.hint", "next": "@end" },
                  { "id": "gift", "text": "gametest.gift", "once": true,
                    "actions": { "type": "add_var", "key": "gametest:%1$s_gifts", "scope": "world", "amount": 1 }, "next": "branch" },
                  { "id": "bye", "text": "gametest.bye", "next": "bye" } ] },
                "branch": { "type": "condition", "conditions": { "type": "var", "key": "gametest:%1$s_gifts", "scope": "world", "op": ">=", "value": 1 },
                            "then": "act", "else": "@end" },
                "act": { "type": "action", "actions": { "type": "add_var", "key": "gametest:%1$s_acts", "scope": "world", "amount": 1 }, "next": "dice" },
                "dice": { "type": "random", "options": [ { "weight": 3, "next": "hop" } ] },
                "hop": { "type": "jump", "node": "sub" },
                "sub": { "type": "call", "dialogue": "gametest:aside", "next": "ask" },
                "bye": { "type": "end", "actions": { "type": "add_var", "key": "gametest:%1$s_byes", "scope": "world", "amount": 1 } }
              } }
            """;
    private static final String ASIDE_JSON = """
            { "start": "a", "nodes": { "a": { "type": "line", "speaker": "npc", "text": "gametest.aside" } } }
            """;

    private DialogueGameTests() {}

    @GameTest(template = "empty")
    public static void dialogueWalksEveryNodeType(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withDialogues(helper, "walk", true, () -> with(helper, CoreDefinitions.EVENTS.serverStore(), StoryEvent.CODEC, Map.of(key("walk_ended"),
                "{ \"trigger\": { \"type\": \"dialogue_ended\", \"dialogue\": \"gametest:talk\", \"node\": \"bye\" },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:walk_ended\", \"scope\": \"world\", \"amount\": 1 } }"), () -> {
            StoryEvents.rebuild();
            helper.assertTrue(Dialogues.start(player, TALK, null), "starts");
            helper.assertFalse(Dialogues.start(player, TALK, null), "a player has one dialogue at a time");
            expect(helper, state(player).node(), "hello", "first line");

            advance(player);
            Dialogues.State choice = state(player);
            helper.assertTrue(choice.waitingForChoice(), "choice after the line");
            expect(helper, choice.shownOptions(), 3, "hidden option not shown, locked one shown");
            helper.assertFalse(Dialogues.choose(player, choice.session(), choice.step(), 0), "a locked option cannot be chosen");

            choose(player, 1);
            expect(helper, world(helper, "walk_gifts"), 1, "option actions ran");
            expect(helper, world(helper, "walk_acts"), 1, "condition took 'then', action node ran");
            expect(helper, state(player).dialogue(), ASIDE, "random, jump and call led into the called dialogue");

            advance(player);
            expect(helper, state(player).node(), "ask", "the call returned to its next");
            expect(helper, state(player).shownOptions(), 2, "a once option is gone after being chosen");

            choose(player, 1);
            expect(helper, Dialogues.state(player), Optional.empty(), "end node finished the dialogue");
            expect(helper, world(helper, "walk_byes"), 1, "end node actions ran");
            expect(helper, world(helper, "walk_ended"), 1, "dialogue_ended fired with the last node");

            Dialogues.start(player, TALK, null);
            advance(player);
            expect(helper, state(player).shownOptions(), 2, "once is remembered for the player");
            helper.assertTrue(Dialogues.leave(player, state(player).session()), "ESC leaves a closable dialogue");
            expect(helper, world(helper, "walk_aborts"), 1, "on_abort ran");
            expect(helper, world(helper, "walk_ended"), 1, "an interrupted dialogue does not fire dialogue_ended");
        }));
        StoryEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void forgedRepliesAreRejected(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ServerPlayer other = helper.makeMockServerPlayerInLevel();
        withDialogues(helper, "forged", false, () -> {
            Dialogues.start(player, TALK, null);
            Dialogues.State line = state(player);
            helper.assertFalse(Dialogues.advance(player, line.session(), line.step() + 1000), "unknown step");
            helper.assertFalse(Dialogues.advance(player, line.session() + 1000, line.step()), "unknown session");
            helper.assertFalse(Dialogues.choose(player, line.session(), line.step(), 0), "no choice is shown");
            helper.assertFalse(Dialogues.advance(other, line.session(), line.step()), "another player's session");
            helper.assertFalse(Dialogues.leave(player, line.session()), "a dialogue that is not closable cannot be left");
            expect(helper, state(player).node(), "hello", "nothing moved");

            advance(player);
            Dialogues.State choice = state(player);
            helper.assertFalse(Dialogues.choose(player, choice.session(), choice.step(), 99), "index beyond the shown options");
            helper.assertFalse(Dialogues.choose(player, choice.session(), choice.step(), -5), "negative index");
            helper.assertFalse(Dialogues.advance(player, choice.session(), choice.step()), "advance while a choice waits");
            helper.assertFalse(Dialogues.choose(player, choice.session(), line.step(), 2), "a stale step");
            helper.assertTrue(state(player).waitingForChoice(), "still waiting for a real choice");
            expect(helper, world(helper, "forged_gifts"), 0, "no forged reply ran an option");
            Dialogues.interrupt(player, EndReason.STOPPED);
        });
        remove(helper, player);
        remove(helper, other);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playersTalkToOneNpcInParallel(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        ServerPlayer first = near(helper, helper.makeMockServerPlayerInLevel(), 2, 2, 1);
        ServerPlayer second = near(helper, helper.makeMockServerPlayerInLevel(), 1, 2, 2);
        ServerPlayer watcher = near(helper, helper.makeMockServerPlayerInLevel(), 3, 2, 3);
        ServerPlayer far = near(helper, helper.makeMockServerPlayerInLevel(), 1, 2, 40);
        withDialogues(helper, "parallel", true, () -> {
            helper.assertTrue(Dialogues.start(first, TALK, npc), "first player starts");
            helper.assertTrue(Dialogues.start(second, TALK, npc), "second player talks to the same NPC");
            advance(first);
            helper.assertTrue(state(first).waitingForChoice(), "first moved on");
            expect(helper, state(second).node(), "hello", "second is still at the first line");
            helper.assertTrue(state(first).session() != state(second).session(), "separate sessions");

            List<ServerPlayer> viewers = Dialogues.bubbleViewers(npc, first);
            helper.assertTrue(viewers.contains(watcher) && viewers.contains(second), "nearby players see the lines");
            helper.assertFalse(viewers.contains(first), "the speaking player gets the dialogue, not a bubble");
            helper.assertFalse(viewers.contains(far), "players far away see nothing");

            Dialogues.interrupt(first, EndReason.STOPPED);
            expect(helper, state(second).node(), "hello", "ending one session leaves the other");
            Dialogues.interrupt(second, EndReason.STOPPED);
        });
        List.of(first, second, watcher, far).forEach(player -> remove(helper, player));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void walkingAwayInterrupts(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        ServerPlayer player = near(helper, helper.makeMockServerPlayerInLevel(), 2, 2, 1);
        Map<ResourceLocation, DialogueDefinition> previous = install(helper, "walk_away", true);
        Dialogues.start(player, TALK, npc);
        player.moveTo(helper.absoluteVec(new Vec3(1, 2, 30)));
        helper.succeedWhen(() -> {
            expect(helper, Dialogues.state(player), Optional.empty(), "interrupted when too far");
            expect(helper, world(helper, "walk_away_aborts"), 1, "on_abort ran");
            DialogueRegistries.DIALOGUES.serverStore().replace(previous);
            remove(helper, player);
        });
    }

    @GameTest(template = "empty")
    public static void cameraCuesFollowTheDialogue(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 0, 2, 0);
        ServerPlayer player = near(helper, helper.makeMockServerPlayerInLevel(), 0.5, 2, 0.5);
        ResourceLocation scene = key("camera_scene");
        ResourceLocation off = key("camera_off");
        ResourceLocation hud = key("camera_hud");
        with(helper, DialogueRegistries.DIALOGUES.serverStore(), DialogueDefinition.CODEC, Map.of(
                scene, """
                        { "start": "a", "nodes": {
                            "a": { "type": "line", "speaker": "npc", "text": "gametest.a", "shot": "close_up", "transition": "ease", "next": "b" },
                            "b": { "type": "line", "speaker": "player", "text": "gametest.b", "next": "c" },
                            "c": { "type": "choice", "options": [ { "text": "gametest.c" } ] } } }
                        """,
                off, "{ \"camera\": \"off\", \"start\": \"a\", \"nodes\": { \"a\": { \"type\": \"line\", \"speaker\": \"npc\", \"text\": \"gametest.a\" } } }",
                hud, "{ \"presentation\": \"hud\", \"start\": \"a\", \"nodes\": { \"a\": { \"type\": \"line\", \"speaker\": \"npc\", \"text\": \"gametest.a\" } } }"
        ), () -> {
            Dialogues.start(player, scene, npc);
            expect(helper, state(player).camera(), new CameraCue(true, CameraCue.Shot.CLOSE_UP, CameraCue.Transition.EASE, npc.getId()), "the line's own shot");
            advance(player);
            expect(helper, state(player).camera(), new CameraCue(true, CameraCue.Shot.AUTO, CameraCue.Transition.CUT, npc.getId()), "defaults");
            advance(player);
            helper.assertTrue(state(player).waitingForChoice() && state(player).camera().enabled(), "a choice is framed too");
            Dialogues.interrupt(player, EndReason.STOPPED);

            Dialogues.start(player, scene, null);
            expect(helper, state(player).camera(), CameraCue.OFF, "nobody to frame without an NPC");
            Dialogues.interrupt(player, EndReason.STOPPED);
            Dialogues.start(player, off, npc);
            expect(helper, state(player).camera(), CameraCue.OFF, "\"camera\": \"off\"");
            Dialogues.interrupt(player, EndReason.STOPPED);
            Dialogues.start(player, hud, npc);
            expect(helper, state(player).camera(), CameraCue.OFF, "HUD subtitles keep the player's view");
            Dialogues.interrupt(player, EndReason.STOPPED);

            StoryboundConfig.COMMON.cameraAllowed.set(false);
            try {
                Dialogues.start(player, scene, npc);
                expect(helper, state(player).camera(), CameraCue.OFF, "the server config forbids it");
                Dialogues.interrupt(player, EndReason.STOPPED);
            } finally {
                StoryboundConfig.COMMON.cameraAllowed.set(true);
            }
        });
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void damageInterrupts(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withDialogues(helper, "damage", true, () -> {
            Dialogues.start(player, TALK, null);
            player.hurt(player.damageSources().fellOutOfWorld(), 1f);
            expect(helper, Dialogues.state(player), Optional.empty(), "damage interrupts");
            expect(helper, world(helper, "damage_aborts"), 1, "on_abort ran");
        });
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void timedLinesAndChoiceTimeoutsMoveOn(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DefinitionStore<DialogueDefinition> store = DialogueRegistries.DIALOGUES.serverStore();
        Map<ResourceLocation, DialogueDefinition> previous = store.all();
        ResourceLocation timed = key("timed");
        installAll(helper, store, DialogueDefinition.CODEC, Map.of(timed, """
                { "participants": {}, "start": "intro", "nodes": {
                    "intro": { "type": "line", "speaker": "player", "text": "gametest.intro", "duration": 0.1, "next": "pick" },
                    "pick": { "type": "choice", "timeout": { "seconds": 1, "next": "late" },
                              "options": [ { "text": "gametest.never_chosen", "next": "@end" } ] },
                    "late": { "type": "line", "speaker": "player", "text": "gametest.late" } } }
                """));
        Dialogues.start(player, timed, null);
        helper.succeedWhen(() -> {
            expect(helper, Dialogues.state(player).map(Dialogues.State::node), Optional.of("late"), "timed line, then the choice timer, moved on");
            Dialogues.interrupt(player, EndReason.STOPPED);
            store.replace(previous);
            remove(helper, player);
        });
    }

    @GameTest(template = "empty")
    public static void npcBindingsStartDialogues(GameTestHelper helper) {
        Villager tagged = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        tagged.addTag("gametest_talker");
        Villager plain = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 2, 1);
        ServerPlayer player = near(helper, helper.makeMockServerPlayerInLevel(), 2, 2, 2);
        ServerPlayer walker = near(helper, helper.makeMockServerPlayerInLevel(), 1, 2, 20);
        withDialogues(helper, "bindings", true, () -> with(helper, DialogueRegistries.NPC_BINDINGS.serverStore(), NpcBinding.CODEC, Map.of(key("talker"), """
                { "entity": { "type": "minecraft:villager", "tag": "gametest_talker" },
                  "dialogues": [
                    { "dialogue": "gametest:talk" },
                    { "dialogue": "gametest:aside", "priority": 5, "conditions": { "type": "flag", "key": "gametest:bindings_aside" } } ],
                  "proximity": { "radius": 3, "cooldown": 0, "line_of_sight": false } }
                """), () -> {
            NpcBindings.rebuild();
            helper.assertTrue(NpcBindings.canTalk(player, tagged.getId()), "the talk hint shows for a bound NPC within reach");
            helper.assertFalse(NpcBindings.canTalk(player, plain.getId()), "but not for an unbound villager");
            helper.assertFalse(NpcBindings.canTalk(walker, tagged.getId()), "nor out of reach");
            helper.assertFalse(interact(player, tagged), "right-click keeps trading: talking has its own key");
            expect(helper, Dialogues.state(player), Optional.empty(), "no dialogue from a right-click by default");
            NpcBindings.talkRequested(player, tagged.getId());
            expect(helper, state(player).dialogue(), TALK, "the talk key started the binding's dialogue with the NPC");
            helper.assertFalse(NpcBindings.canTalk(player, tagged.getId()), "no talk hint while already talking");
            Dialogues.interrupt(player, EndReason.STOPPED);

            ok(() -> StoryState.set(player, StoryScope.PLAYER, key("bindings_aside"), new StoryValue.Bool(true)));
            NpcBindings.talkRequested(player, tagged.getId());
            expect(helper, state(player).dialogue(), ASIDE, "a higher priority entry whose conditions pass wins");
            Dialogues.interrupt(player, EndReason.STOPPED);

            NpcBindings.talkRequested(walker, tagged.getId());
            expect(helper, Dialogues.state(walker), Optional.empty(), "the talk key does nothing out of reach");
            NpcBindings.talkRequested(player, player.getId());
            NpcBindings.talkRequested(player, -1);
            expect(helper, Dialogues.state(player), Optional.empty(), "nor for oneself or an unknown entity");

            NpcBindings.talkRequested(player, plain.getId());
            expect(helper, Dialogues.state(player), Optional.empty(), "no dialogue from an unbound villager");
            plain.setData(DialogueRegistries.NPC_BINDING, new NpcBinding.Attached(ASIDE, true, false));
            NpcBindings.talkRequested(player, plain.getId());
            expect(helper, state(player).dialogue(), ASIDE, "a binding stored on the entity works");
            Dialogues.interrupt(player, EndReason.STOPPED);

            StoryboundConfig.COMMON.dialogueRightClickTalks.set(true);
            try {
                helper.assertTrue(interact(player, tagged), "with rightClickTalks, the click that starts a dialogue does not also open trades");
                expect(helper, state(player).dialogue(), ASIDE, "and right-click starts it");
                Dialogues.interrupt(player, EndReason.STOPPED);
            } finally {
                StoryboundConfig.COMMON.dialogueRightClickTalks.set(false);
            }

            long now = helper.getLevel().getServer().getTickCount();
            NpcBindings.checkProximity(walker, now);
            NpcBindings.checkProximity(walker, now);
            expect(helper, Dialogues.state(walker), Optional.empty(), "nothing while far away");
            walker.moveTo(helper.absoluteVec(new Vec3(1.5, 2, 3)));
            NpcBindings.checkProximity(walker, now);
            expect(helper, state(walker).dialogue(), TALK, "coming close starts the dialogue");
            Dialogues.interrupt(walker, EndReason.STOPPED);
            NpcBindings.checkProximity(walker, now);
            expect(helper, Dialogues.state(walker), Optional.empty(), "staying close does not start it again");
        }));
        NpcBindings.rebuild();
        remove(helper, player);
        remove(helper, walker);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void overrideInteractTalksOnRightClick(GameTestHelper helper) {
        Villager smith = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        smith.addTag("gametest_smith");
        Villager closed = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 2, 1);
        closed.addTag("gametest_closed");
        ServerPlayer player = near(helper, helper.makeMockServerPlayerInLevel(), 2, 2, 2);
        withDialogues(helper, "override", true, () -> with(helper, DialogueRegistries.NPC_BINDINGS.serverStore(), NpcBinding.CODEC, Map.of(
                key("smith"), """
                        { "entity": { "type": "minecraft:villager", "tag": "gametest_smith" },
                          "dialogues": [ { "dialogue": "gametest:talk" } ], "override_interact": true }
                        """,
                key("closed"), """
                        { "entity": { "type": "minecraft:villager", "tag": "gametest_closed" },
                          "dialogues": [ { "dialogue": "gametest:talk", "conditions": { "type": "flag", "key": "gametest:shop_open" } } ],
                          "override_interact": true }
                        """), () -> {
            NpcBindings.rebuild();
            helper.assertTrue(interact(player, smith), "override_interact consumes the right-click even with rightClickTalks off");
            expect(helper, state(player).dialogue(), TALK, "and starts the binding's dialogue instead of trading");
            Dialogues.interrupt(player, EndReason.STOPPED);

            helper.assertFalse(interact(player, closed), "when no dialogue passes its conditions, the click trades as usual");
            expect(helper, Dialogues.state(player), Optional.empty(), "and nothing starts");
        }));
        NpcBindings.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void openTradeOpensTheMerchantScreen(GameTestHelper helper) {
        ServerPlayer player = near(helper, helper.makeMockServerPlayerInLevel(), 2, 2, 2);
        ServerPlayer other = near(helper, helper.makeMockServerPlayerInLevel(), 2, 2, 3);
        WanderingTrader trader = helper.spawnWithNoFreeWill(EntityType.WANDERING_TRADER, 1, 2, 1);
        Villager farmer = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 2, 1);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        Villager jobless = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 3);
        Villager child = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 2, 3);
        child.setVillagerData(child.getVillagerData().setProfession(VillagerProfession.FARMER));
        child.setAge(-24000);
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 2, 1);
        DialogueLogic.OpenTrade openTrade = new DialogueLogic.OpenTrade();
        try {
            ok(() -> openTrade.run(new StoryContext(player, trader)));
            helper.assertTrue(player.containerMenu instanceof MerchantMenu, "the wandering trader's screen is open");
            expect(helper, trader.getTradingPlayer(), player, "the trader trades with the player");
            fails(helper, () -> openTrade.run(new StoryContext(other, trader)), "merchant_busy", "a merchant busy with someone else");
            player.closeContainer();
            expect(helper, trader.getTradingPlayer(), null, "closing the screen frees the trader");

            ok(() -> openTrade.run(new StoryContext(player, farmer)));
            helper.assertTrue(player.containerMenu instanceof MerchantMenu, "a villager with a job opens its trades the vanilla way");
            expect(helper, farmer.getTradingPlayer(), player, "the farmer trades with the player");
            player.closeContainer();

            fails(helper, () -> openTrade.run(new StoryContext(player, jobless)), "no_offers", "a villager without a job");
            fails(helper, () -> openTrade.run(new StoryContext(player, child)), "trade_refused", "a baby villager");
            fails(helper, () -> openTrade.run(new StoryContext(player, pig)), "not_merchant", "a pig");
            fails(helper, () -> openTrade.run(StoryContext.of(player)), "not_merchant", "no entity at all");
            helper.assertFalse(player.containerMenu instanceof MerchantMenu, "failed trades open nothing");
        } finally {
            player.closeContainer();
            other.closeContainer();
        }
        remove(helper, player);
        remove(helper, other);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storyEventStartsDialogueOnInteraction(GameTestHelper helper) {
        ServerPlayer player = near(helper, helper.makeMockServerPlayerInLevel(), 2, 2, 2);
        var trader = helper.spawnWithNoFreeWill(EntityType.WANDERING_TRADER, 1, 2, 1);
        withDialogues(helper, "event", true, () -> with(helper, CoreDefinitions.EVENTS.serverStore(), StoryEvent.CODEC, Map.of(key("event_talk"),
                "{ \"trigger\": { \"type\": \"entity_interacted\", \"entity\": \"minecraft:wandering_trader\" },"
                        + " \"actions\": { \"type\": \"start_dialogue\", \"dialogue\": \"gametest:talk\" } }"), () -> {
            StoryEvents.rebuild();
            PlayerInteractEvent.EntityInteract event = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, trader));
            helper.assertTrue(event.isCanceled(), "the click that started the dialogue is consumed");
            expect(helper, state(player).dialogue(), TALK, "start_dialogue from a story event");
            helper.assertTrue(interact(player, trader), "clicking the NPC you talk to does nothing else");
            Dialogues.interrupt(player, EndReason.STOPPED);
        }));
        StoryEvents.rebuild();
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void validatorReportsBrokenDialogues(GameTestHelper helper) {
        ResourceLocation broken = key("broken");
        DefinitionStore<DialogueDefinition> store = DialogueRegistries.DIALOGUES.serverStore();
        with(helper, store, DialogueDefinition.CODEC, Map.of(broken, """
                { "start": "missing", "nodes": {
                    "a": { "type": "line", "speaker": "stranger", "text": "gametest.a", "next": "nowhere" },
                    "b": { "type": "jump", "dialogue": "gametest:no_such_dialogue" },
                    "c": { "type": "choice", "options": [] },
                    "loop1": { "type": "condition", "conditions": { "type": "flag", "key": "gametest:x" }, "then": "loop2", "else": "loop2" },
                    "loop2": { "type": "jump", "node": "loop1" } } }
                """), () -> with(helper, CoreDefinitions.EVENTS.serverStore(), StoryEvent.CODEC, Map.of(key("bad_start"),
                "{ \"trigger\": { \"type\": \"custom\", \"id\": \"gametest:x\" }, \"actions\": { \"type\": \"start_dialogue\", \"dialogue\": \"gametest:nope\" } }"), () -> {
            List<ValidationIssue> issues = ValidationService.validate().issues();
            List<String> errors = messages(issues, broken, true);
            for (String expected : List.of("Start node 'missing'", "unknown speaker 'stranger'", "unknown node 'nowhere'",
                    "unknown dialogue gametest:no_such_dialogue", "has no options")) {
                helper.assertTrue(errors.stream().anyMatch(message -> message.contains(expected)), "expected an error containing '" + expected + "', got " + errors);
            }
            List<String> warnings = messages(issues, broken, false);
            helper.assertTrue(warnings.stream().anyMatch(message -> message.contains("loop")), "silent loop warning, got " + warnings);
            helper.assertTrue(warnings.stream().anyMatch(message -> message.contains("never reached")), "unreachable warning, got " + warnings);
            helper.assertTrue(messages(issues, key("bad_start"), true).stream().anyMatch(message -> message.contains("Unknown dialogue gametest:nope")),
                    "start_dialogue with an unknown dialogue in a story event");
        }));
        helper.succeed();
    }

    private static List<String> messages(List<ValidationIssue> issues, ResourceLocation entry, boolean errors) {
        return issues.stream().filter(issue -> entry.equals(issue.entry()) && issue.isError() == errors).map(ValidationIssue::message).toList();
    }

    /** Fires a main-hand right-click as the game would; returns whether something consumed it. */
    private static boolean interact(ServerPlayer player, net.minecraft.world.entity.Entity target) {
        return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, target)).isCanceled();
    }

    /** The action must fail with a message whose translation key ends with {@code key}. */
    private static void fails(GameTestHelper helper, ThrowingRunnable action, String key, String what) {
        try {
            action.run();
        } catch (StoryException e) {
            String message = e.component().getContents() instanceof TranslatableContents translatable ? translatable.getKey() : e.getMessage();
            helper.assertTrue(message.endsWith(key), what + ": expected " + key + ", got " + message);
            return;
        }
        throw new GameTestAssertException(what + ": expected the action to fail with " + key);
    }

    private static Dialogues.State state(ServerPlayer player) {
        return Dialogues.state(player).orElseThrow(() -> new GameTestAssertException("the player should be in a dialogue"));
    }

    private static void advance(ServerPlayer player) {
        Dialogues.State state = state(player);
        if (!Dialogues.advance(player, state.session(), state.step())) {
            throw new GameTestAssertException("advance was refused at " + state);
        }
    }

    private static void choose(ServerPlayer player, int index) {
        Dialogues.State state = state(player);
        if (!Dialogues.choose(player, state.session(), state.step(), index)) {
            throw new GameTestAssertException("option " + index + " was refused at " + state);
        }
    }

    private static ServerPlayer near(GameTestHelper helper, ServerPlayer player, double x, double y, double z) {
        player.moveTo(helper.absoluteVec(new Vec3(x, y, z)));
        return player;
    }

    private static int world(GameTestHelper helper, String path) {
        return WorldStoryData.get(helper.getLevel().getServer()).container().get(key(path))
                .map(value -> value instanceof StoryValue.Int number ? number.value() : -1).orElse(0);
    }

    /** Installs {@code gametest:talk} and {@code gametest:aside}, runs the body, then restores the loaded dialogues. */
    @GameTest(template = "empty")
    public static void anExclusiveNpcTalksWithOnePlayerAtATime(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        npc.addTag("gametest_exclusive");
        ServerPlayer first = near(helper, helper.makeMockServerPlayerInLevel(), 2, 2, 2);
        ServerPlayer second = near(helper, helper.makeMockServerPlayerInLevel(), 2, 2, 1);
        withDialogues(helper, "exclusive", true, () -> with(helper, DialogueRegistries.NPC_BINDINGS.serverStore(), NpcBinding.CODEC, Map.of(
                key("exclusive"), """
                        { "entity": { "type": "minecraft:villager", "tag": "gametest_exclusive" }, "exclusive": true,
                          "busy_dialogue": "gametest:aside", "dialogues": [ { "dialogue": "gametest:talk" } ] }
                        """), () -> {
            NpcBindings.rebuild();
            NpcBindings.talkRequested(first, npc.getId());
            expect(helper, state(first).dialogue(), TALK, "the first player talks");
            helper.assertTrue(NpcBindings.canTalk(second, npc.getId()), "the second sees the talk hint: there is a busy line");
            NpcBindings.talkRequested(second, npc.getId());
            expect(helper, state(second).dialogue(), ASIDE, "the second gets the busy line, not the dialogue");
            Dialogues.interrupt(second, EndReason.STOPPED);
            Dialogues.interrupt(first, EndReason.STOPPED);
            NpcBindings.talkRequested(second, npc.getId());
            expect(helper, state(second).dialogue(), TALK, "free again: the dialogue");
            Dialogues.interrupt(second, EndReason.STOPPED);
        }));
        withDialogues(helper, "exclusive_silent", true, () -> with(helper, DialogueRegistries.NPC_BINDINGS.serverStore(), NpcBinding.CODEC, Map.of(
                key("exclusive"), """
                        { "entity": { "type": "minecraft:villager", "tag": "gametest_exclusive" }, "exclusive": true,
                          "dialogues": [ { "dialogue": "gametest:talk" } ] }
                        """), () -> {
            NpcBindings.rebuild();
            NpcBindings.talkRequested(first, npc.getId());
            helper.assertFalse(NpcBindings.canTalk(second, npc.getId()), "without a busy line, no talk hint");
            NpcBindings.talkRequested(second, npc.getId());
            expect(helper, Dialogues.state(second), Optional.empty(), "and nothing starts");
            Dialogues.interrupt(first, EndReason.STOPPED);
        }));
        NpcBindings.rebuild();
        helper.getLevel().getServer().getPlayerList().remove(first);
        helper.getLevel().getServer().getPlayerList().remove(second);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aGoneDialogueIsPassedOverAndMismatchesAreExplained(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        withDialogues(helper, "gone", true, () -> {
            NpcBinding binding = NpcBinding.CODEC.parse(ops(helper), JsonParser.parseString("""
                    { "entity": { "type": "minecraft:villager", "tag": "quest_man", "name": "Quest Man" },
                      "dialogues": [ { "dialogue": "gametest:deleted", "priority": 10 }, { "dialogue": "%s" } ] }
                    """.formatted(TALK))).getOrThrow(GameTestAssertException::new);
            expect(helper, binding.pick(StoryContext.of(player)), Optional.of(TALK), "the next entry talks when the first dialogue is gone");
            Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
            villager.addTag("npc_4");
            String why = binding.entity().mismatch(villager);
            helper.assertTrue(why.contains("tag quest_man missing (has npc_4)") && why.contains("custom name is none, wanted Quest Man"),
                    "says what the villager lacks: " + why);
            villager.addTag("quest_man");
            villager.setCustomName(Component.literal("Quest Man"));
            expect(helper, binding.entity().mismatch(villager), "", "nothing when it matches");
            villager.discard();
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    private static void withDialogues(GameTestHelper helper, String prefix, boolean closable, Runnable body) {
        Map<ResourceLocation, DialogueDefinition> previous = install(helper, prefix, closable);
        try {
            body.run();
        } finally {
            DialogueRegistries.DIALOGUES.serverStore().replace(previous);
        }
    }

    private static Map<ResourceLocation, DialogueDefinition> install(GameTestHelper helper, String prefix, boolean closable) {
        DefinitionStore<DialogueDefinition> store = DialogueRegistries.DIALOGUES.serverStore();
        Map<ResourceLocation, DialogueDefinition> previous = store.all();
        installAll(helper, store, DialogueDefinition.CODEC, Map.of(TALK, TALK_JSON.formatted(prefix, closable), ASIDE, ASIDE_JSON));
        return previous;
    }

    private static <T> void with(GameTestHelper helper, DefinitionStore<T> store, Codec<T> codec, Map<ResourceLocation, String> definitions, Runnable body) {
        Map<ResourceLocation, T> previous = store.all();
        installAll(helper, store, codec, definitions);
        try {
            body.run();
        } finally {
            store.replace(previous);
        }
    }

    private static <T> void installAll(GameTestHelper helper, DefinitionStore<T> store, Codec<T> codec, Map<ResourceLocation, String> definitions) {
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
        Dialogues.interrupt(player, EndReason.STOPPED);
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws StoryException;
    }
}
