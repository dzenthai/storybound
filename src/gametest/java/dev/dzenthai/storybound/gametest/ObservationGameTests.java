package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.trigger.CoreTriggers;
import dev.dzenthai.storybound.core.logic.trigger.ObservationTracker;
import dev.dzenthai.storybound.core.logic.trigger.Subscription;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.module.codex.Codex;
import dev.dzenthai.storybound.module.codex.CodexDefinition;
import dev.dzenthai.storybound.module.codex.CodexEvents;
import dev.dzenthai.storybound.module.codex.CodexRegistries;
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
 * The trigger "observed" on a real server (October 30, 2026): the time adding up while the player looks straight at the
 * target, pausing and then lost when they look away, a wall and the distance, a target counted once, two observations
 * of one target each on their own, no bar for what would do nothing, a block by its position, a Codex section studied.
 * Checks are run by hand, {@link ObservationTracker#INTERVAL} ticks apart, so the tests do not wait for the server.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ObservationGameTests {
    private static final String SOURCE = "gametest observation";
    private static final int STEP = ObservationTracker.INTERVAL;

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
