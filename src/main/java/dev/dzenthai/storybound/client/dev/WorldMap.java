package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.core.logic.WorldPlaces;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.Nullable;

/**
 * The world's map as the server sent it with the snapshot ({@link WorldPlaces#forEditor}; November 9, 2026): which
 * biomes each dimension makes and in which biomes each structure stands, so the editor lists only what can be (the
 * Nether's biomes after "Nether", its structures after a crimson forest). Nothing of vanilla is known here: a server
 * that sent no map (an older one) leaves every list whole.
 */
final class WorldMap {
    private WorldMap() {}

    /** Every dimension of the world; empty when no map came. */
    static List<String> dimensions() {
        return ClientDevStore.serverList(WorldPlaces.DIMENSIONS);
    }

    static boolean known() {
        return !dimensions().isEmpty();
    }

    /** The biomes a dimension makes; null when it is not known (no map, an unknown dimension): then any. */
    static @Nullable Set<String> biomesIn(@Nullable String dimension) {
        if (dimension == null || !known() || !dimensions().contains(dimension)) {
            return null;
        }
        return new HashSet<>(ClientDevStore.serverList(WorldPlaces.BIOMES_OF + dimension));
    }

    /** Every structure the server knows, by id. */
    static List<String> structures() {
        return List.copyOf(structureBiomes().keySet());
    }

    /**
     * The biomes a biome entry stands for: an id itself, a #tag its members (biome tags are on the client); empty for a
     * tag the client does not know.
     */
    static Set<String> biomesOf(String entry) {
        Set<String> biomes = new HashSet<>();
        if (!entry.startsWith("#")) {
            biomes.add(entry);
            return biomes;
        }
        ResourceLocation tag = ResourceLocation.tryParse(entry.substring(1));
        var level = Minecraft.getInstance().level;
        if (tag != null && level != null) {
            level.registryAccess().registry(Registries.BIOME).flatMap(registry -> registry.getTag(TagKey.create(Registries.BIOME, tag)))
                    .ifPresent(set -> set.forEach(holder -> holder.unwrapKey().ifPresent(key -> biomes.add(key.location().toString()))));
        }
        return biomes;
    }

    /** The biomes a structure (or any of a #tag of them) stands in; null when not known: then anywhere. */
    static @Nullable Set<String> structureIn(String structure) {
        Map<String, String> all = structureBiomes();
        if (all.isEmpty()) {
            return null;
        }
        if (structure.startsWith("#")) {
            List<String> members = structureTags().get(structure);
            if (members == null) {
                return null;
            }
            Set<String> biomes = new HashSet<>();
            members.forEach(member -> biomes.addAll(specBiomes(all.get(member))));
            return biomes;
        }
        String spec = all.get(structure);
        return spec == null ? null : specBiomes(spec);
    }

    /** Every structure #tag the server knows, with its members. */
    static Map<String, List<String>> structureTags() {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        for (String line : ClientDevStore.serverList(WorldPlaces.STRUCTURE_TAGS)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                String members = line.substring(eq + 1);
                tags.put(line.substring(0, eq), members.isEmpty() ? List.of() : List.of(members.split(",")));
            }
        }
        return tags;
    }

    /**
     * Whether a structure can stand in the dimension and in any of the biome entries (each part optional: null or empty
     * for any); true when the map does not say.
     */
    static boolean structureFits(String structure, @Nullable String dimension, @Nullable Collection<String> biomeEntries) {
        Set<String> its = structureIn(structure);
        if (its == null) {
            return true;
        }
        Set<String> here = biomesIn(dimension);
        if (here != null && its.stream().noneMatch(here::contains)) {
            return false;
        }
        if (biomeEntries != null && !biomeEntries.isEmpty()) {
            Set<String> chosen = new HashSet<>();
            biomeEntries.forEach(entry -> chosen.addAll(biomesOf(entry)));
            return chosen.isEmpty() || its.stream().anyMatch(chosen::contains);
        }
        return true;
    }

    /** Whether a biome entry (an id or a #tag) can be in the dimension: a #tag when any of its biomes can; true when not known. */
    static boolean biomeFits(String entry, @Nullable String dimension) {
        Set<String> here = biomesIn(dimension);
        if (here == null) {
            return true;
        }
        Set<String> biomes = biomesOf(entry);
        return biomes.isEmpty() || biomes.stream().anyMatch(here::contains);
    }

    private static Set<String> specBiomes(@Nullable String spec) {
        if (spec == null || spec.isEmpty()) {
            return new HashSet<>();
        }
        if (spec.startsWith("#")) {
            return biomesOf(spec);
        }
        return new HashSet<>(List.of(spec.split(",")));
    }

    private static Map<String, String> structureBiomes() {
        Map<String, String> all = new LinkedHashMap<>();
        for (String line : ClientDevStore.serverList(WorldPlaces.STRUCTURES)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                all.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return all;
    }
}
