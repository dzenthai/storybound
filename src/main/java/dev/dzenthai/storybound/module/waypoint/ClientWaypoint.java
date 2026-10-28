package dev.dzenthai.storybound.module.waypoint;

import dev.dzenthai.storybound.core.text.ResolvedText;
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
 * @param style  the marker style from the client's resource packs
 * @param showRadius for an area: write its radius by the name
 * @param showRing   for an area: draw its edge on the ground
 */
public record ClientWaypoint(String key, ResolvedText name, ResourceLocation dimension, Vec3 pos, int entity, float radius,
                             boolean showRadius, boolean showRing, int color, Optional<ResourceLocation> quest, ResourceLocation style) {
    public static final int NO_ENTITY = -1;
    // More fields than StreamCodec.composite takes.
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientWaypoint> STREAM_CODEC = StreamCodec.of(ClientWaypoint::write, ClientWaypoint::read);

    private static ClientWaypoint read(RegistryFriendlyByteBuf buf) {
        return new ClientWaypoint(ByteBufCodecs.STRING_UTF8.decode(buf), ResolvedText.STREAM_CODEC.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readVarInt() - 1, buf.readFloat(), buf.readBoolean(), buf.readBoolean(),
                buf.readInt(),
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).decode(buf), ResourceLocation.STREAM_CODEC.decode(buf));
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
        ResourceLocation.STREAM_CODEC.encode(buf, waypoint.style());
    }
}
