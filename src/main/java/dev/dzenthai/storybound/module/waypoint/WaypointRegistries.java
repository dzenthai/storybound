package dev.dzenthai.storybound.module.waypoint;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.state.StoryMigrations;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class WaypointRegistries {
    /** Server only: each client gets just the waypoints it sees, already placed and resolved. */
    public static final DefinitionType<WaypointDefinition> WAYPOINTS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("waypoint"), "storybound/waypoints", WaypointDefinition.CODEC));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Storybound.MOD_ID);

    /** Where the player last saw each waypoint's entity; personal, kept through death. */
    public static final Supplier<AttachmentType<WaypointSightings>> SIGHTINGS = ATTACHMENT_TYPES.register("waypoint_sightings",
            () -> AttachmentType.builder(() -> WaypointSightings.EMPTY).serialize(WaypointSightings.CODEC).copyOnDeath().build());

    private WaypointRegistries() {}

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
        WaypointLogic.REGISTER_ACTIONS.register(modBus);
        StoryMigrations.registerServer((server, renames) -> WaypointData.get(server).migrate(renames));
    }
}
