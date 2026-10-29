package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.event.CustomTriggerEvent;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.TriggerType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * Routes game events to subscribed story logic. Indexed by trigger type, so event types nobody
 * listens to cost nothing. Runs on the server thread only.
 */
public final class TriggerDispatcher {
    /** Actions can change flags that fire triggers that run actions; this stops accidental loops. */
    private static final int MAX_DEPTH = 16;

    private static final Map<String, List<Subscription<?>>> SOURCES = new LinkedHashMap<>();
    private static Map<TriggerType<?, ?>, List<Subscription<?>>> index = Map.of();
    private static final Map<UUID, Set<Subscription<?>>> INSIDE_AREAS = new HashMap<>();
    /** Players whose next area check is their first since logging in. */
    private static final Set<UUID> FRESH_LOGINS = new HashSet<>();
    private static int depth;
    /** The owner of the world's shared progress, for {@link #asOne(Set, Runnable)} ({@code StoryState.owner} gives null). */
    public static final UUID WORLD = new UUID(0, 0);
    private static long occurrences;
    /** The happening the trigger now being handled belongs to. */
    private static long current;
    private static Set<UUID> countedBefore = Set.of();
    /** The happening fired as one by {@link #asOne}, at its depth; -1 outside one. */
    private static long group = -1;
    private static int groupDepth = -1;
    private static Set<UUID> groupCountedBefore = Set.of();

    private TriggerDispatcher() {}

    /** Replaces every subscription of one source, e.g. all story events after /reload. */
    public static void setSubscriptions(String source, List<Subscription<?>> subscriptions) {
        SOURCES.put(source, List.copyOf(subscriptions));
        Map<TriggerType<?, ?>, List<Subscription<?>>> next = new HashMap<>();
        SOURCES.values().forEach(list -> list.forEach(subscription ->
                next.computeIfAbsent(subscription.trigger().type(), type -> new ArrayList<>()).add(subscription)));
        index = next;
        INSIDE_AREAS.clear();
    }

    /** How deep the running chain of triggers is: 0 outside a trigger, 1 in the first one. */
    public static int depth() {
        return depth;
    }

    public static boolean hasSubscribers(TriggerType<?, ?> type) {
        return index.containsKey(type);
    }

    /** What listens to the trigger type now; for detectors that only look for what someone waits for. */
    public static List<Subscription<?>> subscriptions(TriggerType<?, ?> type) {
        return index.getOrDefault(type, List.of());
    }

    @SuppressWarnings("unchecked")
    public static <E> void fire(TriggerType<?, E> type, StoryContext context, E event) {
        List<Subscription<?>> subscriptions = index.get(type);
        boolean grouped = group >= 0 && depth == groupDepth;
        if (subscriptions == null || !enter(type)) {
            return;
        }
        long previous = current;
        Set<UUID> previousCounted = countedBefore;
        current = grouped ? group : ++occurrences;
        countedBefore = grouped ? groupCountedBefore : Set.of();
        try {
            for (Subscription<?> subscription : subscriptions) {
                notify((Subscription<E>) subscription, context, event);
            }
        } finally {
            depth--;
            current = previous;
            countedBefore = previousCounted;
        }
    }

    /**
     * Fires what {@code fires} fires as one happening in the world: a kill shared with the killer's party, a boss's
     * victory for each credited player, a party quest's or flag's change for each member online fire one trigger for
     * several players, and progress those players share (a party's, the world's) counts it once (October 29, 2026;
     * see {@link #occurrence}). What those triggers set off in turn is a happening of its own.
     *
     * @param countedBefore owners of shared progress that already counted this happening (a victory credited late, on
     *                      joining, was counted for the party of a player who was there); {@link #WORLD} for the world's
     */
    public static void asOne(Set<UUID> countedBefore, Runnable fires) {
        long previousGroup = group;
        int previousDepth = groupDepth;
        Set<UUID> previousCounted = groupCountedBefore;
        group = ++occurrences;
        groupDepth = depth;
        groupCountedBefore = Set.copyOf(countedBefore);
        try {
            fires.run();
        } finally {
            group = previousGroup;
            groupDepth = previousDepth;
            groupCountedBefore = previousCounted;
        }
    }

    public static void asOne(Runnable fires) {
        asOne(Set.of(), fires);
    }

    /** The happening of the trigger being handled: the same for every player it was fired for {@link #asOne as one}. */
    public static long occurrence() {
        return current;
    }

    /** Whether the shared progress of {@code owner} (null: the world's) counted the happening being handled before. */
    public static boolean countedBefore(@Nullable UUID owner) {
        return countedBefore.contains(owner == null ? WORLD : owner);
    }

