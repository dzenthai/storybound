package dev.dzenthai.storybound.module.waypoint;

import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.logic.EntityReturns;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StructureRef;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Decides which waypoints each player sees and keeps their client up to date: the full list on login and
 * after {@code /reload}, then only what changed. Visibility is recomputed when story state, quests or
 * parties change, and every few seconds for conditions nothing announces (items, location); waypoints
 * that follow an entity or point at the nearest one move once per second.
 */
public final class Waypoints {
    private static final int FOLLOW_INTERVAL = 20;
    private static final int RECHECK_INTERVAL = 100;

    /** Static waypoint → the quest stages that list it in their {@code waypoints}. */
    private static Map<ResourceLocation, List<Binding>> stageBindings = Map.of();
    /** Player → what their client has now. */
    private static final Map<UUID, Map<String, ClientWaypoint>> SENT = new HashMap<>();
    /** Followed entity → where it was last seen, for when it is not loaded (without the entity id: the client has no such entity either). */
    private static final Map<UUID, WaypointSightings.Sighting> LAST_SEEN = new HashMap<>();
    /**
     * Structure searches from a player start from the middle of the 256-block cell they stand in, so players near
     * each other share one background search, and walking within a cell starts none.
     */
    private static final int SEARCH_CELL_SHIFT = 8;
    /** Player → waypoint key with a structure target and no origin → the search their waypoint shows now. */
    private static final Map<UUID, Map<String, BlockPos>> SEARCH_ORIGINS = new HashMap<>();
    /** Player → waypoint key → a search started since they moved to another cell, not yet answered. */
    private static final Map<UUID, Map<String, BlockPos>> SEARCH_CANDIDATES = new HashMap<>();
    /** A structure waypoint moves to a newly found structure only when it is at least this much nearer, in blocks. */
    private static final double STRUCTURE_SWITCH_MARGIN = 16;
    /**
     * A {@code nearest} waypoint keeps its pick while the entity lives and stays within this many times the range, so
     * the marker does not hop between two mobs at about the same distance.
     */
    private static final double KEEP_PICK_RANGE = 1.25;
    /** The search area shown around where an entity out of sight was last seen, in blocks. */
    private static final float SIGHTING_RADIUS = 16;
    /**
     * Player → waypoint key with a {@code nearest} target → the entity it points at for them. The entity object itself,
     * so that once it is gone from the level its removal reason tells an unloaded chunk from a death.
     */
    private static final Map<UUID, Map<String, Entity>> NEAREST_PICKS = new HashMap<>();

    private Waypoints() {}

    /** {@code modules.waypoints}; while disabled, clients get no waypoints and actions fail. */
    public static boolean isEnabled() {
        return StoryboundConfig.COMMON.waypoints.get();
    }

