package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.core.event.PartyEvent;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.dzenthai.storybound.core.logic.KillCredit;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Turns game and Storybound events into trigger firings. */
public final class TriggerEvents {
    private static final int AREA_CHECK_INTERVAL = 10;
    /** How often once story events are checked by their trigger's state (see {@link StoryEvents#checkStates}). */
    private static final int STATE_CHECK_INTERVAL = 20;

    private TriggerEvents() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(TriggerEvents::onValueChanged);
        gameBus.addListener(TriggerEvents::onLivingDeath);
        gameBus.addListener(TriggerEvents::onServerTick);
        gameBus.addListener(TriggerEvents::onItemPickup);
        gameBus.addListener(TriggerEvents::onItemCrafted);
        gameBus.addListener(TriggerEvents::onEntityInteract);
        gameBus.addListener(TriggerEvents::onPartyJoined);
        gameBus.addListener(TriggerEvents::onPartyLeft);
        gameBus.addListener(TriggerEvents::onLoggedIn);
        gameBus.addListener(TriggerEvents::onLoggedOut);
        gameBus.addListener(TriggerEvents::onServerStopped);
        ProgressTriggers.register(gameBus);
    }

    // Runs for the player who caused the change (then, for a party value, for the other members online: what follows a
    // party's flag happens to each of them, as with party quests); changes without one (console, scripts) run for every
    // affected player. A world value runs for its actor only, or one change would run stories for the whole server.
    private static void onValueChanged(StoryValueChangedEvent event) {
        if (!TriggerDispatcher.hasSubscribers(BuiltinTriggers.FLAG_CHANGED.get())) {
            return;
        }
        List<ServerPlayer> players;
        if (event.getActor() == null) {
            players = StoryState.onlinePlayersOf(event.getServer(), event.getScope(), event.getOwner());
        } else if (event.getScope() == StoryScope.PARTY) {
            players = new ArrayList<>(List.of(event.getActor()));
            StoryState.onlinePlayersOf(event.getServer(), event.getScope(), event.getOwner()).stream()
                    .filter(member -> member != event.getActor()).forEach(players::add);
        } else {
            players = List.of(event.getActor());
        }
        TriggerDispatcher.asOne(() -> {
            for (ServerPlayer player : players) {
                TriggerDispatcher.fire(BuiltinTriggers.FLAG_CHANGED.get(), StoryContext.of(player), event);
            }
        });
    }

    // The killer first, then party members nearby, whom only "shared" triggers count.
    private static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer) || !TriggerDispatcher.hasSubscribers(BuiltinTriggers.ENTITY_KILLED.get())) {
            return;
        }
        LivingEntity victim = event.getEntity();
        TriggerDispatcher.asOne(() -> {
            TriggerDispatcher.fire(BuiltinTriggers.ENTITY_KILLED.get(), new StoryContext(killer, victim), new CoreTriggers.Kill(victim, true));
            for (ServerPlayer member : KillCredit.partyNear(killer, victim)) {
                TriggerDispatcher.fire(BuiltinTriggers.ENTITY_KILLED.get(), new StoryContext(member, victim), new CoreTriggers.Kill(victim, false));
            }
        });
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % AREA_CHECK_INTERVAL == 0) {
            TriggerDispatcher.tickAreas(event.getServer());
        }
        int tick = event.getServer().getTickCount();
        for (ServerPlayer player : List.copyOf(event.getServer().getPlayerList().getPlayers())) {
            // Each player on a tick of their own, as the quest checks.
            if (Math.floorMod(tick + player.getId(), STATE_CHECK_INTERVAL) == 0) {
                StoryEvents.checkStates(player);
            }
        }
        SeenTracker.tick(event.getServer());
        ObservationTracker.tick(event.getServer());
    }

    private static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            TriggerDispatcher.fire(BuiltinTriggers.ITEM_OBTAINED.get(), StoryContext.of(player), event.getOriginalStack());
        }
    }

    private static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TriggerDispatcher.fire(BuiltinTriggers.ITEM_OBTAINED.get(), StoryContext.of(player), event.getCrafting());
        }
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() == InteractionHand.MAIN_HAND && event.getEntity() instanceof ServerPlayer player) {
            TriggerDispatcher.fire(BuiltinTriggers.ENTITY_INTERACTED.get(), new StoryContext(player, event.getTarget()), event.getTarget());
        }
    }

    private static void onPartyJoined(PartyEvent.Joined event) {
        ServerPlayer player = event.getServer().getPlayerList().getPlayer(event.getPlayer());
        if (player != null) {
            TriggerDispatcher.fire(BuiltinTriggers.PARTY_JOINED.get(), StoryContext.of(player), event);
        }
    }

    private static void onPartyLeft(PartyEvent.Left event) {
        ServerPlayer player = event.getServer().getPlayerList().getPlayer(event.getPlayer());
        if (player != null) {
            TriggerDispatcher.fire(BuiltinTriggers.PARTY_LEFT.get(), StoryContext.of(player), event);
        }
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        TriggerDispatcher.loggedIn(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player && TriggerDispatcher.hasSubscribers(BuiltinTriggers.LOGGED_IN.get())) {
            // The game counts leaving, so a player who never left is here for the first time.
            boolean first = player.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) == 0;
            TriggerDispatcher.fire(BuiltinTriggers.LOGGED_IN.get(), StoryContext.of(player), first);
        }
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        TriggerDispatcher.forget(event.getEntity().getUUID());
        SeenTracker.forget(event.getEntity().getUUID());
        ObservationTracker.forget(event.getEntity().getUUID());
        StoryEvents.forget(event.getEntity().getUUID());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        StoryTrace.stop();
        TriggerDispatcher.reset();
        SeenTracker.reset();
        ObservationTracker.reset();
        StoryEvents.reset();
    }
}
