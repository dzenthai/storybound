package dev.dzenthai.storybound.module.waypoint;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The real tracks of creatures that trails follow ({@code "trace": { }} with a creature target): where each one has
 * stepped on the ground lately, kept on the server, in memory only. A creature is followed while a player's waypoint
 * asks for it; its steps are taken every {@link #SAMPLE_INTERVAL} ticks once it has moved a stride, at most
 * {@link #MAX_STEPS} and for {@link #LIFETIME} ticks, so a trail cools off behind it. A creature nobody has asked for in
 * {@link #FORGET_AFTER} ticks is forgotten. An unloaded creature keeps the steps it had, which stay until they cool.
 */
public final class WaypointTracks {
    private static final int SAMPLE_INTERVAL = 2;
    private static final long LIFETIME = 6000;
    private static final int MAX_STEPS = 256;
    private static final long FORGET_AFTER = 1200;
    /** The width of a walker whose size is not known: a player's. */
    public static final float DEFAULT_WIDTH = 0.6f;

    private record Step(Vec3 pos, long time) {}

    private static final class Track {
        WeakReference<Entity> entity;
        float width;
        final ArrayDeque<Step> steps = new ArrayDeque<>();
        long asked;

        Track(Entity entity) {
            this.entity = new WeakReference<>(entity);
            this.width = entity.getBbWidth();
        }
    }

    /** What a client gets: the steps, oldest first, and the walker's width, which sets its stride. */
    public record Steps(List<Vec3> points, float width) {
        static final Steps NONE = new Steps(List.of(), DEFAULT_WIDTH);
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    /** The creature a player's waypoint followed last, so its steps are still shown while it is not loaded. */
    private static final Map<String, UUID> LAST = new HashMap<>();

    private WaypointTracks() {}

    /** The steps of the creature a player's waypoint finds now ({@code entity}, null while it is not loaded). */
    static Steps of(MinecraftServer server, ServerPlayer player, String key, @Nullable Entity entity) {
        long now = server.getTickCount();
        String asker = player.getUUID() + "|" + key;
        Track track;
        if (entity != null) {
            LAST.put(asker, entity.getUUID());
            track = TRACKS.computeIfAbsent(entity.getUUID(), id -> new Track(entity));
            track.entity = new WeakReference<>(entity);
            track.width = entity.getBbWidth();
        } else {
            UUID last = LAST.get(asker);
            track = last == null ? null : TRACKS.get(last);
        }
        if (track == null) {
            return Steps.NONE;
        }
        track.asked = now;
        return new Steps(track.steps.stream().map(Step::pos).toList(), track.width);
    }

    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % SAMPLE_INTERVAL != 0 || TRACKS.isEmpty()) {
            return;
        }
        for (Iterator<Track> it = TRACKS.values().iterator(); it.hasNext(); ) {
            Track track = it.next();
            if (now - track.asked > FORGET_AFTER) {
                it.remove();
                continue;
            }
            while (!track.steps.isEmpty() && now - track.steps.peekFirst().time() > LIFETIME) {
                track.steps.pollFirst();
            }
            Entity entity = track.entity.get();
            if (entity == null || entity.isRemoved() || !entity.onGround()) {
                continue;
            }
            Vec3 pos = entity.position();
            // A step at least a block apart, more for a big creature: its prints are drawn along these.
            double stride = Math.max(1, entity.getBbWidth());
            if (track.steps.isEmpty() || track.steps.peekLast().pos().distanceTo(pos) >= stride) {
                track.steps.addLast(new Step(pos, now));
                if (track.steps.size() > MAX_STEPS) {
                    track.steps.pollFirst();
                }
            }
        }
        LAST.values().removeIf(id -> !TRACKS.containsKey(id));
    }

    static void reset() {
        TRACKS.clear();
        LAST.clear();
    }
}
