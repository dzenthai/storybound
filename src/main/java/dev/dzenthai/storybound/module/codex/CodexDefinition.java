package dev.dzenthai.storybound.module.codex;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.StoryIds;
import dev.dzenthai.storybound.core.logic.Area;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.logic.WorldPlaces;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * {@code data/<ns>/storybound/codex/<path>.json}: an entry made of sections the player uncovers one by one,
 * each by its own {@link Unlock}. Listed in the Codex once any section is open.
 *
 * @param entity    what {@code seen} and {@code killed} refer to: an id, a #tag or a list; also the portrait
 * @param announce  JSON {@code notify}: toasts when the entry appears or a section opens
 * @param showKills JSON {@code show_kills}: the entry counts and shows how many of its {@code entity} the player killed (off for
 *                  characters and peaceful creatures)
 * @param place     what {@code visited} refers to: a structure, biome, point of interest, dimension, or area (server-only)
 * @param image     optional illustration shown in a book frame at the top of the Codex detail page
 */
public record CodexDefinition(ResourceLocation category, Optional<HolderSet<EntityType<?>>> entity, StoryText title,
                              List<Section> sections, boolean announce, boolean showKills,
                              Optional<Place> place, Optional<ResourceLocation> image, Optional<Boolean> photo) {
    public static final Codec<CodexDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StoryIds.CODEC.optionalFieldOf("category", Storybound.id("monsters")).forGetter(CodexDefinition::category),
            RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).optionalFieldOf("entity").forGetter(CodexDefinition::entity),
            StoryText.CODEC.fieldOf("title").forGetter(CodexDefinition::title),
            Section.CODEC.listOf().fieldOf("sections").forGetter(CodexDefinition::sections),
            Codec.BOOL.optionalFieldOf("notify", true).forGetter(CodexDefinition::announce),
            Codec.BOOL.optionalFieldOf("show_kills", true).forGetter(CodexDefinition::showKills),
            Place.CODEC.optionalFieldOf("place").forGetter(CodexDefinition::place),
            ResourceLocation.CODEC.optionalFieldOf("image").forGetter(CodexDefinition::image),
            Codec.BOOL.optionalFieldOf("photo").forGetter(CodexDefinition::photo)
    ).apply(instance, CodexDefinition::new));

    public CodexDefinition(ResourceLocation category, Optional<HolderSet<EntityType<?>>> entity, StoryText title,
                           List<Section> sections, boolean announce, boolean showKills) {
        this(category, entity, title, sections, announce, showKills, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public CodexDefinition(ResourceLocation category, Optional<HolderSet<EntityType<?>>> entity, StoryText title,
                           List<Section> sections, boolean announce, boolean showKills,
                           Optional<Place> place, Optional<ResourceLocation> image) {
        this(category, entity, title, sections, announce, showKills, place, image, Optional.empty());
    }

    public Optional<Section> section(String id) {
        return sections.stream().filter(section -> section.id().equals(id)).findFirst();
    }

    /** The entity type when {@code entity} names exactly one, for the portrait. */
    public Optional<EntityType<?>> singleType() {
        return entity.filter(set -> set.size() == 1 && set.unwrapKey().isEmpty()).map(set -> set.get(0).value());
    }

    /** Whether players can capture their own in-world photo for this entry (true by default for place entries). */
    public boolean canPhotograph() {
        return photo.orElseGet(() -> place.isPresent() && !place.get().isEmpty());
    }

    /** What clients receive: everything except server-only {@code place} filters and how sections open. */
    CodexDefinition clientView() {
        return new CodexDefinition(category, entity, title, sections.stream().map(Section::clientView).toList(),
                announce, showKills, Optional.empty(), image, Optional.of(canPhotograph()));
    }

    /**
     * World location matched by {@code visited} unlocks: structure, biome, point of interest, dimension, or area.
     * All present filters must hold at the same time.
     */
    public record Place(Optional<ResourceKey<Level>> dimension, Optional<HolderSet<Biome>> biome,
                        Optional<List<StructureRef>> structure, Optional<HolderSet<PoiType>> poi,
                        int radius, Optional<Area> area) {
        private static final Codec<List<StructureRef>> STRUCTURES = Codec.either(StructureRef.CODEC, StructureRef.CODEC.listOf())
                .xmap(either -> either.map(List::of, Function.identity()),
                        list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list));

        public static final Codec<Place> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(Place::dimension),
                RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("biome").forGetter(Place::biome),
                STRUCTURES.optionalFieldOf("structure").forGetter(Place::structure),
                RegistryCodecs.homogeneousList(Registries.POINT_OF_INTEREST_TYPE).optionalFieldOf("poi").forGetter(Place::poi),
                Codec.intRange(1, 128).optionalFieldOf("radius", 12).forGetter(Place::radius),
                Area.CODEC.optionalFieldOf("area").forGetter(Place::area)
        ).apply(instance, Place::new));

        public boolean isEmpty() {
            return dimension.isEmpty() && biome.isEmpty() && structure.isEmpty() && poi.isEmpty() && area.isEmpty();
        }

        public WorldPlaces.Place worldPlace() {
            return new WorldPlaces.Place(dimension, biome, structure);
        }

        public boolean matches(ServerPlayer player) {
            if (isEmpty()) {
                return false;
            }
            if (dimension.isPresent() && !player.level().dimension().equals(dimension.get())) {
                return false;
            }
            BlockPos pos = player.blockPosition();
            if (biome.isPresent() && !biome.get().contains(player.level().getBiome(pos))) {
                return false;
            }
            if (structure.isPresent() && structure.get().stream().noneMatch(ref -> ref.contains(player.serverLevel(), pos))) {
                return false;
            }
            if (poi.isPresent() && player.serverLevel().getPoiManager()
                    .getInRange(poi.get()::contains, pos, radius, PoiManager.Occupancy.ANY)
                    .findAny().isEmpty()) {
                return false;
            }
            if (area.isPresent() && !area.get().contains(player)) {
                return false;
            }
            return true;
        }
    }

    /**
     * @param title shown above the section's text
     * @param text  a long text, {@code assets/<ns>/storybound/texts/<lang>/<path>.sbt}; by default
     *              {@code <ns>:codex/<entry path>/<section id>}
     */
    public record Section(String id, Optional<StoryText> title, Optional<ResourceLocation> text, Unlock unlock) {
        public static final Codec<Section> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Section::id),
                StoryText.CODEC.optionalFieldOf("title").forGetter(Section::title),
                ResourceLocation.CODEC.optionalFieldOf("text").forGetter(Section::text),
                Unlock.CODEC.fieldOf("unlock").forGetter(Section::unlock)
        ).apply(instance, Section::new));

        public ResourceLocation textId(ResourceLocation entry) {
            return text.orElseGet(() -> ResourceLocation.fromNamespaceAndPath(entry.getNamespace(), "codex/" + entry.getPath() + "/" + id));
        }

        Section clientView() {
            return new Section(id, title, text, Unlock.HIDDEN);
        }
    }
}
