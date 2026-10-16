package dev.dzenthai.storybound.compat.xaero;

import dev.dzenthai.storybound.client.waypoint.ClientWaypoints;

/** Entry point of the Xaero's Minimap integration; called only when Xaero's Minimap is loaded. */
public final class XaeroCompat {
    private XaeroCompat() {}

    public static void register() {
        ClientWaypoints.registerRenderer(new XaeroWaypointRenderer());
    }
}
