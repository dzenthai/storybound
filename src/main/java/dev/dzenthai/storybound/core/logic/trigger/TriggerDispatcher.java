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
    private static List<Subscription<?>> areaSubscriptions = List.of();
    private static final Map<net.minecraft.world.entity.EntityType<?>, List<Subscription<?>>> KILLED_BY_TYPE = new HashMap<>();
    private static final Map<net.minecraft.world.entity.EntityType<?>, List<Subscription<?>>> SEEN_BY_TYPE = new HashMap<>();
    private static boolean hasSharedKill;
    private static boolean hasByAnyoneKill;
    private static int generation;
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
        KILLED_BY_TYPE.clear();
        SEEN_BY_TYPE.clear();
        List<Subscription<?>> areas = new ArrayList<>();
        areas.addAll(index.getOrDefault(BuiltinTriggers.AREA_ENTERED.get(), List.of()));
        areas.addAll(index.getOrDefault(BuiltinTriggers.STRUCTURE_ENTERED.get(), List.of()));
        areas.addAll(index.getOrDefault(BuiltinTriggers.BIOME_ENTERED.get(), List.of()));
        areas.addAll(index.getOrDefault(BuiltinTriggers.POI_ENTERED.get(), List.of()));
        areaSubscriptions = List.copyOf(areas);
        boolean shared = false;
        boolean byAnyone = false;
        for (Subscription<?> sub : index.getOrDefault(BuiltinTriggers.ENTITY_KILLED.get(), List.of())) {
            if (sub.trigger() instanceof CoreTriggers.EntityKilled killed) {
                shared |= killed.shared();
                byAnyone |= killed.byAnyone();
            } else {
                // Unknown custom trigger on ENTITY_KILLED: stay conservative.
                shared = true;
                byAnyone = true;
            }
        }
        hasSharedKill = shared;
        hasByAnyoneKill = byAnyone;
        generation++;
        INSIDE_AREAS.clear();
    }

    static int generation() {
        return generation;
    }

    static boolean hasSharedKill() {
        return hasSharedKill;
    }

    static boolean hasByAnyoneKill() {
        return hasByAnyoneKill;
    }

    static boolean hasEntityKilledFor(net.minecraft.world.entity.EntityType<?> type) {
        return !killedFor(type).isEmpty();
    }

    static boolean hasEntitySeenFor(net.minecraft.world.entity.EntityType<?> type) {
        return !seenFor(type).isEmpty();
    }

    private static List<Subscription<?>> killedFor(net.minecraft.world.entity.EntityType<?> type) {
        List<Subscription<?>> all = index.get(BuiltinTriggers.ENTITY_KILLED.get());
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<Subscription<?>> cached = KILLED_BY_TYPE.get(type);
        if (cached != null) {
            return cached;
        }
        var holder = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type);
        List<Subscription<?>> matching = null;
        for (Subscription<?> sub : all) {
            boolean fits = true;
            if (sub.trigger() instanceof CoreTriggers.EntityKilled killed && killed.entity().isPresent()) {
                var types = killed.entity().get().type();
                fits = types.isEmpty() || types.get().contains(holder);
            }
            if (fits) {
                if (matching == null) {
                    matching = new ArrayList<>();
                }
                matching.add(sub);
            }
        }
        List<Subscription<?>> result = matching == null ? List.of() : List.copyOf(matching);
        KILLED_BY_TYPE.put(type, result);
        return result;
    }

    private static List<Subscription<?>> seenFor(net.minecraft.world.entity.EntityType<?> type) {
        List<Subscription<?>> all = index.get(BuiltinTriggers.ENTITY_SEEN.get());
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<Subscription<?>> cached = SEEN_BY_TYPE.get(type);
        if (cached != null) {
            return cached;
        }
        var holder = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type);
        List<Subscription<?>> matching = null;
        for (Subscription<?> sub : all) {
            boolean fits = true;
            if (sub.trigger() instanceof CoreTriggers.EntitySeen seen && seen.entity().isPresent()) {
                var types = seen.entity().get().type();
                fits = types.isEmpty() || types.get().contains(holder);
            }
            if (fits) {
                if (matching == null) {
                    matching = new ArrayList<>();
                }
                matching.add(sub);
            }
        }
        List<Subscription<?>> result = matching == null ? List.of() : List.copyOf(matching);
        SEEN_BY_TYPE.put(type, result);
        return result;
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
        List<Subscription<?>> subscriptions = subscriptionsFor(type, event);
        boolean grouped = group >= 0 && depth == groupDepth;
        if (subscriptions == null || subscriptions.isEmpty() || !enter(type)) {
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
     * Fires one subscription alone: a detector that measures each subscription on its own (an observation of 3 seconds
     * and one of 10 on the same zombie end at different moments) tells only the one that is done.
     */
    public static <E> void fireOne(Subscription<E> subscription, StoryContext context, E event) {
        if (!enter(subscription.trigger().type())) {
            return;
        }
        long previous = current;
        Set<UUID> previousCounted = countedBefore;
        current = ++occurrences;
        countedBefore = Set.of();
        try {
            notify(subscription, context, event);
        } finally {
            depth--;
            current = previous;
            countedBefore = previousCounted;
        }
    }

    /**
     * Fires what {@code fires} fires as one happening in the world: a kill shared with the killer's party, a boss's
     * victory for each credited player, a party quest's or flag's change for each member online fire one trigger for
     * several players, and progress those players share (a party's, the world's) counts it once (see
     * {@link #occurrence}). What those triggers set off in turn is a happening of its own.
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

    private static @Nullable List<Subscription<?>> subscriptionsFor(TriggerType<?, ?> type, Object event) {
        if (type == BuiltinTriggers.ENTITY_KILLED.get() && event instanceof CoreTriggers.Kill kill) {
            return killedFor(kill.victim().getType());
        }
        if (type == BuiltinTriggers.ENTITY_SEEN.get() && event instanceof net.minecraft.world.entity.Entity entity) {
            return seenFor(entity.getType());
        }
        return index.get(type);
    }

    /** True if a subscription of this type would react to the event; lets costly detectors skip what nobody listens for. */
    @SuppressWarnings("unchecked")
    public static <E> boolean anyMatches(TriggerType<?, E> type, StoryContext context, E event) {
        List<Subscription<?>> subscriptions = subscriptionsFor(type, event);
        if (subscriptions == null || subscriptions.isEmpty()) {
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
        List<Subscription<?>> subscriptions = areaSubscriptions;
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
        areaSubscriptions = List.of();
        KILLED_BY_TYPE.clear();
        SEEN_BY_TYPE.clear();
        hasSharedKill = false;
        hasByAnyoneKill = false;
        generation++;
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
