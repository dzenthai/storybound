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
 * @param key   unique per client: a static waypoint's id, or {@code <scope>/<id>} for one added in game
 * @param beam  a light beam is drawn at it in the world
 * @param quest the quest it belongs to, so the tracked quest's waypoints can be highlighted
 */
public record ClientWaypoint(String key, ResolvedText name, ResourceLocation dimension, Vec3 pos, float radius, int color,
                             boolean beam, Optional<ResourceLocation> quest) {
    // More fields than StreamCodec.composite takes.
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientWaypoint> STREAM_CODEC = StreamCodec.of(ClientWaypoint::write, ClientWaypoint::read);

    private static ClientWaypoint read(RegistryFriendlyByteBuf buf) {
        return new ClientWaypoint(ByteBufCodecs.STRING_UTF8.decode(buf), ResolvedText.STREAM_CODEC.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readFloat(), buf.readInt(),
                buf.readBoolean(), ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).decode(buf));
    }

    private static void write(RegistryFriendlyByteBuf buf, ClientWaypoint waypoint) {
        ByteBufCodecs.STRING_UTF8.encode(buf, waypoint.key());
        ResolvedText.STREAM_CODEC.encode(buf, waypoint.name());
        ResourceLocation.STREAM_CODEC.encode(buf, waypoint.dimension());
        buf.writeDouble(waypoint.pos().x);
        buf.writeDouble(waypoint.pos().y);
        buf.writeDouble(waypoint.pos().z);
        buf.writeFloat(waypoint.radius());
        buf.writeInt(waypoint.color());
        buf.writeBoolean(waypoint.beam());
        ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).encode(buf, waypoint.quest());
    }
}
