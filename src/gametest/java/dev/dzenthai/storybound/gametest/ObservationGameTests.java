package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.trigger.CoreTriggers;
import dev.dzenthai.storybound.core.logic.trigger.ObservationTracker;
import dev.dzenthai.storybound.core.logic.trigger.Subscription;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.module.codex.Codex;
import dev.dzenthai.storybound.module.codex.CodexDefinition;
import dev.dzenthai.storybound.module.codex.CodexEvents;
import dev.dzenthai.storybound.module.codex.CodexRegistries;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The trigger "observed" on a real server: the time adding up while the player looks straight at the target, pausing
 * and then lost when they look away, a wall and the distance, a target counted once, two observations of one target
 * each on their own, no bar for what would do nothing, a block by its position, a Codex section studied. Checks are run
 * by hand, {@link ObservationTracker#INTERVAL} ticks apart, so the tests do not wait for the server.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ObservationGameTests {
    private static final String SOURCE = "gametest observation";
    private static final int STEP = ObservationTracker.INTERVAL;
    private static final ResourceLocation SCREEN_DIALOGUE = ResourceLocation.fromNamespaceAndPath("gametest", "observe_screen");
    private static final ResourceLocation SUBTITLE_DIALOGUE = ResourceLocation.fromNamespaceAndPath("gametest", "observe_subtitles");

    private ObservationGameTests() {}

    // "open": the 1-block "empty" template is walled in with barriers, which block the look.
    @GameTest(template = "open")
    public static void observingFillsOverItsTimeAndCountsATargetOnce(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        ServerPlayer player = looking(helper, pig.getEyePosition(), 2.5, 1, 6);
        AtomicInteger fired = new AtomicInteger();
        Subscription<CoreTriggers.Observation> watch = observe(pig, 1, 16, fired, true);
        with(List.of(watch), () -> {
            int now = watch(player, 0, 16);
            expect(helper, fired.get(), 0, "not yet: 16 of 20 ticks");
            helper.assertTrue(Math.abs(ObservationTracker.progress(player, watch) - 0.8f) < 0.01f, "the bar is at 80%");
            now = watch(player, now, 4);
            expect(helper, fired.get(), 1, "observed after 20 ticks");
            watch(player, now, 32);
            expect(helper, fired.get(), 1, "the same pig does not count again while the player stays near");
            expect(helper, ObservationTracker.progress(player, watch), 0f, "and fills no bar");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "open")
    public static void aCrosshairJustBesideTheTargetStillObserves(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        // A little beside the pig's box, as the crosshair slips while walking.
        Vec3 beside = new Vec3(pig.getBoundingBox().maxX + 0.3, pig.getEyeY(), pig.getZ());
        ServerPlayer player = looking(helper, beside, 2.5, 1, 6);
        AtomicInteger fired = new AtomicInteger();
        Subscription<CoreTriggers.Observation> watch = observe(pig, 0.4f, 16, fired, true);
        with(List.of(watch), () -> {
            watch(player, 0, 8);
            expect(helper, fired.get(), 1, "the crosshair 0.3 blocks beside the pig still observes it");
        });
        player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(pig.getBoundingBox().maxX + 2, pig.getEyeY(), pig.getZ()));
        AtomicInteger far = new AtomicInteger();
        with(List.of(observe(pig, 0.25f, 16, far, true)), () -> {
            watch(player, 100, 20);
            expect(helper, far.get(), 0, "two blocks beside it does not");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "open")
    public static void lookingAwayPausesThenLosesTheProgress(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        ServerPlayer player = looking(helper, pig.getEyePosition(), 2.5, 1, 6);
        AtomicInteger fired = new AtomicInteger();
        Subscription<CoreTriggers.Observation> watch = observe(pig, 1, 16, fired, true);
        with(List.of(watch), () -> {
            int now = watch(player, 0, 8);
            float before = ObservationTracker.progress(player, watch);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition().add(0, 0, 10));
            ObservationTracker.check(player, now + STEP);
            expect(helper, ObservationTracker.progress(player, watch), before, "looking away pauses the bar");
            ObservationTracker.check(player, now + ObservationTracker.GRACE + STEP);
            expect(helper, ObservationTracker.progress(player, watch), 0f, "and loses it after the grace");
            expect(helper, fired.get(), 0, "nothing observed");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "open")
    public static void aWallOrTheDistanceHidesTheTarget(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        ServerPlayer near = looking(helper, pig.getEyePosition(), 2.5, 1, 6);
        AtomicInteger fired = new AtomicInteger();
        Subscription<CoreTriggers.Observation> close = observe(pig, 0.25f, 2, fired, true);
        with(List.of(close), () -> {
            watch(near, 0, 20);
            expect(helper, fired.get(), 0, "the pig is 4 blocks away, the observation reaches 2");
        });
        helper.setBlock(new BlockPos(2, 1, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 4), Blocks.STONE);
        Subscription<CoreTriggers.Observation> far = observe(pig, 0.25f, 16, fired, true);
        with(List.of(far), () -> {
            watch(near, 100, 20);
            expect(helper, fired.get(), 0, "a wall between them hides the pig");
        });
        helper.getLevel().getServer().getPlayerList().remove(near);
        helper.succeed();
    }

    @GameTest(template = "open")
    public static void eachObservationCountsOnItsOwnAndOnlyWhenWorthIt(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        ServerPlayer player = looking(helper, pig.getEyePosition(), 2.5, 1, 6);
        AtomicInteger quick = new AtomicInteger();
        AtomicInteger slow = new AtomicInteger();
        AtomicInteger idle = new AtomicInteger();
        Subscription<CoreTriggers.Observation> short_ = observe(pig, 0.4f, 16, quick, true);
        Subscription<CoreTriggers.Observation> long_ = observe(pig, 1, 16, slow, true);
        Subscription<CoreTriggers.Observation> useless = observe(pig, 0.4f, 16, idle, false);
        with(List.of(short_, long_, useless), () -> {
            int now = watch(player, 0, 8);
            expect(helper, quick.get(), 1, "the 8-tick observation ends first");
            expect(helper, slow.get(), 0, "the 20-tick one goes on");
            watch(player, now, 12);
            expect(helper, slow.get(), 1, "then the 20-tick one ends");
            expect(helper, idle.get(), 0, "one that would do nothing (an objective of another stage) never fills");
            expect(helper, ObservationTracker.progress(player, useless), 0f, "nor shows a bar");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "open")
    public static void aBlockIsObservedByItsPosition(GameTestHelper helper) {
        BlockPos lectern = new BlockPos(2, 1, 2);
        helper.setBlock(lectern, Blocks.LECTERN);
        BlockPos absolute = helper.absolutePos(lectern);
        ServerPlayer player = looking(helper, Vec3.atCenterOf(absolute), 2.5, 1, 6);
        AtomicInteger fired = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        Subscription<CoreTriggers.Observation> lecternWatch = new Subscription<>("gametest lectern", new CoreTriggers.Observed(Optional.empty(), Optional.empty(),
                Optional.of(absolute), Optional.empty(), 0.25f, 16, Optional.empty()), context -> fired.incrementAndGet());
        Subscription<CoreTriggers.Observation> elsewhere = new Subscription<>("gametest elsewhere", new CoreTriggers.Observed(Optional.empty(), Optional.empty(),
                Optional.of(absolute.above(5)), Optional.empty(), 0.25f, 16, Optional.empty()), context -> other.incrementAndGet());
        with(List.of(lecternWatch, elsewhere), () -> {
            watch(player, 0, 6);
            expect(helper, fired.get(), 1, "the lectern looked at");
            expect(helper, other.get(), 0, "another position is not");
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "open")
    public static void studyingACreatureOpensACodexSection(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        ServerPlayer player = looking(helper, pig.getEyePosition(), 2.5, 1, 6);
        ResourceLocation entry = ResourceLocation.fromNamespaceAndPath("gametest", "studied_pigs");
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        CodexDefinition definition = CodexDefinition.CODEC.parse(ops, JsonParser.parseString("""
                { "entity": "minecraft:pig", "title": "gametest.codex.studied",
                  "sections": [ { "id": "habits", "unlock": { "on": ["observed:0.5"] } } ] }""")).getOrThrow(GameTestAssertException::new);
        Map<ResourceLocation, CodexDefinition> previous = CodexRegistries.CODEX.serverStore().all();
        Map<ResourceLocation, CodexDefinition> installed = new LinkedHashMap<>(previous);
        installed.put(entry, definition);
        CodexRegistries.CODEX.serverStore().replace(installed);
        try {
            CodexEvents.rebuild();
            int now = watch(player, 0, 8);
            helper.assertTrue(!Codex.data(player).isUnlocked(entry, "habits"), "not yet: 8 of 10 ticks");
            watch(player, now, 2);
            helper.assertTrue(Codex.data(player).isUnlocked(entry, "habits"), "studied for half a second: the section opens");
        } finally {
            CodexRegistries.CODEX.serverStore().replace(previous);
            CodexEvents.rebuild();
        }
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    /**
     * A dialogue on the screen freezes the look on whoever stands in front: it observes nothing, a bar already filling
     * pauses and is lost as when looking away, a player beside them who is not talking still observes, and once it ends
     * the bar starts from nothing.
     */
    @GameTest(template = "open")
    public static void aDialogueOnTheScreenObservesNothing(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        ServerPlayer player = looking(helper, pig.getEyePosition(), 2.5, 1, 6);
        ServerPlayer other = looking(helper, pig.getEyePosition(), 1.5, 1, 6);
        AtomicInteger fired = new AtomicInteger();
        Subscription<CoreTriggers.Observation> watch = observe(pig, 1, 16, fired, true);
        withDialogues(helper, () -> with(List.of(watch), () -> {
            int now = watch(player, 0, 8);
            float before = ObservationTracker.progress(player, watch);
            // A click, or a story event, opens a dialogue with the pig being observed.
            Dialogues.start(player, SCREEN_DIALOGUE, pig);
            ObservationTracker.check(player, now += STEP);
            expect(helper, ObservationTracker.progress(player, watch), before, "the dialogue pauses the bar, as looking away does");
            now = watch(player, now, 40);
            expect(helper, ObservationTracker.progress(player, watch), 0f, "and loses it after the grace");
            expect(helper, fired.get(), 0, "two seconds of talking face to face observe nothing: the conversation is no observation");
            watch(other, 0, 20);
            expect(helper, fired.get(), 1, "a player beside them who is not talking observes the pig");
            Dialogues.interrupt(player, EndReason.STOPPED);
            now = watch(player, now, 16);
            helper.assertTrue(Math.abs(ObservationTracker.progress(player, watch) - 0.8f) < 0.01f, "the dialogue over, the bar starts from nothing");
            watch(player, now, 4);
            expect(helper, fired.get(), 2, "and fills as usual");
        }));
        Dialogues.interrupt(player, EndReason.STOPPED);
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.getLevel().getServer().getPlayerList().remove(other);
        helper.succeed();
    }

    /**
     * Subtitles leave the look to the player, so the lines observe; so does a choice in them while it waits for the
     * talk key; its window, once open, holds the screen and does not.
     */
    @GameTest(template = "open")
    public static void subtitlesObserveButTheirChoiceDoesNot(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        ServerPlayer player = looking(helper, pig.getEyePosition(), 2.5, 1, 6);
        AtomicInteger duringLine = new AtomicInteger();
        AtomicInteger duringPrompt = new AtomicInteger();
        AtomicInteger duringChoice = new AtomicInteger();
        withDialogues(helper, () -> {
            Dialogues.start(player, SUBTITLE_DIALOGUE, pig);
            with(List.of(observe(pig, 1, 16, duringLine, true)), () -> {
                watch(player, 0, 20);
                expect(helper, duringLine.get(), 1, "a subtitle line: the player looks where they like, the pig is observed");
            });
            Dialogues.State state = Dialogues.state(player).orElseThrow();
            helper.assertTrue(Dialogues.advance(player, state.session(), state.step()), "on to the choice");
            Dialogues.State choice = Dialogues.state(player).orElseThrow();
            helper.assertTrue(choice.waitingForChoice(), "the choice is shown");
            with(List.of(observe(pig, 1, 16, duringPrompt, true)), () -> {
                watch(player, 50, 20);
                expect(helper, duringPrompt.get(), 1, "waiting for the talk key: the pig is observed");
            });
            helper.assertTrue(Dialogues.openChoice(player, choice.session(), choice.step(), true), "its window opens");
            with(List.of(observe(pig, 1, 16, duringChoice, true)), () -> {
                watch(player, 100, 40);
                expect(helper, duringChoice.get(), 0, "its window holds the screen: nothing is observed");
            });
        });
        Dialogues.interrupt(player, EndReason.STOPPED);
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    private static void withDialogues(GameTestHelper helper, Runnable body) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        Map<ResourceLocation, DialogueDefinition> previous = DialogueRegistries.DIALOGUES.serverStore().all();
        Map<ResourceLocation, DialogueDefinition> installed = new LinkedHashMap<>(previous);
        installed.put(SCREEN_DIALOGUE, DialogueDefinition.CODEC.parse(ops, JsonParser.parseString("""
                { "start": "a", "nodes": { "a": { "type": "line", "speaker": "npc", "text": "gametest.a" } } }"""))
                .getOrThrow(GameTestAssertException::new));
        installed.put(SUBTITLE_DIALOGUE, DialogueDefinition.CODEC.parse(ops, JsonParser.parseString("""
                { "presentation": "hud", "start": "a", "nodes": {
                    "a": { "type": "line", "speaker": "npc", "text": "gametest.a", "next": "b" },
                    "b": { "type": "choice", "options": [ { "text": "gametest.b" } ] } } }"""))
                .getOrThrow(GameTestAssertException::new));
        DialogueRegistries.DIALOGUES.serverStore().replace(installed);
        try {
            body.run();
        } finally {
            DialogueRegistries.DIALOGUES.serverStore().replace(previous);
        }
    }

    /**
     * Observing an item, wherever it shows: an emerald lying on the ground (Minecraft's crosshair does not pick it),
     * one in an item frame, a golden helmet worn by an armor stand, a chest placed as a block; an item the subscription
     * does not name, and the player's own hand, count for nothing.
     */
    @GameTest(template = "open", timeoutTicks = 200)
    public static void anItemIsObservedWhereverItShows(GameTestHelper helper) {
        AtomicInteger emeralds = new AtomicInteger();
        AtomicInteger helmets = new AtomicInteger();
        AtomicInteger chests = new AtomicInteger();
        AtomicInteger diamonds = new AtomicInteger();
        List<Subscription<?>> watches = List.of(
                observeItem(net.minecraft.world.item.Items.EMERALD, emeralds),
                observeItem(net.minecraft.world.item.Items.GOLDEN_HELMET, helmets),
                observeItem(net.minecraft.world.item.Items.CHEST, chests),
                observeItem(net.minecraft.world.item.Items.DIAMOND, diamonds));
        with(watches, () -> {
            // On the ground.
            net.minecraft.world.entity.item.ItemEntity lying = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(),
                    helper.absoluteVec(new Vec3(2.5, 1, 2.5)).x, helper.absoluteVec(new Vec3(2.5, 1, 2.5)).y, helper.absoluteVec(new Vec3(2.5, 1, 2.5)).z,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD));
            lying.setNoGravity(true);
            lying.setDeltaMovement(Vec3.ZERO);
            lying.setNeverPickUp();
            helper.getLevel().addFreshEntity(lying);
            ServerPlayer player = looking(helper, lying.getBoundingBox().getCenter(), 2.5, 1, 6);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
            watch(player, 0, 6);
            expect(helper, emeralds.get(), 1, "an emerald on the ground");
            expect(helper, diamonds.get(), 0, "the diamond in the player's own hand counts for nothing");
            lying.discard();

            // Worn by an armor stand.
            net.minecraft.world.entity.decoration.ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, 2, 1, 2);
            stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_HELMET));
            player.lookAt(EntityAnchorArgument.Anchor.EYES, stand.getBoundingBox().getCenter());
            watch(player, 100, 6);
            expect(helper, helmets.get(), 1, "a golden helmet worn by an armor stand");
            stand.discard();

            // Placed as a block.
            BlockPos chest = new BlockPos(2, 1, 2);
            helper.setBlock(chest, Blocks.CHEST);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(helper.absolutePos(chest)));
            watch(player, 200, 6);
            expect(helper, chests.get(), 1, "a chest placed is the chest item seen");
            expect(helper, emeralds.get(), 1, "the chest is no emerald");
            helper.getLevel().getServer().getPlayerList().remove(player);
        });
        helper.succeed();
    }

    /** In an item frame: the frame is an entity the crosshair picks; the item in it is what is observed. */
    @GameTest(template = "open", timeoutTicks = 100)
    public static void anItemInAFrameIsObserved(GameTestHelper helper) {
        AtomicInteger emeralds = new AtomicInteger();
        BlockPos wall = new BlockPos(2, 1, 1);
        helper.setBlock(wall, Blocks.STONE);
        net.minecraft.world.entity.decoration.ItemFrame frame = new net.minecraft.world.entity.decoration.ItemFrame(helper.getLevel(),
                helper.absolutePos(wall.south()), net.minecraft.core.Direction.SOUTH);
        frame.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD), false);
        helper.getLevel().addFreshEntity(frame);
        with(List.of(observeItem(net.minecraft.world.item.Items.EMERALD, emeralds)), () -> {
            ServerPlayer player = looking(helper, frame.getBoundingBox().getCenter(), 2.5, 1.5, 5);
            watch(player, 0, 6);
            expect(helper, emeralds.get(), 1, "an emerald in an item frame");
            helper.getLevel().getServer().getPlayerList().remove(player);
        });
        frame.discard();
        helper.succeed();
    }

    /** While nothing observes an item, one lying in front of an observed block hides nothing (as before items could be observed). */
    @GameTest(template = "open", timeoutTicks = 100)
    public static void anItemLyingInFrontHidesNothingWhileNoItemIsObserved(GameTestHelper helper) {
        BlockPos lectern = new BlockPos(2, 1, 2);
        helper.setBlock(lectern, Blocks.LECTERN);
        BlockPos absolute = helper.absolutePos(lectern);
        ServerPlayer player = looking(helper, Vec3.atCenterOf(absolute), 2.5, 1, 6);
        Vec3 between = player.getEyePosition().add(Vec3.atCenterOf(absolute).subtract(player.getEyePosition()).scale(0.5));
        net.minecraft.world.entity.item.ItemEntity lying = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), between.x, between.y, between.z,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD));
        lying.setNoGravity(true);
        lying.setDeltaMovement(Vec3.ZERO);
        lying.setNeverPickUp();
        helper.getLevel().addFreshEntity(lying);
        AtomicInteger fired = new AtomicInteger();
        // A kind of block, not a position: a position has its own look past entities, a kind only the one in front.
        with(List.of(new Subscription<CoreTriggers.Observation>("gametest lectern behind", new CoreTriggers.Observed(Optional.empty(),
                Optional.of(net.minecraft.core.HolderSet.direct(net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.LECTERN))), Optional.empty(), Optional.empty(),
                0.25f, 16, Optional.empty()), context -> fired.incrementAndGet())), () -> {
            watch(player, 0, 6);
            expect(helper, fired.get(), 1, "the lectern behind the emerald is still observed");
        });
        lying.discard();
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    private static Subscription<CoreTriggers.Observation> observeItem(net.minecraft.world.item.Item item, AtomicInteger fired) {
        return new Subscription<>("gametest observe " + item, new CoreTriggers.Observed(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0.25f, 16, Optional.empty(), Optional.of(net.minecraft.core.HolderSet.direct(net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(item)))),
                context -> fired.incrementAndGet(), context -> true);
    }

    /** Checks as the server does, every {@link ObservationTracker#INTERVAL} ticks, for {@code ticks} ticks; returns the tick it got to. */
    private static int watch(ServerPlayer player, int from, int ticks) {
        int now = from;
        for (int done = 0; done < ticks; done += STEP) {
            ObservationTracker.check(player, now += STEP);
        }
        return now;
    }

    private static ServerPlayer looking(GameTestHelper helper, Vec3 target, double x, double y, double z) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new Vec3(x, y, z)));
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        return player;
    }

    private static Subscription<CoreTriggers.Observation> observe(LivingEntity target, float seconds, double distance, AtomicInteger fired, boolean worthIt) {
        return new Subscription<>("gametest observe " + seconds, new CoreTriggers.Observed(Optional.of(EntitySelector.of(target.getUUID())), Optional.empty(),
                Optional.empty(), Optional.empty(), seconds, distance, Optional.empty()), context -> fired.incrementAndGet(), context -> worthIt);
    }

    private static void with(List<Subscription<?>> subscriptions, Runnable body) {
        TriggerDispatcher.setSubscriptions(SOURCE, subscriptions);
        try {
            body.run();
        } finally {
            TriggerDispatcher.setSubscriptions(SOURCE, List.of());
        }
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(java.util.Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }
}
