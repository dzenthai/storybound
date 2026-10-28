package dev.dzenthai.storybound.core.logic.trigger;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.StatRef;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.TriggerType;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The player's own progress as story triggers: an advancement earned (a modpack's milestones often are advancements),
 * a dimension entered (chapters that are journeys), an experience level and a statistic reached. Levels and statistics
 * are looked at once a second, so commands and other mods changing them count too; they fire when the value crosses
 * the threshold, not for a player already past it (a condition checks that).
 */
public final class ProgressTriggers {
    private static final int CHECK_INTERVAL = 20;
    private static final Map<UUID, Integer> LEVELS = new HashMap<>();
    private static final Map<UUID, Map<StatRef, Integer>> STATS = new HashMap<>();

    private ProgressTriggers() {}

    static void register(IEventBus gameBus) {
        gameBus.addListener(ProgressTriggers::onAdvancement);
        gameBus.addListener(ProgressTriggers::onDimensionChanged);
        gameBus.addListener(ProgressTriggers::onServerTick);
        gameBus.addListener(ProgressTriggers::onLoggedOut);
        gameBus.addListener(ProgressTriggers::onServerStopped);
    }

    /** {@code { "type": "advancement_earned", "advancement": "calamity:chapter_one" }}; without one, any with a toast (not recipes). */
    public record AdvancementEarned(Optional<ResourceLocation> advancement) implements Trigger<AdvancementHolder> {
        public static final MapCodec<AdvancementEarned> CODEC = ResourceLocation.CODEC.optionalFieldOf("advancement")
                .xmap(AdvancementEarned::new, AdvancementEarned::advancement);

        @Override
        public TriggerType<?, AdvancementHolder> type() {
            return BuiltinTriggers.ADVANCEMENT_EARNED.get();
        }

        @Override
        public boolean matches(StoryContext context, AdvancementHolder earned) {
            return advancement.map(id -> id.equals(earned.id())).orElseGet(() -> earned.value().display().isPresent());
        }

        /** A named advancement, once earned; "any" has no state. */
        @Override
        public StateKind stateKind() {
            return advancement.isPresent() ? StateKind.HISTORY : StateKind.NONE;
        }

        @Override
        public boolean holdsNow(StoryContext context) {
            AdvancementHolder holder = context.player().server.getAdvancements().get(advancement.orElseThrow());
            return holder != null && context.player().getAdvancements().getOrStartProgress(holder).isDone();
        }
    }

    public record Travel(ResourceKey<Level> from, ResourceKey<Level> to) {}

    /** {@code { "type": "dimension_changed", "to": "twilightforest:twilight_forest" }}; {@code from} and {@code to} are optional. */
    public record DimensionChanged(Optional<ResourceKey<Level>> from, Optional<ResourceKey<Level>> to) implements Trigger<Travel> {
        public static final MapCodec<DimensionChanged> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("from").forGetter(DimensionChanged::from),
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("to").forGetter(DimensionChanged::to)
        ).apply(instance, DimensionChanged::new));

        @Override
        public TriggerType<?, Travel> type() {
            return BuiltinTriggers.DIMENSION_CHANGED.get();
        }

        @Override
        public boolean matches(StoryContext context, Travel travel) {
            return from.map(travel.from()::equals).orElse(true) && to.map(travel.to()::equals).orElse(true);
        }

        /** Being in {@code to}; a journey from a given dimension is only the moment. */
        @Override
        public StateKind stateKind() {
            return to.isPresent() && from.isEmpty() ? StateKind.CURRENT : StateKind.NONE;
        }

        @Override
        public boolean holdsNow(StoryContext context) {
            return context.player().level().dimension().equals(to.orElseThrow());
        }
    }

    /** A value that went from {@code before} to {@code now}. */
    public record Change(int before, int now) {
        boolean crossed(int threshold) {
            return before < threshold && now >= threshold;
        }
    }

    /** {@code { "type": "level_reached", "level": 30 }}: the player's experience level reaches it. */
    public record LevelReached(int level) implements Trigger<Change> {
        public static final MapCodec<LevelReached> CODEC = Codec.intRange(1, 1_000_000).fieldOf("level").xmap(LevelReached::new, LevelReached::level);

        @Override
        public TriggerType<?, Change> type() {
            return BuiltinTriggers.LEVEL_REACHED.get();
        }

        @Override
        public boolean matches(StoryContext context, Change change) {
            return change.crossed(level);
        }

        /** The level can drop again (death, enchanting), so it is a current state. */
        @Override
        public StateKind stateKind() {
            return StateKind.CURRENT;
        }

        @Override
        public boolean holdsNow(StoryContext context) {
            return context.player().experienceLevel >= level;
        }
    }

    public record StatChange(StatRef stat, Change change) {}

    /** {@code { "type": "stat_reached", "stat_type": "minecraft:killed", "stat": "minecraft:zombie", "value": 50 }}: see {@link StatRef}. */
    public record StatReached(StatRef stat, int value) implements Trigger<StatChange> {
        public static final MapCodec<StatReached> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                StatRef.MAP_CODEC.forGetter(StatReached::stat),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("value").forGetter(StatReached::value)
        ).apply(instance, StatReached::new));

        @Override
        public TriggerType<?, StatChange> type() {
            return BuiltinTriggers.STAT_REACHED.get();
        }

        @Override
        public boolean matches(StoryContext context, StatChange change) {
            return stat.equals(change.stat()) && change.change().crossed(value);
        }

        @Override
        public StateKind stateKind() {
            return StateKind.HISTORY;
        }

        @Override
        public boolean holdsNow(StoryContext context) {
            return stat.value(context.player()) >= value;
        }
    }

    private static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TriggerDispatcher.fire(BuiltinTriggers.ADVANCEMENT_EARNED.get(), StoryContext.of(player), event.getAdvancement());
        }
    }

    private static void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TriggerDispatcher.fire(BuiltinTriggers.DIMENSION_CHANGED.get(), StoryContext.of(player), new Travel(event.getFrom(), event.getTo()));
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % CHECK_INTERVAL == 0) {
            checkValues(event.getServer());
        }
    }

    /**
     * Looks at levels and the statistics story logic waits for; fires for what changed since the last look. The first
     * look at a player (after a login) only records. Public for tests.
     */
    public static void checkValues(MinecraftServer server) {
        boolean levels = TriggerDispatcher.hasSubscribers(BuiltinTriggers.LEVEL_REACHED.get());
        Set<StatRef> stats = new LinkedHashSet<>();
        for (Subscription<?> subscription : TriggerDispatcher.subscriptions(BuiltinTriggers.STAT_REACHED.get())) {
            stats.add(((StatReached) subscription.trigger()).stat());
        }
        if (!levels && stats.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            StoryContext context = StoryContext.of(player);
            if (levels) {
                Integer before = LEVELS.put(player.getUUID(), player.experienceLevel);
                if (before != null && before != player.experienceLevel) {
                    TriggerDispatcher.fire(BuiltinTriggers.LEVEL_REACHED.get(), context, new Change(before, player.experienceLevel));
                }
            }
            Map<StatRef, Integer> seen = STATS.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
            for (StatRef stat : stats) {
                int now = stat.value(player);
                Integer before = seen.put(stat, now);
                if (before != null && before != now) {
                    TriggerDispatcher.fire(BuiltinTriggers.STAT_REACHED.get(), context, new StatChange(stat, new Change(before, now)));
                }
            }
        }
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LEVELS.remove(event.getEntity().getUUID());
        STATS.remove(event.getEntity().getUUID());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
        STATS.clear();
    }
}
