package dev.dzenthai.storybound.module.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where the player last saw the entity of each waypoint that follows one ({@code entity} and {@code nearest} targets),
 * so the waypoint can still show that place while the entity's chunk is not loaded, also after a relog or a restart
 * (attachment {@code storybound:waypoint_sightings}, personal, kept through death).
 */
public record WaypointSightings(Map<String, Sighting> seen) {
    public static final WaypointSightings EMPTY = new WaypointSightings(Map.of());
    public static final Codec<WaypointSightings> CODEC = Codec.unboundedMap(Codec.STRING, Sighting.CODEC)
            .xmap(WaypointSightings::new, WaypointSightings::seen);

    public WaypointSightings {
        seen = Map.copyOf(seen);
    }

    /** @param pos above the entity's head, as the waypoint shows it */
    public record Sighting(UUID entity, ResourceLocation dimension, Vec3 pos) {
        public static final Codec<Sighting> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("entity").forGetter(Sighting::entity),
                ResourceLocation.CODEC.fieldOf("dimension").forGetter(Sighting::dimension),
                Vec3.CODEC.fieldOf("pos").forGetter(Sighting::pos)
        ).apply(instance, Sighting::new));
    }

    static @Nullable Sighting get(ServerPlayer player, String key) {
        return player.getData(WaypointRegistries.SIGHTINGS).seen().get(key);
    }

    static void put(ServerPlayer player, String key, Sighting sighting) {
        WaypointSightings current = player.getData(WaypointRegistries.SIGHTINGS);
        if (sighting.equals(current.seen().get(key))) {
            return;
        }
        Map<String, Sighting> next = new HashMap<>(current.seen());
        next.put(key, sighting);
        player.setData(WaypointRegistries.SIGHTINGS, new WaypointSightings(next));
    }

    static void forget(ServerPlayer player, String key) {
        WaypointSightings current = player.getData(WaypointRegistries.SIGHTINGS);
        if (current.seen().containsKey(key)) {
            Map<String, Sighting> next = new HashMap<>(current.seen());
            next.remove(key);
            player.setData(WaypointRegistries.SIGHTINGS, new WaypointSightings(next));
        }
    }
}
