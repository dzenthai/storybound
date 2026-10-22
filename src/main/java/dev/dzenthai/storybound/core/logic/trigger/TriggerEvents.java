package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.core.event.PartyEvent;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.dzenthai.storybound.core.logic.KillCredit;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.state.StoryState;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
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

    // Runs for the player who caused the change; changes without one (console, scripts) run for every affected player.
    private static void onValueChanged(StoryValueChangedEvent event) {
        if (!TriggerDispatcher.hasSubscribers(BuiltinTriggers.FLAG_CHANGED.get())) {
            return;
        }
        List<ServerPlayer> players = event.getActor() != null
                ? List.of(event.getActor())
                : StoryState.onlinePlayersOf(event.getServer(), event.getScope(), event.getOwner());
        for (ServerPlayer player : players) {
            TriggerDispatcher.fire(BuiltinTriggers.FLAG_CHANGED.get(), StoryContext.of(player), event);
        }
    }

    // The killer first, then party members nearby, whom only "shared" triggers count.
    private static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer) || !TriggerDispatcher.hasSubscribers(BuiltinTriggers.ENTITY_KILLED.get())) {
            return;
        }
        LivingEntity victim = event.getEntity();
        TriggerDispatcher.fire(BuiltinTriggers.ENTITY_KILLED.get(), new StoryContext(killer, victim), new CoreTriggers.Kill(victim, true));
        for (ServerPlayer member : KillCredit.partyNear(killer, victim)) {
            TriggerDispatcher.fire(BuiltinTriggers.ENTITY_KILLED.get(), new StoryContext(member, victim), new CoreTriggers.Kill(victim, false));
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % AREA_CHECK_INTERVAL == 0) {
            TriggerDispatcher.tickAreas(event.getServer());
        }
        SeenTracker.tick(event.getServer());
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
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        TriggerDispatcher.forget(event.getEntity().getUUID());
        SeenTracker.forget(event.getEntity().getUUID());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        StoryTrace.stop();
        TriggerDispatcher.reset();
        SeenTracker.reset();
    }
}
