package dev.dzenthai.storybound.core.logic;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.dzenthai.storybound.Storybound;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

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
        Optional<? extends HolderSet<Structure>> set = known(level);
        if (set.isEmpty() || !level.isLoaded(pos)) {
            return false;
        }
        StructureManager manager = level.structureManager();
        Map<Structure, LongSet> here = manager.getAllStructuresAt(pos);
        if (here.isEmpty()) {
            return false;
        }
        for (Holder<Structure> holder : set.get()) {
            if (here.containsKey(holder.value()) && manager.getStructureAt(pos, holder.value()).isValid()) {
                return true;
            }
        }
        return false;
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
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        return tag ? registry.getTag(TagKey.create(Registries.STRUCTURE, id))
                : registry.getHolder(ResourceKey.create(Registries.STRUCTURE, id)).map(HolderSet::direct);
    }

    /** The ground at a structure's position when its chunk is loaded (no chunk is loaded for this), else sea level. */
    public static double groundY(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())
                : level.getChunkSource().getGenerator().getSeaLevel();
    }

    @Override
    public String toString() {
        return (tag ? "#" : "") + id;
    }
}
