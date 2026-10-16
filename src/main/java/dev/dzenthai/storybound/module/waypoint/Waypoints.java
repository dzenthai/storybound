package dev.dzenthai.storybound.module.waypoint;

import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import dev.dzenthai.storybound.network.WaypointSyncPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Decides which waypoints each player sees and keeps their client up to date: the full list on login and
 * after {@code /reload}, then only what changed. Visibility is recomputed when story state, quests or
 * parties change, and every few seconds for conditions nothing announces (items, location); waypoints
 * that follow an entity move once per second.
 */
public final class Waypoints {
    private static final int FOLLOW_INTERVAL = 20;
    private static final int RECHECK_INTERVAL = 100;

    /** Static waypoint → the quest stages that list it in their {@code waypoints}. */
    private static Map<ResourceLocation, List<Binding>> stageBindings = Map.of();
    /** Player → what their client has now. */
    private static final Map<UUID, Map<String, ClientWaypoint>> SENT = new HashMap<>();
    /** Followed entity → where it was last seen, for when it is not loaded. */
    private static final Map<UUID, Place> LAST_SEEN = new HashMap<>();

    private Waypoints() {}

    /** {@code modules.waypoints}; while disabled, clients get no waypoints and actions fail. */
    public static boolean isEnabled() {
        return StoryboundConfig.COMMON.waypoints.get();
    }

