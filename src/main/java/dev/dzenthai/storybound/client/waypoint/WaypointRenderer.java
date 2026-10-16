package dev.dzenthai.storybound.client.waypoint;

import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import java.util.List;

/**
 * Shows Storybound waypoints somewhere: a map mod's waypoint list, a minimap. Our own HUD reads
 * {@link ClientWaypoints} directly while drawing and needs no renderer.
 */
public interface WaypointRenderer {
    String name();

    /**
     * The full list to show now, already without hidden ones (see {@link ClientWaypoints#display}).
     * Called on the client thread when the waypoints, the tracked quest or the dimension change.
     */
    void refresh(List<ClientWaypoint> waypoints);
}
