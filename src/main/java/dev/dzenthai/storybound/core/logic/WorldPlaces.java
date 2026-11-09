package dev.dzenthai.storybound.core.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * The base every mob and every player has, where it is: dimension → biome → structure (November 9, 2026). What fits
 * what is asked of the game itself, never written here: a dimension's biomes are those its biome source makes, a
 * structure's those it lists, mods' dimensions, biomes and structures alike. So the validator can say that the Nether
 * has no plains, and the editor lists only what can be.
 */
public final class WorldPlaces {
    private WorldPlaces() {}

    /**
     * A place as written: each part optional.
     *
     * @param dimension  the dimension
     * @param biome      the biomes, any of them (an id, a #tag or a list)
     * @param structures the structures, any of them
     */
    public record Place(Optional<ResourceKey<Level>> dimension, Optional<HolderSet<Biome>> biome, Optional<List<StructureRef>> structures) {
        public static final Place ANYWHERE = new Place(Optional.empty(), Optional.empty(), Optional.empty());

        public boolean isEmpty() {
            return dimension.isEmpty() && biome.isEmpty() && structures.isEmpty();
        }
    }

    /**
     * What in the place can never be, in words for the validator; empty when it can. A dimension, biome or structure
     * the game does not know is left to the checks that report unknown ids.
     */
    public static List<String> problems(MinecraftServer server, Place place) {
        List<String> problems = new ArrayList<>();
        if (place.isEmpty()) {
            return problems;
        }
        Set<ResourceKey<Biome>> inDimension = null;
        if (place.dimension().isPresent()) {
            ServerLevel level = server.getLevel(place.dimension().get());
            if (level == null) {
                problems.add("dimension " + place.dimension().get().location() + " is unknown");
                return problems;
            }
            inDimension = biomesOf(level);
        }
        Set<ResourceKey<Biome>> chosen = null;
        if (place.biome().isPresent()) {
            HolderSet<Biome> biomes = place.biome().get();
            chosen = keys(biomes);
            if (inDimension != null) {
                Set<ResourceKey<Biome>> here = inDimension;
                String dimension = place.dimension().get().location().toString();
                if (biomes.unwrapKey().isPresent()) {
                    // A #tag: wrong only when none of its biomes is in the dimension.
                    if (chosen.stream().noneMatch(here::contains)) {
                        problems.add("no biome of #" + biomes.unwrapKey().get().location() + " is ever in " + dimension);
                    }
                } else {
                    chosen.stream().filter(key -> !here.contains(key))
                            .forEach(key -> problems.add("biome " + key.location() + " is never in " + dimension));
                }
                chosen.retainAll(here);
            }
        }
        if (place.structures().isPresent()) {
            RegistryAccess access = server.registryAccess();
            for (StructureRef ref : place.structures().get()) {
                Optional<? extends HolderSet<Structure>> known = ref.holders(access);
                if (known.isEmpty()) {
                    continue;
                }
                Set<ResourceKey<Biome>> its = new HashSet<>();
                known.get().forEach(structure -> its.addAll(keys(structure.value().biomes())));
                if (inDimension != null && its.stream().noneMatch(inDimension::contains)) {
                    problems.add("structure " + ref + " is never in " + place.dimension().get().location());
                } else if (chosen != null && !chosen.isEmpty() && its.stream().noneMatch(chosen::contains)) {
                    problems.add("structure " + ref + " is never in biome " + describe(place.biome().get()));
                }
            }
        }
        return problems;
    }

    /** The editor's lists of the world's map (see {@link #forEditor}). */
    public static final String DIMENSIONS = "world:dimensions";
    public static final String BIOMES_OF = "world:biomes:";
    public static final String STRUCTURES = "world:structures";
    public static final String STRUCTURE_TAGS = "world:structure_tags";

    /**
     * The world's map for the editor, which a client cannot work out (its worldgen is not sent): {@value #DIMENSIONS} →
     * every dimension; {@value #BIOMES_OF}{@code <dimension>} → the biomes it makes; {@value #STRUCTURES} → each
     * structure as {@code id=#biome_tag} or {@code id=biome,biome...} (the client resolves a tag itself, biome tags are
     * sent to it). Mods' dimensions, biomes and structures alike.
     */
    public static java.util.Map<String, List<String>> forEditor(MinecraftServer server) {
        java.util.Map<String, List<String>> lists = new java.util.LinkedHashMap<>();
        List<String> dimensions = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String id = level.dimension().location().toString();
            dimensions.add(id);
            lists.put(BIOMES_OF + id, biomesOf(level).stream().map(key -> key.location().toString()).sorted().toList());
        }
        lists.put(DIMENSIONS, dimensions.stream().sorted().toList());
        List<String> structures = new ArrayList<>();
        server.registryAccess().registry(net.minecraft.core.registries.Registries.STRUCTURE).ifPresent(registry -> registry.entrySet().forEach(entry -> {
            HolderSet<Biome> biomes = entry.getValue().biomes();
            String spec = biomes.unwrapKey().map(tag -> "#" + tag.location())
                    .orElseGet(() -> keys(biomes).stream().map(key -> key.location().toString()).sorted().collect(Collectors.joining(",")));
            structures.add(entry.getKey().location() + "=" + spec);
        }));
        lists.put(STRUCTURES, structures.stream().sorted().toList());
        // Structure tags are not sent to clients either: each with its members.
        List<String> tags = new ArrayList<>();
        server.registryAccess().registry(net.minecraft.core.registries.Registries.STRUCTURE).ifPresent(registry -> registry.getTags().forEach(pair ->
                tags.add("#" + pair.getFirst().location() + "=" + pair.getSecond().stream().flatMap(holder -> holder.unwrapKey().stream())
                        .map(key -> key.location().toString()).sorted().collect(Collectors.joining(",")))));
        lists.put(STRUCTURE_TAGS, tags.stream().sorted().toList());
        return lists;
    }

    /** The biomes a dimension makes, from its biome source. */
    public static Set<ResourceKey<Biome>> biomesOf(ServerLevel level) {
        return level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                .flatMap(holder -> holder.unwrapKey().stream()).collect(Collectors.toSet());
    }

    /** The keys of a set of biomes, in a set of its own (to change). */
    public static Set<ResourceKey<Biome>> keys(HolderSet<Biome> biomes) {
        Set<ResourceKey<Biome>> keys = new HashSet<>();
        for (Holder<Biome> biome : biomes) {
            biome.unwrapKey().ifPresent(keys::add);
        }
        return keys;
    }

    private static String describe(HolderSet<Biome> biomes) {
        return biomes.unwrapKey().map(tag -> "#" + tag.location())
                .orElseGet(() -> keys(biomes).stream().map(key -> key.location().toString()).sorted().collect(Collectors.joining(", ")));
    }
}
