package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.event.CustomTriggerEvent;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.StoryContext;
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

    public static boolean hasSubscribers(TriggerType<?, ?> type) {
        return index.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    public static <E> void fire(TriggerType<?, E> type, StoryContext context, E event) {
        List<Subscription<?>> subscriptions = index.get(type);
        if (subscriptions == null || !enter(type)) {
            return;
        }
        try {
            for (Subscription<?> subscription : subscriptions) {
                notify((Subscription<E>) subscription, context, event);
            }
        } finally {
            depth--;
        }
    }

    /** The single entry point for {@code custom} triggers: notifies listeners of {@link CustomTriggerEvent}, then story logic. */
    public static void fireCustom(ServerPlayer player, ResourceLocation id) {
        NeoForge.EVENT_BUS.post(new CustomTriggerEvent(player, id));
        fire(BuiltinTriggers.CUSTOM.get(), StoryContext.of(player), id);
    }

    /**
     * Fires {@code area_entered} for players who crossed into an area since the previous check;
     * called every 10 ticks. Public for tests.
     */
    @SuppressWarnings("unchecked")
    public static void tickAreas(MinecraftServer server) {
        TriggerType<?, Unit> type = BuiltinTriggers.AREA_ENTERED.get();
        List<Subscription<?>> subscriptions = index.get(type);
        if (subscriptions == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Set<Subscription<?>> inside = Collections.newSetFromMap(new IdentityHashMap<>());
            StoryContext context = StoryContext.of(player);
            for (Subscription<?> subscription : subscriptions) {
                if (((Subscription<Unit>) subscription).trigger().matches(context, Unit.INSTANCE)) {
                    inside.add(subscription);
                }
            }
            Set<Subscription<?>> previous = INSIDE_AREAS.put(player.getUUID(), inside);
            boolean freshLogin = FRESH_LOGINS.remove(player.getUUID());
            // The first look after a reload only records where the player is; after a login,
            // areas with fire_on_login fire as if the player had just walked in.
            if ((previous == null && !freshLogin) || !enter(type)) {
                continue;
            }
            try {
                for (Subscription<?> subscription : inside) {
                    boolean entered = previous != null
                            ? !previous.contains(subscription)
                            : ((CoreTriggers.AreaEntered) subscription.trigger()).fireOnLogin();
                    if (entered) {
                        runListener(subscription, context);
                    }
                }
            } finally {
                depth--;
            }
        }
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
                subscription.listener().onTriggered(context);
            }
        } catch (RuntimeException e) {
            Storybound.LOGGER.error("Storybound: {} failed", subscription.name(), e);
        }
    }

    private static void runListener(Subscription<?> subscription, StoryContext context) {
        try {
            subscription.listener().onTriggered(context);
        } catch (RuntimeException e) {
            Storybound.LOGGER.error("Storybound: {} failed", subscription.name(), e);
        }
    }
}
