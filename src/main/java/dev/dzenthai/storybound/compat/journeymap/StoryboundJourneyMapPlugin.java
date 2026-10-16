package dev.dzenthai.storybound.compat.journeymap;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.waypoint.ClientWaypoints;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

/** Found by JourneyMap through the annotation, only when JourneyMap is installed. */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class StoryboundJourneyMapPlugin implements IClientPlugin {
    @Override
    public String getModId() {
        return Storybound.MOD_ID;
    }

    @Override
    public void initialize(IClientAPI api) {
        ClientWaypoints.registerRenderer(new JourneyMapWaypointRenderer(api));
    }
}
