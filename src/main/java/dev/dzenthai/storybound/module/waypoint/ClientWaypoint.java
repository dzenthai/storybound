package dev.dzenthai.storybound.module.waypoint;

import dev.dzenthai.storybound.core.text.ResolvedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * A waypoint as a client sees it: already resolved for that player and placed.
 *
 * @param key    unique per client: a static waypoint's id, or {@code <scope>/<id>} for one added in game
 * @param entity the network id of the entity the waypoint is above ({@code entity} and {@code nearest} targets), so
 *               the client can draw the marker at the entity every frame while it has it; {@link #NO_ENTITY} otherwise
 * @param quest  the quest it belongs to, so the tracked quest's waypoints can be highlighted
 * @param showRadius for an area: write its radius by the name
 * @param showRing   for an area: draw its edge on the ground
 * @param placed     false when only its dimension is known (a structure in a dimension the player is not in, not
 *                   searched for): nothing is drawn, the quest tracker names the dimension
 * @param marker     what the marker shows: a point's icon, the event's "!!!" or the question's "?"
 * @param traceKind  how its trail is drawn, {@link WaypointTrace.Kind#NONE} for a marker
 * @param trace      the trail's points, from start to end (a creature's tracks: oldest first)
 * @param traceSize  the walker's width, which sets its stride
 */
public record ClientWaypoint(String key, ResolvedText name, ResourceLocation dimension, Vec3 pos, int entity, float radius,
                             boolean showRadius, boolean showRing, int color, Optional<ResourceLocation> quest, boolean placed,
                             AreaLook.Marker marker, WaypointTrace.Kind traceKind, List<Vec3> trace, float traceSize) {
    public static final int NO_ENTITY = -1;
    // More fields than StreamCodec.composite takes.
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientWaypoint> STREAM_CODEC = StreamCodec.of(ClientWaypoint::write, ClientWaypoint::read);

    private static ClientWaypoint read(RegistryFriendlyByteBuf buf) {
        return new ClientWaypoint(ByteBufCodecs.STRING_UTF8.decode(buf), ResolvedText.STREAM_CODEC.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readVarInt() - 1, buf.readFloat(), buf.readBoolean(), buf.readBoolean(),
                buf.readInt(),
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).decode(buf), buf.readBoolean(), buf.readEnum(AreaLook.Marker.class),
                buf.readEnum(WaypointTrace.Kind.class), readPoints(buf), buf.readFloat());
    }

    /** A trail is a waypoint drawn as its trail alone: no marker, no edge arrow, nothing on the maps. */
    public boolean isTrace() {
        return traceKind != WaypointTrace.Kind.NONE;
    }

    private static List<Vec3> readPoints(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Vec3> points = new ArrayList<>(Math.min(size, WaypointTrace.MAX_POINTS));
        for (int i = 0; i < size; i++) {
            points.add(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        return List.copyOf(points);
    }

    private static void write(RegistryFriendlyByteBuf buf, ClientWaypoint waypoint) {
        ByteBufCodecs.STRING_UTF8.encode(buf, waypoint.key());
        ResolvedText.STREAM_CODEC.encode(buf, waypoint.name());
        ResourceLocation.STREAM_CODEC.encode(buf, waypoint.dimension());
        buf.writeDouble(waypoint.pos().x);
        buf.writeDouble(waypoint.pos().y);
        buf.writeDouble(waypoint.pos().z);
        buf.writeVarInt(waypoint.entity() + 1);
        buf.writeFloat(waypoint.radius());
        buf.writeBoolean(waypoint.showRadius());
        buf.writeBoolean(waypoint.showRing());
        buf.writeInt(waypoint.color());
        ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).encode(buf, waypoint.quest());
        buf.writeBoolean(waypoint.placed());
        buf.writeEnum(waypoint.marker());
        buf.writeEnum(waypoint.traceKind());
        buf.writeVarInt(waypoint.trace().size());
        for (Vec3 point : waypoint.trace()) {
            buf.writeDouble(point.x);
            buf.writeDouble(point.y);
            buf.writeDouble(point.z);
        }
        buf.writeFloat(waypoint.traceSize());
    }
}