    /** Collects which quest stages list which waypoints; after every data load. Public for tests. */
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
        // Waypoint definitions may have changed what they search for.
        SEARCH_ORIGINS.clear();
        SEARCH_CANDIDATES.clear();
        NEAREST_PICKS.clear();
        StructureLocator.reset();
    }

    /**
     * Who shares what a waypoint points at with the player: for a waypoint shown for a party or world quest, the others
     * online who share that quest (what one of them found, the others are looking for too); nobody for a personal
     * quest (each player may have their own of it: their own reaper) or a waypoint of no quest.
     */
    private static List<ServerPlayer> companions(ServerPlayer player, Optional<ResourceLocation> quest) {
        QuestDefinition definition = quest.map(Quests::definition).orElse(null);
        if (definition == null || definition.scope() == StoryScope.PLAYER) {
            return List.of();
        }
        return StoryState.onlinePlayersOf(player.server, definition.scope(), StoryState.owner(player, definition.scope()));
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
            String key = id.toString();
            Place place = locate(player, key, definition.target(), companions(player, quest));
            if (place != null) {
                result.put(key, new ClientWaypoint(key, definition.name().resolve(player), place.dimension(), place.pos(), place.entity(),
                        place.radius(definition.radius()), place.showRadius(definition.radius(), definition.look()),
                        place.showRing(definition.radius(), definition.look()), definition.color(), quest, definition.style()));
            }
        });
        for (WaypointData.Added added : WaypointData.get(player.server).all()) {
            if (!sees(player, added)) {
                continue;
            }
            Place place = locate(player, added.key(), added.target(), added.scope() == StoryScope.PLAYER ? List.of()
                    : StoryState.onlinePlayersOf(player.server, added.scope(), StoryState.owner(player, added.scope())));
            if (place != null) {
                result.put(added.key(), new ClientWaypoint(added.key(), added.name().resolve(player), place.dimension(), place.pos(), place.entity(),
                        place.radius(added.radius()), place.showRadius(added.radius(), added.look()), place.showRing(added.radius(), added.look()),
                        added.color(), Optional.empty(), added.style()));
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
                              int color, float radius, AreaLook look, StoryScope scope, ResourceLocation style) {
        if (!isEnabled()) {
            return false;
        }
        UUID owner = StoryState.owner(player, scope);
        WaypointData.get(player.server).put(new WaypointData.Added(id, name, target, color, radius, look, scope, Optional.ofNullable(owner), style));
        refreshScope(player.server, scope, owner);
        return true;
    }

    /** A world waypoint from a command, without a player behind it. */
    public static void addShared(MinecraftServer server, ResourceLocation id, StoryText name, WaypointTarget target, int color) {
        WaypointData.get(server).put(new WaypointData.Added(id, name, target, color, 0f, AreaLook.DEFAULT, StoryScope.WORLD, Optional.empty(),
                WaypointDefinition.DEFAULT_STYLE));
        refreshAll(server);
    }

    public static boolean remove(MinecraftServer server, ResourceLocation id, StoryScope scope, @Nullable UUID owner) {
        boolean removed = WaypointData.get(server).remove(id, scope, owner);
        if (removed) {
            refreshScope(server, scope, owner);
        }
        return removed;
    }

    /** What {@link #teleportTo} did. */
    public enum Teleport {
        DONE,
        /** A structure waypoint whose background search has not answered yet; asking again later teleports. */
        SEARCHING,
        NOT_FOUND
    }

    /**
     * Dev mode: teleports the player to a static waypoint as they would see it. A structure is never searched for on
     * the server thread here either (a search can take minutes): it is asked of the background search, like the
     * waypoint itself.
     */
    public static Teleport teleportTo(ServerPlayer player, ResourceLocation id) {
        WaypointDefinition waypoint = WaypointRegistries.WAYPOINTS.serverStore().getOrNull(id);
        if (waypoint == null) {
            return Teleport.NOT_FOUND;
        }
        Place place = locate(player, id.toString(), waypoint.target(), List.of());
        if (place == null) {
            if (waypoint.target() instanceof WaypointTarget.Structure structure) {
                BlockPos origin = structure.origin().orElseGet(() -> structureSearchOrigin(player, structure.dimension()));
                if (StructureLocator.result(player.server, structure.searchFrom(origin)) == null) {
                    return Teleport.SEARCHING;
                }
            }
            return Teleport.NOT_FOUND;
        }
        ServerLevel level = player.server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, place.dimension()));
        if (level == null) {
            return Teleport.NOT_FOUND;
        }
        Vec3 pos = place.pos();
        double y = pos.y;
        if (waypoint.target() instanceof WaypointTarget.Structure) {
            BlockPos bp = BlockPos.containing(pos.x, pos.y, pos.z);
            level.getChunk(bp);
            y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bp.getX(), bp.getZ());
        }
        player.teleportTo(level, pos.x, y, pos.z, java.util.Set.of(), player.getYRot(), player.getXRot());
        return Teleport.DONE;
    }

    static void refreshScope(MinecraftServer server, StoryScope scope, @Nullable UUID owner) {
        for (ServerPlayer member : StoryState.onlinePlayersOf(server, scope, owner)) {
            refresh(member);
        }
    }

    static void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            // Each player on ticks of their own, so a full server spreads the work instead of a spike.
            int own = tick + player.getId();
            if (Math.floorMod(own, RECHECK_INTERVAL) == 0) {
                refresh(player);
            } else if (Math.floorMod(own, FOLLOW_INTERVAL) == 0 && followsSomething(player)) {
                // Only players who see a waypoint that follows an entity; the others cannot have moved targets.
                refresh(player);
            }
        }
    }

    static void forget(ServerPlayer player) {
        SENT.remove(player.getUUID());
        SEARCH_ORIGINS.remove(player.getUUID());
        SEARCH_CANDIDATES.remove(player.getUUID());
        NEAREST_PICKS.remove(player.getUUID());
    }

    static void reset() {
        SENT.clear();
        LAST_SEEN.clear();
        SEARCH_ORIGINS.clear();
        SEARCH_CANDIDATES.clear();
        NEAREST_PICKS.clear();
        StructureLocator.reset();
    }

    /**
     * A waypoint at the structure nearest to this player. With an {@code origin} in the target, searched from there,
     * the same for everyone. Otherwise it follows the player: the first search starts from the cell they stand in (or
     * the dimension's spawn when they are in another dimension); once they move to another cell, a search from there
     * runs in the background, one at a time per waypoint, while the waypoint keeps its place, and the waypoint moves
     * only when the new structure is nearer to the player by {@link #STRUCTURE_SWITCH_MARGIN}. Null until found.
     */
    private static @Nullable Place nearestStructure(ServerPlayer player, String key, WaypointTarget.Structure target) {
        MinecraftServer server = player.server;
        if (target.origin().isPresent()) {
            return structurePlace(server, target, target.origin().get());
        }
        BlockPos here = structureSearchOrigin(player, target.dimension());
        Map<String, BlockPos> origins = SEARCH_ORIGINS.computeIfAbsent(player.getUUID(), uuid -> new HashMap<>());
        Map<String, BlockPos> candidates = SEARCH_CANDIDATES.computeIfAbsent(player.getUUID(), uuid -> new HashMap<>());
        BlockPos shown = origins.computeIfAbsent(key, k -> here);
        if (here.equals(shown)) {
            candidates.remove(key);
        } else {
            BlockPos trying = candidates.computeIfAbsent(key, k -> here);
            Optional<BlockPos> found = StructureLocator.result(server, target.searchFrom(trying));
            if (found != null) {
                candidates.remove(key);
                Optional<BlockPos> current = StructureLocator.result(server, target.searchFrom(shown));
                if (found.isPresent() && (current == null || current.isEmpty() || nearer(player, found.get(), current.get()))) {
                    origins.put(key, trying);
                    shown = trying;
                }
            }
        }
        return structurePlace(server, target, shown);
    }

    private static boolean nearer(ServerPlayer player, BlockPos candidate, BlockPos current) {
        return Math.sqrt(horizontalDistanceSqr(player, candidate)) < Math.sqrt(horizontalDistanceSqr(player, current)) - STRUCTURE_SWITCH_MARGIN;
    }

    private static double horizontalDistanceSqr(ServerPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Where a structure search for this player starts: the middle of the 256-block cell they stand in, or the
     * dimension's spawn when they are in another dimension. Public for tests.
     */
    public static BlockPos structureSearchOrigin(ServerPlayer player, ResourceKey<Level> dimension) {
        ServerLevel level = player.server.getLevel(dimension);
        BlockPos from = level == null || player.level() == level ? player.blockPosition() : level.getSharedSpawnPos();
        int half = 1 << (SEARCH_CELL_SHIFT - 1);
        return new BlockPos(((from.getX() >> SEARCH_CELL_SHIFT) << SEARCH_CELL_SHIFT) + half, 0, ((from.getZ() >> SEARCH_CELL_SHIFT) << SEARCH_CELL_SHIFT) + half);
    }

    /** Where the background search put it, the height following the ground once its chunk is loaded; null until then. */
    private static @Nullable Place structurePlace(MinecraftServer server, WaypointTarget.Structure target, BlockPos origin) {
        Optional<BlockPos> found = StructureLocator.result(server, target.searchFrom(origin));
        ServerLevel level = server.getLevel(target.dimension());
        if (found == null || found.isEmpty() || level == null) {
            return null;
        }
        BlockPos at = found.get();
        return new Place(level.dimension().location(), new Vec3(at.getX() + 0.5, StructureRef.groundY(level, at), at.getZ() + 0.5), ClientWaypoint.NO_ENTITY);
    }

    /**
     * The matching entity nearest to this player, in their dimension and within range; the previous pick is kept
     * while it still matches and stays within {@link #KEEP_PICK_RANGE} times the range, and also beyond that while no
     * other match is in range (the player walked away from it). A pick that cannot be found in the player's level (its
     * chunk is not loaded: the player went far away, to another dimension, or joined again far from it) is still
     * there: the waypoint shows the area where it was last seen ({@link WaypointSightings}, kept through relogs) until a
     * loaded match is in range. One that died, was removed or no longer matches is dropped. Null while there is none.
     */
    private static @Nullable Place nearestEntity(ServerPlayer player, String key, WaypointTarget.Nearest target, List<ServerPlayer> companions) {
        Map<String, Entity> picks = NEAREST_PICKS.computeIfAbsent(player.getUUID(), uuid -> new HashMap<>());
        ServerLevel level = player.serverLevel();
        Entity picked = picks.get(key);
        WaypointSightings.Sighting sighting = WaypointSightings.get(player, key);
        UUID pickedId = picked != null ? picked.getUUID() : sighting != null ? sighting.entity() : null;
        // Looked up again: an entity whose chunk loads anew is another object with the same uuid.
        Entity entity = pickedId == null ? null : level.getEntity(pickedId);
        if (entity != null && !entity.isAlive() && !entity.isRemoved() && sighting != null && sighting.entity().equals(pickedId)) {
            // Dying: a mob lies there for its death animation before it leaves the level, and only then is it known
            // whether it comes back (an NPC that respawns) or is gone; until then, where it was seen.
            return lastSeen(sighting);
        }
        boolean lost = entity != null && !isCandidate(entity, target);
        double keep = target.range() * KEEP_PICK_RANGE;
        if (entity != null && !lost && entity.distanceToSqr(player) <= keep * keep) {
            return remember(player, picks, key, entity);
        }
        double range = target.range();
        Entity found = level.getEntities((Entity) null, player.getBoundingBox().inflate(range),
                        candidate -> isCandidate(candidate, target) && candidate.distanceToSqr(player) <= range * range).stream()
                .min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(player)))
                .orElse(null);
        if (found != null) {
            return remember(player, picks, key, found);
        }
        if (entity != null && !lost) {
            return remember(player, picks, key, entity);
        }
        if (entity == null && sighting != null && sighting.entity().equals(pickedId) && stillThere(player, picked, sighting)) {
            return lastSeen(sighting);
        }
        picks.remove(key);
        WaypointSightings.forget(player, key);
        return shared(player, key, target, companions, picks);
    }

    /**
     * For a waypoint the player shares with companions (see {@link #companions}), when the player has nothing of their
     * own: the entity a companion's waypoint points at. Loaded and alive, it becomes the player's own pick (a point,
     * the one nearest to the player); otherwise the area where a companion last saw it. Only when the player's own
     * search found nothing, so their own target, nearer to them, comes first.
     */
    private static @Nullable Place shared(ServerPlayer player, String key, WaypointTarget.Nearest target, List<ServerPlayer> companions,
                                          Map<String, Entity> picks) {
        Entity nearest = null;
        WaypointSightings.Sighting seen = null;
        for (ServerPlayer companion : companions) {
            if (companion == player) {
                continue;
            }
            Entity theirs = NEAREST_PICKS.getOrDefault(companion.getUUID(), Map.of()).get(key);
            Entity loaded = theirs == null ? null : player.serverLevel().getEntity(theirs.getUUID());
            if (loaded != null && isCandidate(loaded, target)) {
                if (nearest == null || loaded.distanceToSqr(player) < nearest.distanceToSqr(player)) {
                    nearest = loaded;
                }
                continue;
            }
            WaypointSightings.Sighting sighting = WaypointSightings.get(companion, key);
            if (seen == null && sighting != null && stillThere(companion, theirs, sighting)) {
                seen = sighting;
            }
        }
        if (nearest != null) {
            return remember(player, picks, key, nearest);
        }
        return seen == null ? null : lastSeen(seen);
    }

    private static Place remember(ServerPlayer player, Map<String, Entity> picks, String key, Entity entity) {
        picks.put(key, entity);
        Place place = placeOf(entity, true);
        WaypointSightings.put(player, key, new WaypointSightings.Sighting(entity.getUUID(), place.dimension(), place.pos()));
        WaypointTargets.get(player.server).seen(entity.getUUID());
        return place;
    }

    private static boolean isCandidate(Entity entity, WaypointTarget.Nearest target) {
        return entity.isAlive() && !(entity instanceof Player) && target.entity().matches(entity);
    }

    /**
     * Whether an entity the player cannot find in their level is still where it was seen. Gone when anyone killed or
     * removed it since ({@link WaypointTargets}, also while this player was far away or offline), or when the object
     * kept for the pick says so. Otherwise there when it is loaded in its level (another dimension than the player's),
     * or when its object was unloaded with its chunk. After a relog there is no object, only the sighting: gone when
     * the entities of the chunk it was seen in are loaded and it is not there (it left meanwhile).
     */
    private static boolean stillThere(ServerPlayer player, @Nullable Entity picked, WaypointSightings.Sighting sighting) {
        if (EntityReturns.comingBack(player.server, sighting.entity())) {
            // Dead but waiting to respawn where it fell: its place is still the place to look.
            return true;
        }
        if (WaypointTargets.get(player.server).isGone(sighting.entity())) {
            return false;
        }
        if (picked != null && picked.getRemovalReason() != null && picked.getRemovalReason().shouldDestroy()) {
            return false;
        }
        ServerLevel level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, sighting.dimension()));
        if (level == null) {
            return false;
        }
        if (level.getEntity(sighting.entity()) != null || picked != null) {
            return true;
        }
        return !level.areEntitiesLoaded(ChunkPos.asLong(BlockPos.containing(sighting.pos())));
    }

    /** An entity out of sight: the area where it was last seen rather than a point it may have left. */
    private static Place lastSeen(WaypointSightings.Sighting sighting) {
        return new Place(sighting.dimension(), sighting.pos(), ClientWaypoint.NO_ENTITY, SIGHTING_RADIUS);
    }

    /**
     * Above the entity's head, rounded so a mob shuffling in place does not resend the waypoint every second.
     *
     * @param loaded whether the client may have the entity to draw the marker at; an unloaded entity's network id may
     *               belong to another entity by now
     */
    private static Place placeOf(Entity entity, boolean loaded) {
        Vec3 pos = new Vec3(Math.round(entity.getX() * 2) / 2.0, Math.round(entity.getY() * 2) / 2.0, Math.round(entity.getZ() * 2) / 2.0);
        return new Place(entity.level().dimension().location(), pos.add(0, entity.getBbHeight(), 0), loaded ? entity.getId() : ClientWaypoint.NO_ENTITY);
    }

    /** Whether the player sees a waypoint that moves on its own: one that follows an entity or picks the nearest. */
    private static boolean followsSomething(ServerPlayer player) {
        Map<String, ClientWaypoint> sent = SENT.get(player.getUUID());
        if (sent == null || sent.isEmpty()) {
            return false;
        }
        for (WaypointDefinition definition : WaypointRegistries.WAYPOINTS.serverStore().all().values()) {
            if (moves(definition.target())) {
                return true;
            }
        }
        return WaypointData.get(player.server).all().stream().anyMatch(added -> moves(added.target()) && sees(player, added));
    }

    private static boolean moves(WaypointTarget target) {
        return target instanceof WaypointTarget.Follow || target instanceof WaypointTarget.Nearest;
    }

    private static boolean sees(ServerPlayer player, WaypointData.Added added) {
        return switch (added.scope()) {
            case WORLD -> true;
            case PLAYER, PARTY -> added.owner().map(owner -> owner.equals(StoryState.owner(player, added.scope()))).orElse(false);
        };
    }

    /** Where the waypoint with this key is for this player; null when it has no place for them now. */
    /** @param companions who shares the waypoint's target with the player (see {@link #companions}) */
    private static @Nullable Place locate(ServerPlayer player, String key, WaypointTarget target, List<ServerPlayer> companions) {
        return switch (target) {
            case WaypointTarget.Fixed fixed -> new Place(fixed.dimension().location(), fixed.pos(), ClientWaypoint.NO_ENTITY);
            case WaypointTarget.Follow follow -> {
                for (ServerLevel level : player.server.getAllLevels()) {
                    Entity entity = level.getEntity(follow.entity());
                    if (entity != null && entity.isAlive()) {
                        Place place = placeOf(entity, true);
                        WaypointSightings.Sighting sighting = new WaypointSightings.Sighting(follow.entity(), place.dimension(), place.pos());
                        LAST_SEEN.put(follow.entity(), sighting);
                        WaypointSightings.put(player, key, sighting);
                        WaypointTargets.get(player.server).seen(follow.entity());
                        yield place;
                    }
                }
                // Not loaded anywhere: where this player last saw it, else where anyone did since the server started.
                WaypointSightings.Sighting own = WaypointSightings.get(player, key);
                WaypointSightings.Sighting seen = own != null && own.entity().equals(follow.entity()) ? own : LAST_SEEN.get(follow.entity());
                yield seen == null || (WaypointTargets.get(player.server).isGone(follow.entity())
                        && !EntityReturns.comingBack(player.server, follow.entity())) ? null : lastSeen(seen);
            }
            case WaypointTarget.Structure structure -> nearestStructure(player, key, structure);
            case WaypointTarget.Nearest nearest -> nearestEntity(player, key, nearest, companions);
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

    /**
     * @param entity the network id of the entity it is above, for the client to follow it smoothly, or {@link ClientWaypoint#NO_ENTITY}
     * @param radius a search area to show at least, for an entity out of sight; negative for the waypoint's own
     */
    private record Place(ResourceLocation dimension, Vec3 pos, int entity, float radius) {
        Place(ResourceLocation dimension, Vec3 pos, int entity) {
            this(dimension, pos, entity, -1);
        }

        float radius(float own) {
            return radius < 0 ? own : Math.max(own, radius);
        }

        /** The area of "where it was last seen" in place of the waypoint's own (a point, or a smaller area). */
        private boolean sighted(float own) {
            return radius >= 0 && own < radius;
        }

        /** "Radius 16 m" would only confuse for a place last seen: the number is not the author's. */
        boolean showRadius(float own, AreaLook look) {
            return !sighted(own) && look.showRadius();
        }

        /** The edge of a place last seen is drawn: it is where to look. */
        boolean showRing(float own, AreaLook look) {
            return sighted(own) || look.showRing();
        }
    }
}
