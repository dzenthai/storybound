package dev.dzenthai.storybound.module.waypoint;

import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.event.PartyEvent;
import dev.dzenthai.storybound.core.event.QuestChangedEvent;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.dzenthai.storybound.core.logic.LogicOwners;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class WaypointEvents {
    private WaypointEvents() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(EventPriority.HIGH, WaypointEvents::onTagsUpdated);
        // LOWEST: quest progress, flags and party are synced first, so names and bindings are current.
        gameBus.addListener(EventPriority.LOWEST, WaypointEvents::onDatapackSync);
        gameBus.addListener(WaypointEvents::onValueChanged);
        gameBus.addListener(WaypointEvents::onQuestChanged);
        gameBus.addListener(WaypointEvents::onPartyJoined);
        gameBus.addListener(WaypointEvents::onPartyLeft);
        gameBus.addListener(WaypointEvents::onLoggedOut);
        gameBus.addListener(WaypointEvents::onServerTick);
        gameBus.addListener(WaypointEvents::onServerStopped);
        gameBus.addListener(WaypointEvents::onEntityLeave);
        gameBus.addListener(WaypointEvents::onEntityJoin);
        LogicOwners.register(WaypointRegistries.WAYPOINTS, (waypoint, references) -> {
            waypoint.conditions().collectReferences(references);
            waypoint.quest().ifPresent(quest -> references.quest(quest, waypoint.stage().orElse(null)));
        });
        ValidationService.registerValidator(WaypointEvents::validate);
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            Waypoints.rebuild();
        }
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(Waypoints::syncFull);
    }

    private static void onValueChanged(StoryValueChangedEvent event) {
        Waypoints.refreshScope(event.getServer(), event.getScope(), event.getOwner());
    }

    private static void onQuestChanged(QuestChangedEvent event) {
        Waypoints.refreshScope(event.getServer(), event.getScope(), event.getOwner());
    }

    private static void onPartyJoined(PartyEvent.Joined event) {
        refresh(event);
    }

    private static void onPartyLeft(PartyEvent.Left event) {
        refresh(event);
    }

    private static void refresh(PartyEvent event) {
        ServerPlayer player = event.getServer().getPlayerList().getPlayer(event.getPlayer());
        if (player != null) {
            Waypoints.refresh(player);
        }
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Waypoints.forget(player);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        Waypoints.tick(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        Waypoints.reset();
    }

    /** A followed entity killed or removed for good (not unloaded, not through a portal) is gone for every player. */
    private static void onEntityLeave(EntityLeaveLevelEvent event) {
        Entity.RemovalReason reason = event.getEntity().getRemovalReason();
        if (event.getLevel() instanceof ServerLevel level && reason != null && reason.shouldDestroy()
                && reason != Entity.RemovalReason.CHANGED_DIMENSION && !(event.getEntity() instanceof Player)) {
            WaypointTargets.get(level.getServer()).left(event.getEntity().getUUID());
        }
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && !(event.getEntity() instanceof Player)) {
            WaypointTargets.get(level.getServer()).back(event.getEntity().getUUID());
        }
    }

    /** Quest stages must list waypoints that exist; a waypoint bound to a stage needs a quest. */
    private static void validate(Consumer<ValidationIssue> sink) {
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> {
            for (QuestDefinition.Stage stage : definition.stages()) {
                for (var waypoint : stage.waypoints()) {
                    if (!WaypointRegistries.WAYPOINTS.serverStore().contains(waypoint)) {
                        sink.accept(ValidationIssue.error(QuestRegistries.QUESTS.id(), quest, "Stage '" + stage.id() + "' lists unknown waypoint " + waypoint));
                    }
                }
            }
        });
        WaypointRegistries.WAYPOINTS.serverStore().all().forEach((id, waypoint) -> {
            if (waypoint.stage().isPresent() && waypoint.quest().isEmpty()) {
                sink.accept(ValidationIssue.error(WaypointRegistries.WAYPOINTS.id(), id, "'stage' needs 'quest'"));
            }
            if (waypoint.radius() == 0 && !waypoint.look().equals(AreaLook.DEFAULT)) {
                sink.accept(ValidationIssue.warning(WaypointRegistries.WAYPOINTS.id(), id,
                        "'show_radius' and 'show_ring' are for a search area; without a 'radius' this is a point and they do nothing"));
            }
        });
    }
}
