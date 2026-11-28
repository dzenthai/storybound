package dev.dzenthai.storybound.core.logic;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.dzenthai.storybound.Storybound;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

/**
 * A structure or a structure tag, written {@code "minecraft:village_plains"} or {@code "#minecraft:village"}.
 * Kept as text and looked up in the level's registry only when searched, so a missing structure (a removed mod)
 * makes a waypoint disappear or a trigger never fire instead of failing the whole data load.
 */
public record StructureRef(ResourceLocation id, boolean tag) {
    public static final Codec<StructureRef> CODEC = Codec.STRING.comapFlatMap(StructureRef::parse, StructureRef::toString);
    /** {@code /locate}'s default, in chunks. */
    public static final int DEFAULT_SEARCH_RADIUS = 100;

    private static final Set<StructureRef> REPORTED = ConcurrentHashMap.newKeySet();
    /**
     * The structures each reference stands for, in the registry of {@link #cachedRegistry} only: a holder set keeps its
     * registry, so a cache per registry would keep every world opened in a game; a new registry empties it instead.
     */
    private static final Map<StructureRef, Optional<? extends HolderSet<Structure>>> HOLDER_CACHE = new ConcurrentHashMap<>();
    private static volatile @Nullable Registry<Structure> cachedRegistry;

    /** From script text; empty when it is not a resource location, with or without a leading {@code #}. */
    public static Optional<StructureRef> of(String text) {
        return parse(text).result();
    }

    private static DataResult<StructureRef> parse(String text) {
        boolean tag = text.startsWith("#");
        return ResourceLocation.read(tag ? text.substring(1) : text).map(id -> new StructureRef(id, tag));
    }

    /** Whether the level's registry knows this structure or tag; cheap, unlike a search. */
    public boolean exists(ServerLevel level) {
        return holders(level).isPresent();
    }

    /**
     * Whether {@code pos} lies within such a structure: inside the box around all its pieces, so a village counts
     * between its houses too. Cheap: only the references kept in that chunk are looked at. False for an unknown
     * structure or an unloaded chunk.
     */
    public boolean contains(ServerLevel level, BlockPos pos) {
        return boundingBox(level, pos).isPresent();
    }

    /**
     * Bounding box of the matching structure containing {@code pos}, or empty when {@code pos} is outside any
     * matching structure, the structure is unknown, or the chunk is unloaded.
     */
    public Optional<net.minecraft.world.level.levelgen.structure.BoundingBox> boundingBox(ServerLevel level, BlockPos pos) {
        Optional<? extends HolderSet<Structure>> set = known(level);
        if (set.isEmpty() || !level.isLoaded(pos)) {
            return Optional.empty();
        }
        StructureManager manager = level.structureManager();
        Map<Structure, LongSet> here = manager.getAllStructuresAt(pos);
        if (here.isEmpty()) {
            return Optional.empty();
        }
        return boundingBoxIn(level, manager, pos, here, set.get());
    }

    /**
     * Checks whether {@code pos} lies in any of {@code refs}, performing chunk load and {@code getAllStructuresAt}
     * lookups at most once across the entire list.
     */
    public static boolean containsAny(ServerLevel level, BlockPos pos, List<StructureRef> refs) {
        return boundingBoxAny(level, pos, refs).isPresent();
    }

    /**
     * Returns the bounding box of the first matching structure in {@code refs} containing {@code pos}, sharing a single
     * {@code getAllStructuresAt} lookup across the list.
     */
    public static Optional<BoundingBox> boundingBoxAny(
            ServerLevel level, BlockPos pos, List<StructureRef> refs) {
        if (refs.isEmpty() || !level.isLoaded(pos)) {
            return Optional.empty();
        }
        StructureManager manager = level.structureManager();
        Map<Structure, LongSet> here = manager.getAllStructuresAt(pos);
        if (here.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < refs.size(); i++) {
            Optional<? extends HolderSet<Structure>> set = refs.get(i).known(level);
            if (set.isPresent()) {
                Optional<BoundingBox> box = boundingBoxIn(level, manager, pos, here, set.get());
                if (box.isPresent()) {
                    return box;
                }
            }
        }
        return Optional.empty();
    }

    /** Matches {@code this} against an already-fetched {@code getAllStructuresAt(pos)} map. */
    public boolean containsIn(ServerLevel level, BlockPos pos, Map<Structure, LongSet> here) {
        Optional<? extends HolderSet<Structure>> set = known(level);
        if (set.isEmpty() || here.isEmpty()) {
            return false;
        }
        return boundingBoxIn(level, level.structureManager(), pos, here, set.get()).isPresent();
    }

