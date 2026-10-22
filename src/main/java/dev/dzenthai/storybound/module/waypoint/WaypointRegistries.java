package dev.dzenthai.storybound.module.waypoint;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.state.StoryMigrations;
import net.neoforged.bus.api.IEventBus;

public final class WaypointRegistries {
    /** Server only: each client gets just the waypoints it sees, already placed and resolved. */
    public static final DefinitionType<WaypointDefinition> WAYPOINTS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("waypoint"), "storybound/waypoints", WaypointDefinition.CODEC));

    private WaypointRegistries() {}

    public static void register(IEventBus modBus) {
        WaypointLogic.REGISTER_ACTIONS.register(modBus);
        StoryMigrations.registerServer((server, renames) -> WaypointData.get(server).migrate(renames));
    }
}