    /** True if a subscription of this type would react to the event; lets costly detectors skip what nobody listens for. */
    @SuppressWarnings("unchecked")
    public static <E> boolean anyMatches(TriggerType<?, E> type, StoryContext context, E event) {
        List<Subscription<?>> subscriptions = index.get(type);
        if (subscriptions == null) {
            return false;
        }
        for (Subscription<?> subscription : subscriptions) {
            try {
                if (((Subscription<E>) subscription).trigger().matches(context, event)) {
                    return true;
                }
            } catch (RuntimeException e) {
                Storybound.LOGGER.error("Storybound: {} failed", subscription.name(), e);
            }
        }
        return false;
    }

    /** The single entry point for {@code custom} triggers: notifies listeners of {@link CustomTriggerEvent}, then story logic. */
    public static void fireCustom(ServerPlayer player, ResourceLocation id) {
        NeoForge.EVENT_BUS.post(new CustomTriggerEvent(player, id));
        fire(BuiltinTriggers.CUSTOM.get(), StoryContext.of(player), id);
    }

    /**
     * Fires {@code area_entered} and {@code structure_entered} for players who crossed into an area or a structure
     * since the previous check; called every 10 ticks. Public for tests.
     */
    @SuppressWarnings("unchecked")
    public static void tickAreas(MinecraftServer server) {
        List<Subscription<?>> subscriptions = new ArrayList<>(index.getOrDefault(BuiltinTriggers.AREA_ENTERED.get(), List.of()));
        subscriptions.addAll(index.getOrDefault(BuiltinTriggers.STRUCTURE_ENTERED.get(), List.of()));
        subscriptions.addAll(index.getOrDefault(BuiltinTriggers.BIOME_ENTERED.get(), List.of()));
        subscriptions.addAll(index.getOrDefault(BuiltinTriggers.POI_ENTERED.get(), List.of()));
        if (subscriptions.isEmpty()) {
            return;
        }
        long previousOccurrence = current;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Each player walking in is a happening of their own.
            current = ++occurrences;
            Set<Subscription<?>> inside = Collections.newSetFromMap(new IdentityHashMap<>());
            StoryContext context = StoryContext.of(player);
            for (Subscription<?> subscription : subscriptions) {
                try {
                    if (((Subscription<Unit>) subscription).trigger().matches(context, Unit.INSTANCE)) {
                        inside.add(subscription);
                    }
                } catch (RuntimeException e) {
                    Storybound.LOGGER.error("Storybound: {} failed", subscription.name(), e);
                }
            }
            Set<Subscription<?>> previous = INSIDE_AREAS.put(player.getUUID(), inside);
            boolean freshLogin = FRESH_LOGINS.remove(player.getUUID());
            // The first look after a reload only records where the player is; after a login,
            // places with fire_on_login fire as if the player had just walked in.
            if ((previous == null && !freshLogin) || !enter(BuiltinTriggers.AREA_ENTERED.get())) {
                continue;
            }
            try {
                for (Subscription<?> subscription : inside) {
                    boolean entered = previous != null
                            ? !previous.contains(subscription)
                            : ((CoreTriggers.Entered) subscription.trigger()).fireOnLogin();
                    if (entered) {
                        runListener(subscription, context);
                    }
                }
            } finally {
                depth--;
            }
        }
        current = previousOccurrence;
    }

    static void loggedIn(UUID player) {
        INSIDE_AREAS.remove(player);
        FRESH_LOGINS.add(player);
    }

    static void forget(UUID player) {
        INSIDE_AREAS.remove(player);
        FRESH_LOGINS.remove(player);
    }

    static void reset() {
        SOURCES.clear();
        index = Map.of();
        INSIDE_AREAS.clear();
        FRESH_LOGINS.clear();
        depth = 0;
    }

    private static boolean enter(TriggerType<?, ?> type) {
        if (depth >= MAX_DEPTH) {
            Storybound.LOGGER.error("Storybound: trigger chain deeper than {} at {}; stopping. Check story events that trigger each other.",
                    MAX_DEPTH, LogicRegistries.TRIGGER_TYPES.getKey(type));
            return false;
        }
        depth++;
        return true;
    }

    private static <E> void notify(Subscription<E> subscription, StoryContext context, E event) {
        try {
            if (subscription.trigger().matches(context, event)) {
                StoryTrace.trigger(context, subscription.name(), subscription.trigger().type());
                subscription.listener().onTriggered(context);
            }
        } catch (RuntimeException e) {
            Storybound.LOGGER.error("Storybound: {} failed", subscription.name(), e);
        }
    }

    private static void runListener(Subscription<?> subscription, StoryContext context) {
        try {
            StoryTrace.trigger(context, subscription.name(), subscription.trigger().type());
            subscription.listener().onTriggered(context);
        } catch (RuntimeException e) {
            Storybound.LOGGER.error("Storybound: {} failed", subscription.name(), e);
        }
    }
}
