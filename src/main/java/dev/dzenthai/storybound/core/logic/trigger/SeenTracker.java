package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.logic.StoryContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Decides that a player saw an entity and fires {@code entity_seen}. Every {@link #INTERVAL} ticks, for each
 * player, living entities within {@code detection.seenRadius} that some {@code entity_seen} subscription
 * cares about are checked: inside the field of view ({@code detection.seenAngle}) and in line of sight.
 * Time in view adds up; at {@code detection.seenTicks} the trigger fires once for that entity, until it
 * leaves the range. Does nothing while nobody subscribes to {@code entity_seen}.
 */
public final class SeenTracker {
    public static final int INTERVAL = 10;

    /** Player → entity → ticks in view so far. */
    private static final Map<UUID, Map<UUID, Integer>> IN_VIEW = new HashMap<>();
    /** Player → entities already reported, until they leave the range. */
    private static final Map<UUID, Set<UUID>> REPORTED = new HashMap<>();

    private SeenTracker() {}

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % INTERVAL != 0) {
            return;
        }
        if (!TriggerDispatcher.hasSubscribers(BuiltinTriggers.ENTITY_SEEN.get())) {
            IN_VIEW.clear();
            REPORTED.clear();
            return;
        }
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            check(player);
        }
    }

    /** One check for one player; public for tests. */
    public static void check(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) {
            forget(player.getUUID());
            return;
        }
        StoryboundConfig.Common config = StoryboundConfig.COMMON;
        double radius = config.seenRadius.get();
        double minCos = Math.cos(Math.toRadians(config.seenAngle.get()));
        int needed = config.seenTicks.get();
        Map<UUID, Integer> inView = IN_VIEW.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        Set<UUID> reported = REPORTED.computeIfAbsent(player.getUUID(), id -> new HashSet<>());
        Set<UUID> present = new HashSet<>();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f);
        List<Entity> candidates = player.level().getEntities(player, player.getBoundingBox().inflate(radius),
                entity -> entity instanceof LivingEntity && entity.isAlive() && entity.distanceToSqr(player) <= radius * radius);
        for (Entity entity : candidates) {
            StoryContext context = new StoryContext(player, entity);
            if (!TriggerDispatcher.anyMatches(BuiltinTriggers.ENTITY_SEEN.get(), context, entity)) {
                continue;
            }
            UUID id = entity.getUUID();
            present.add(id);
            if (reported.contains(id)) {
                continue;
            }
            Vec3 toEntity = entity.getEyePosition().subtract(eye);
            boolean visible = toEntity.lengthSqr() < 1e-6 || toEntity.normalize().dot(look) >= minCos;
            if (!visible || !player.hasLineOfSight(entity)) {
                continue;
            }
            int ticks = inView.merge(id, INTERVAL, Integer::sum);
            if (ticks >= needed) {
                inView.remove(id);
                reported.add(id);
                TriggerDispatcher.fire(BuiltinTriggers.ENTITY_SEEN.get(), context, entity);
            }
        }
        // Leaving the range resets an entity: coming back, it can be seen again.
        inView.keySet().retainAll(present);
        reported.retainAll(present);
    }

    static void forget(UUID player) {
        IN_VIEW.remove(player);
        REPORTED.remove(player);
    }

    static void reset() {
        IN_VIEW.clear();
        REPORTED.clear();
    }
}