    private static Optional<BoundingBox> boundingBoxIn(
            ServerLevel level, StructureManager manager, BlockPos pos, Map<Structure, LongSet> here, HolderSet<Structure> set) {
        if (here.size() < set.size()) {
            Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Structure structure : here.keySet()) {
                if (set.contains(registry.wrapAsHolder(structure))) {
                    StructureStart start = manager.getStructureAt(pos, structure);
                    if (start.isValid()) {
                        return Optional.of(start.getBoundingBox());
                    }
                }
            }
            return Optional.empty();
        }
        for (Holder<Structure> holder : set) {
            if (here.containsKey(holder.value())) {
                net.minecraft.world.level.levelgen.structure.StructureStart start = manager.getStructureAt(pos, holder.value());
                if (start.isValid()) {
                    return Optional.of(start.getBoundingBox());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The nearest such structure to {@code origin} within {@code radius} chunks, as vanilla's {@code /locate} finds
     * it, and as costly: called by the waypoints' background search, off the server thread. Empty when there is
     * none or the structure is unknown.
     */
    public Optional<BlockPos> locate(ServerLevel level, BlockPos origin, int radius) {
        Optional<? extends HolderSet<Structure>> set = known(level);
        if (set.isEmpty()) {
            return Optional.empty();
        }
        long start = System.nanoTime();
        Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator().findNearestMapStructure(level, set.get(), origin, radius, false);
        Storybound.LOGGER.debug("Storybound: searched {} ({} structures) within {} chunks of {} in {} ms: {}", this, set.get().size(), radius, origin,
                (System.nanoTime() - start) / 1_000_000, found == null ? "none" : found.getFirst());
        return found == null ? Optional.empty() : Optional.of(found.getFirst());
    }

    /** The structures, or empty for an unknown id or tag, which is logged once. */
    private Optional<? extends HolderSet<Structure>> known(ServerLevel level) {
        Optional<? extends HolderSet<Structure>> set = holders(level);
        if (set.isEmpty() && REPORTED.add(this)) {
            Storybound.LOGGER.warn("Storybound: unknown structure {}", this);
        }
        return set;
    }

    private Optional<? extends HolderSet<Structure>> holders(ServerLevel level) {
        return holders(level.registryAccess());
    }

    /** The structures, or empty for an unknown id or tag (not logged). */
    public Optional<? extends HolderSet<Structure>> holders(RegistryAccess access) {
        Registry<Structure> registry = access.registryOrThrow(Registries.STRUCTURE);
        if (cachedRegistry != registry) {
            synchronized (HOLDER_CACHE) {
                if (cachedRegistry != registry) {
                    HOLDER_CACHE.clear();
                    cachedRegistry = registry;
                }
            }
        }
        Optional<? extends HolderSet<Structure>> cached = HOLDER_CACHE.get(this);
        if (cached != null) {
            return cached;
        }
        Optional<? extends HolderSet<Structure>> resolved = tag
                ? registry.getTag(TagKey.create(Registries.STRUCTURE, id))
                : registry.getHolder(ResourceKey.create(Registries.STRUCTURE, id)).map(HolderSet::direct);
        // Only a found one is kept: a tag asked for before the tags are bound is looked up again later.
        if (resolved.isPresent()) {
            HOLDER_CACHE.put(this, resolved);
        }
        return resolved;
    }

    /**
     * Where a marker for this structure found at {@code at} stands: the middle of the structure itself once its chunk
     * is loaded (an underground stronghold underground, a fortress in the Nether's middle). Until then, and for a
     * structure whose start is not there: the ground, else the sea level in a dimension with a roof (whose top block is
     * the roof).
     */
    public net.minecraft.world.phys.Vec3 markerPos(ServerLevel level, BlockPos at) {
        if (level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4)) {
            net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunk(at.getX() >> 4, at.getZ() >> 4);
            Optional<? extends HolderSet<Structure>> set = holders(level);
            if (set.isPresent()) {
                for (Holder<Structure> structure : set.get()) {
                    net.minecraft.world.level.levelgen.structure.StructureStart start = chunk.getStartForStructure(structure.value());
                    if (start == null || !start.isValid()) {
                        // Not its start chunk (a place already at the middle): the structure covering that place.
                        start = level.structureManager().getStructureAt(at, structure.value());
                    }
                    if (start != null && start.isValid()) {
                        net.minecraft.world.level.levelgen.structure.BoundingBox box = start.getBoundingBox();
                        return new net.minecraft.world.phys.Vec3((box.minX() + box.maxX() + 1) / 2.0, (box.minY() + box.maxY()) / 2.0,
                                (box.minZ() + box.maxZ() + 1) / 2.0);
                    }
                }
            }
        }
        return new net.minecraft.world.phys.Vec3(at.getX() + 0.5, groundY(level, at), at.getZ() + 0.5);
    }

    /**
     * The ground at a structure's position when its chunk is loaded (no chunk is loaded for this), else sea level; in a
     * dimension with a roof, the sea level.
     */
    public static double groundY(ServerLevel level, BlockPos pos) {
        if (level.dimensionType().hasCeiling()) {
            // Its top block is the roof (the Nether's bedrock): the sea level is nearer where things are.
            return level.getChunkSource().getGenerator().getSeaLevel();
        }
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())
                : level.getChunkSource().getGenerator().getSeaLevel();
    }

    @Override
    public String toString() {
        return (tag ? "#" : "") + id;
    }
}