    /** Collects which quest stages list which waypoints; after every data load. */
    /** Public for tests. */
    public static void rebuild() {
        Map<ResourceLocation, List<Binding>> bindings = new HashMap<>();
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> {
            for (QuestDefinition.Stage stage : definition.stages()) {
                for (ResourceLocation waypoint : stage.waypoints()) {
                    bindings.computeIfAbsent(waypoint, id -> new ArrayList<>()).add(new Binding(quest, stage.id()));
                }
            }
        });
        stageBindings = bindings;
    }

    /** What the player should see now, by key. Public for tests. */
    public static Map<String, ClientWaypoint> visible(ServerPlayer player) {
        Map<String, ClientWaypoint> result = new LinkedHashMap<>();
        if (!isEnabled()) {
            return result;
        }
        StoryContext context = StoryContext.of(player);
        WaypointRegistries.WAYPOINTS.serverStore().all().forEach((id, definition) -> {
            List<Binding> bindings = new ArrayList<>(stageBindings.getOrDefault(id, List.of()));
            definition.quest().ifPresent(quest -> bindings.add(new Binding(quest, definition.stage().orElse(null))));
            Optional<ResourceLocation> quest = Optional.empty();
            if (!bindings.isEmpty()) {
                Binding active = bindings.stream().filter(binding -> binding.isActive(player)).findFirst().orElse(null);
                if (active == null) {
                    return;
                }
                quest = Optional.of(active.quest());
            }
            if (!definition.conditions().test(context)) {
                return;
            }
            Place place = locate(player.server, definition.target());
            if (place != null) {
                String key = id.toString();
                result.put(key, new ClientWaypoint(key, definition.name().resolve(player), place.dimension(), place.pos(), definition.radius(),
                        definition.color(), definition.beam(), quest));
            }
        });
        for (WaypointData.Added added : WaypointData.get(player.server).all()) {
            if (!sees(player, added)) {
                continue;
            }
            Place place = locate(player.server, added.target());
            if (place != null) {
                result.put(added.key(), new ClientWaypoint(added.key(), added.name().resolve(player), place.dimension(), place.pos(), added.radius(),
                        added.color(), added.beam(), Optional.empty()));
            }
        }
        return result;
    }

    /** Sends the player everything they see, replacing what their client had. */
    public static void syncFull(ServerPlayer player) {
        Map<String, ClientWaypoint> now = visible(player);
        SENT.put(player.getUUID(), now);
        StoryboundNetwork.sendToPlayer(player, new WaypointSyncPayload(true, List.copyOf(now.values()), List.of()));
    }

    /** Sends only what changed since the last sync; nothing when nothing did. */
    public static void refresh(ServerPlayer player) {
        Map<String, ClientWaypoint> before = SENT.get(player.getUUID());
        if (before == null) {
            syncFull(player);
            return;
        }
        Map<String, ClientWaypoint> now = visible(player);
        List<ClientWaypoint> changed = now.values().stream().filter(waypoint -> !Objects.equals(before.get(waypoint.key()), waypoint)).toList();
        List<String> removed = before.keySet().stream().filter(key -> !now.containsKey(key)).toList();
        if (changed.isEmpty() && removed.isEmpty()) {
            return;
        }
        SENT.put(player.getUUID(), now);
        StoryboundNetwork.sendToPlayer(player, new WaypointSyncPayload(false, changed, removed));
    }

    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            refresh(player);
        }
    }

    /** Adds (or replaces) a waypoint seen by the player, their party or everyone, and updates whoever sees it. */
    public static boolean add(ServerPlayer player, ResourceLocation id, StoryText name, WaypointTarget target,
                              int color, float radius, boolean beam, StoryScope scope) {
        if (!isEnabled()) {
            return false;
        }
        UUID owner = StoryState.owner(player, scope);
        WaypointData.get(player.server).put(new WaypointData.Added(id, name, target, color, radius, beam, scope, Optional.ofNullable(owner)));
        refreshScope(player.server, scope, owner);
        return true;
    }

    /** A world waypoint from a command, without a player behind it. */
    public static void addShared(MinecraftServer server, ResourceLocation id, StoryText name, WaypointTarget target, int color, boolean beam) {
        WaypointData.get(server).put(new WaypointData.Added(id, name, target, color, 0f, beam, StoryScope.WORLD, Optional.empty()));
        refreshAll(server);
    }

    /** Turns the beam of an added waypoint on or off. @return false when there is no such waypoint */
    public static boolean setBeam(MinecraftServer server, ResourceLocation id, StoryScope scope, @Nullable UUID owner, boolean beam) {
        boolean changed = WaypointData.get(server).setBeam(id, scope, owner, beam);
        if (changed) {
            refreshScope(server, scope, owner);
        }
        return changed;
    }

    public static boolean remove(MinecraftServer server, ResourceLocation id, StoryScope scope, @Nullable UUID owner) {
        boolean removed = WaypointData.get(server).remove(id, scope, owner);
        if (removed) {
            refreshScope(server, scope, owner);
        }
        return removed;
    }

    static void refreshScope(MinecraftServer server, StoryScope scope, @Nullable UUID owner) {
        for (ServerPlayer member : StoryState.onlinePlayersOf(server, scope, owner)) {
            refresh(member);
        }
    }

    static void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick % RECHECK_INTERVAL == 0) {
            refreshAll(server);
        } else if (tick % FOLLOW_INTERVAL == 0) {
            // Only players who see a waypoint that follows an entity; the others cannot have moved targets.
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                if (followsSomething(player)) {
                    refresh(player);
                }
            }
        }
    }

    static void forget(ServerPlayer player) {
        SENT.remove(player.getUUID());
    }

    static void reset() {
        SENT.clear();
        LAST_SEEN.clear();
    }

    private static boolean followsSomething(ServerPlayer player) {
        Map<String, ClientWaypoint> sent = SENT.get(player.getUUID());
        if (sent == null || sent.isEmpty()) {
            return false;
        }
        for (WaypointDefinition definition : WaypointRegistries.WAYPOINTS.serverStore().all().values()) {
            if (definition.target() instanceof WaypointTarget.Follow) {
                return true;
            }
        }
        return WaypointData.get(player.server).all().stream().anyMatch(added -> added.target() instanceof WaypointTarget.Follow && sees(player, added));
    }

    private static boolean sees(ServerPlayer player, WaypointData.Added added) {
        return switch (added.scope()) {
            case WORLD -> true;
            case PLAYER, PARTY -> added.owner().map(owner -> owner.equals(StoryState.owner(player, added.scope()))).orElse(false);
        };
    }

    private static @Nullable Place locate(MinecraftServer server, WaypointTarget target) {
        return switch (target) {
            case WaypointTarget.Fixed fixed -> new Place(fixed.dimension().location(), fixed.pos());
            case WaypointTarget.Follow follow -> {
                for (ServerLevel level : server.getAllLevels()) {
                    Entity entity = level.getEntity(follow.entity());
                    if (entity != null && entity.isAlive()) {
                        // Rounded so a mob shuffling in place does not resend the waypoint every second.
                        Vec3 pos = new Vec3(Math.round(entity.getX() * 2) / 2.0, Math.round(entity.getY() * 2) / 2.0, Math.round(entity.getZ() * 2) / 2.0);
                        Place place = new Place(level.dimension().location(), pos.add(0, entity.getBbHeight(), 0));
                        LAST_SEEN.put(follow.entity(), place);
                        yield place;
                    }
                }
                yield LAST_SEEN.get(follow.entity());
            }
        };
    }

    /** A quest, and the stage it must be at (any stage when null). */
    private record Binding(ResourceLocation quest, @Nullable String stage) {
        boolean isActive(ServerPlayer player) {
            Optional<QuestProgress> progress = Quests.progress(player, quest);
            return progress.isPresent() && progress.get().status() == QuestStatus.ACTIVE
                    && (stage == null || progress.get().stage().equals(Optional.of(stage)));
        }
    }

    private record Place(ResourceLocation dimension, Vec3 pos) {}
}
