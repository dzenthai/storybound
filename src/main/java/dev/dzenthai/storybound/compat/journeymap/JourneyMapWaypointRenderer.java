package dev.dzenthai.storybound.compat.journeymap;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.waypoint.ClientWaypoints;
import dev.dzenthai.storybound.client.waypoint.WaypointRenderer;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import java.util.List;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;

/** Shows Storybound waypoints in JourneyMap through its official API: client-side, never persisted, rebuilt on every change. */
final class JourneyMapWaypointRenderer implements WaypointRenderer {
    private final IClientAPI api;

    JourneyMapWaypointRenderer(IClientAPI api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "JourneyMap";
    }

    @Override
    public void refresh(List<ClientWaypoint> waypoints) {
        api.removeAllWaypoints(Storybound.MOD_ID);
        for (ClientWaypoint waypoint : waypoints) {
            String name = waypoint.name().toComponent().getString();
            ClientWaypoints.Display display = ClientWaypoints.display(waypoint);
            Waypoint journeymap = WaypointFactory.createClientWaypoint(Storybound.MOD_ID, BlockPos.containing(waypoint.pos()),
                    display == ClientWaypoints.Display.TRACKED ? "» " + name : name,
                    ResourceKey.create(Registries.DIMENSION, waypoint.dimension()), false);
            journeymap.setColor(waypoint.color() & 0xFFFFFF);
            // On the minimap and the full map only: in the world, Storybound draws its own markers.
            journeymap.setShowBeacon(false);
            journeymap.setShowInWorld(false);
            if (display == ClientWaypoints.Display.DIMMED) {
                journeymap.setIconOpacity(0.5f);
            }
            api.addWaypoint(Storybound.MOD_ID, journeymap);
        }
    }
}
