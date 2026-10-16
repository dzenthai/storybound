package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.waypoint.ClientWaypoints;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The waypoints a player sees. {@code full}: the whole list, replacing the client's (login, {@code /reload});
 * otherwise a delta: waypoints added or changed, and keys of those that disappeared.
 */
public record WaypointSyncPayload(boolean full, List<ClientWaypoint> changed, List<String> removed) implements CustomPacketPayload {
    public static final Type<WaypointSyncPayload> TYPE = new Type<>(Storybound.id("sync_waypoints"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WaypointSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, WaypointSyncPayload::full,
            ClientWaypoint.STREAM_CODEC.apply(ByteBufCodecs.list()), WaypointSyncPayload::changed,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), WaypointSyncPayload::removed,
            WaypointSyncPayload::new);

    @Override
    public Type<WaypointSyncPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(WaypointSyncPayload payload, IPayloadContext context) {
        ClientWaypoints.apply(payload.full(), payload.changed(), payload.removed());
    }
}
