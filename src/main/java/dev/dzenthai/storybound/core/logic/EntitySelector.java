package dev.dzenthai.storybound.core.logic;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

/**
 * Picks particular entities, not just a type: {@code { "type": "minecraft:wolf", "tag": "white_wolf" }} or
 * {@code { "type": "minecraft:villager", "dimension": "minecraft:overworld", "data": { "VillagerData/profession":
 * "minecraft:weaponsmith" } }}. Every part that is given must match; at least one part saying which entities (not only
 * where they stand) is required. Several variants of a mob (a man smith, a woman farmer) are a list of selectors where
 * one is taken. Used by NPC bindings, bosses, waypoints' nearest entity and the "observed" trigger, always on the server.
 * <p>
 * Where it is ({@code dimension}, {@code biome}, {@code structure}) is the base every mob has ({@link WorldPlaces});
 * what it is like is read from its own saved data ({@code data}), nothing known of any kind or mod (November 9, 2026).
 *
 * @param type       an entity type, a #tag or a list
 * @param tag        a scoreboard tag
 * @param name       the exact custom name
 * @param uuid       one entity, e.g. an Easy NPC
 * @param profession older files: a villager profession or a list; only entities with villager data match (the form
 *                   writes {@code data} now)
 * @param outfit     older files: a villager type or a list; only entities with villager data match
 * @param baby       a baby or not; an entity that cannot be a baby is not one
 * @param biome      the biome the entity stands in now: an id, a #tag or a list
 * @param inVillage  older files: in a village or not, by vanilla's notion (claimed beds, workstations and bells)
 * @param variant    older files: its variant by the game's common way of them ({@link #variantOf})
 * @param nbt        what its saved data must hold, as vanilla's {@code @e[nbt=...]}, written by hand
 * @param structure  within one of these structures where it stands now
 * @param dimension  the dimension it is in now
 * @param data       fields of its saved data and the values they may hold ({@link DataFilter})
 */
public record EntitySelector(Optional<HolderSet<EntityType<?>>> type, Optional<String> tag, Optional<String> name, Optional<UUID> uuid,
                             Optional<HolderSet<VillagerProfession>> profession, Optional<HolderSet<VillagerType>> outfit,
                             Optional<Boolean> baby, Optional<HolderSet<Biome>> biome, Optional<Boolean> inVillage,
                             Optional<List<String>> variant, Optional<CompoundTag> nbt, Optional<List<StructureRef>> structure,
                             Optional<ResourceKey<Level>> dimension, Optional<DataFilter> data) {
    /** One string or a list of them, written back as one when it is one. */
    static final Codec<List<String>> STRINGS = Codec.either(Codec.STRING, Codec.STRING.listOf())
            .xmap(either -> either.map(List::of, Function.identity()), list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list));

    public static final Codec<EntitySelector> CODEC = RecordCodecBuilder.<EntitySelector>create(instance -> instance.group(
            RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).optionalFieldOf("type").forGetter(EntitySelector::type),
            Codec.STRING.optionalFieldOf("tag").forGetter(EntitySelector::tag),
            Codec.STRING.optionalFieldOf("name").forGetter(EntitySelector::name),
            UUIDUtil.STRING_CODEC.optionalFieldOf("uuid").forGetter(EntitySelector::uuid),
            RegistryCodecs.homogeneousList(Registries.VILLAGER_PROFESSION).optionalFieldOf("profession").forGetter(EntitySelector::profession),
            RegistryCodecs.homogeneousList(Registries.VILLAGER_TYPE).optionalFieldOf("outfit").forGetter(EntitySelector::outfit),
            Codec.BOOL.optionalFieldOf("baby").forGetter(EntitySelector::baby),
            RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("biome").forGetter(EntitySelector::biome),
            Codec.BOOL.optionalFieldOf("in_village").forGetter(EntitySelector::inVillage),
            STRINGS.optionalFieldOf("variant").forGetter(EntitySelector::variant),
            TagParser.AS_CODEC.optionalFieldOf("nbt").forGetter(EntitySelector::nbt),
            // One structure or a list, as types are written.
            Codec.either(StructureRef.CODEC, StructureRef.CODEC.listOf()).xmap(either -> either.map(List::of, Function.identity()),
                    list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list)).optionalFieldOf("structure").forGetter(EntitySelector::structure),
            Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(EntitySelector::dimension),
            DataFilter.CODEC.optionalFieldOf("data").forGetter(EntitySelector::data)
    ).apply(instance, EntitySelector::new)).validate(selector ->
            selector.type().isPresent() || selector.tag().isPresent() || selector.name().isPresent() || selector.uuid().isPresent()
                    || selector.profession().isPresent() || selector.outfit().isPresent()
                    ? DataResult.success(selector)
                    // Where it stands or its data alone would take every entity there: items, arrows, players.
                    : DataResult.error(() -> "An entity selector needs 'type', 'tag', 'name', 'uuid', 'profession' or 'outfit'"));

    public static EntitySelector of(UUID uuid) {
        return new EntitySelector(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(uuid), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** A selector of only entity types (an id, a #tag or a list). */
    public static EntitySelector ofTypes(HolderSet<EntityType<?>> types) {
        return new EntitySelector(Optional.of(types), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Checked cheapest first: what the entity is, where it is, then its saved data, read once for both. */
    public boolean matches(Entity entity) {
        return type.map(set -> set.contains(entity.getType().builtInRegistryHolder())).orElse(true)
                && dimension.map(entity.level().dimension()::equals).orElse(true)
                && tag.map(entity.getTags()::contains).orElse(true)
                && name.map(value -> value.equals(customName(entity))).orElse(true)
                && uuid.map(entity.getUUID()::equals).orElse(true)
                && baby.map(value -> value == isBaby(entity)).orElse(true)
                && profession.map(set -> villagerData(entity) instanceof VillagerData data && set.contains(professionHolder(data))).orElse(true)
                && outfit.map(set -> villagerData(entity) instanceof VillagerData data && set.contains(outfitHolder(data))).orElse(true)
                && variant.map(values -> variantOf(entity) instanceof String has && values.contains(has)).orElse(true)
                && biome.map(set -> set.contains(biomeHere(entity))).orElse(true)
                && inVillage.map(value -> value == isInVillage(entity)).orElse(true)
                && structure.map(structures -> isIn(entity, structures)).orElse(true)
                && (nbt.isEmpty() && data.isEmpty() || savedMatches(dataOf(entity)));
    }

    private boolean savedMatches(CompoundTag saved) {
        return nbt.map(wanted -> NbtUtils.compareNbt(wanted, saved, true)).orElse(true)
                && data.map(filter -> filter.matches(saved)).orElse(true);
    }

    /** Where it is, as written: for the validator, which says when it can never be. */
    public WorldPlaces.Place place() {
        return new WorldPlaces.Place(dimension, biome, structure);
    }

    /**
     * What keeps the entity from matching, for the trace: {@code "tag quest_man missing (has npc_4); custom name is none,
     * wanted Quest Man"}; empty when it matches.
     */
    public String mismatch(Entity entity) {
        List<String> parts = new ArrayList<>();
        if (type.isPresent() && !type.get().contains(entity.getType().builtInRegistryHolder())) {
            parts.add("type is " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        }
        if (dimension.isPresent() && !dimension.get().equals(entity.level().dimension())) {
            parts.add("in " + entity.level().dimension().location());
        }
        if (tag.isPresent() && !entity.getTags().contains(tag.get())) {
            parts.add("tag " + tag.get() + " missing (has " + (entity.getTags().isEmpty() ? "none" : String.join(", ", entity.getTags())) + ")");
        }
        String customName = customName(entity);
        if (name.isPresent() && !name.get().equals(customName)) {
            parts.add("custom name is " + (customName == null ? "none" : customName) + ", wanted " + name.get());
        }
        if (uuid.isPresent() && !uuid.get().equals(entity.getUUID())) {
            parts.add("uuid is " + entity.getUUID());
        }
        if (baby.isPresent() && baby.get() != isBaby(entity)) {
            parts.add(baby.get() ? "not a baby" : "a baby");
        }
        VillagerData villager = villagerData(entity);
        if (profession.isPresent() && (villager == null || !profession.get().contains(professionHolder(villager)))) {
            parts.add(villager == null ? "no profession (not a villager)" : "profession is " + BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getProfession()));
        }
        if (outfit.isPresent() && (villager == null || !outfit.get().contains(outfitHolder(villager)))) {
            parts.add(villager == null ? "no outfit (not a villager)" : "outfit is " + BuiltInRegistries.VILLAGER_TYPE.getKey(villager.getType()));
        }
        if (variant.isPresent() && !(variantOf(entity) instanceof String own && variant.get().contains(own))) {
            String has = variantOf(entity);
            parts.add(has == null ? "no variant" : "variant is " + has);
        }
        if (biome.isPresent()) {
            Holder<Biome> here = biomeHere(entity);
            if (!biome.get().contains(here)) {
                parts.add("biome is " + here.unwrapKey().map(key -> key.location().toString()).orElse("unknown"));
            }
        }
        if (inVillage.isPresent() && inVillage.get() != isInVillage(entity)) {
            parts.add(inVillage.get() ? "not in a village" : "in a village");
        }
        if (structure.isPresent() && !isIn(entity, structure.get())) {
            parts.add("not in " + structure.get().stream().map(StructureRef::toString).collect(java.util.stream.Collectors.joining(" or ")));
        }
        if (nbt.isPresent() || data.isPresent()) {
            CompoundTag saved = dataOf(entity);
            if (nbt.isPresent() && !NbtUtils.compareNbt(nbt.get(), saved, true)) {
                parts.add("its data do not hold " + nbt.get());
            }
            data.ifPresent(filter -> filter.mismatch(saved, parts));
        }
        return String.join("; ", parts);
    }

    /** How many filters it has (where it is, what it is like): of two NPCs by kind, more is closer. */
    public int filters() {
        return count(profession) + count(outfit) + count(baby) + count(variant) + count(nbt) + count(biome) + count(inVillage)
                + count(structure) + count(dimension) + data.map(DataFilter::count).orElse(0);
    }

    private static int count(Optional<?> part) {
        return part.isPresent() ? 1 : 0;
    }

    /** The entity type when the selector names exactly one, e.g. to draw a portrait; empty for tags and lists. */
    public Optional<EntityType<?>> singleType() {
        return type.filter(set -> set.size() == 1 && set.unwrapKey().isEmpty()).map(set -> set.get(0).value());
    }

    private static String customName(Entity entity) {
        return Optional.ofNullable(entity.getCustomName()).map(Component::getString).orElse(null);
    }

    private static boolean isBaby(Entity entity) {
        return entity instanceof LivingEntity living && living.isBaby();
    }

    private static @Nullable VillagerData villagerData(Entity entity) {
        return entity instanceof VillagerDataHolder holder ? holder.getVillagerData() : null;
    }

    private static Holder<VillagerProfession> professionHolder(VillagerData data) {
        return BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(data.getProfession());
    }

    private static Holder<VillagerType> outfitHolder(VillagerData data) {
        return BuiltInRegistries.VILLAGER_TYPE.wrapAsHolder(data.getType());
    }

    private static Holder<Biome> biomeHere(Entity entity) {
        return entity.level().getBiome(entity.blockPosition());
    }

    /**
     * The entity's variant by the game's common way of them ({@code VariantHolder}), as written: a registry id
     * ("minecraft:snowy") or a name ("red"); null for one without. Older files' {@code variant}.
     */
    public static @Nullable String variantOf(Entity entity) {
        return entity instanceof net.minecraft.world.entity.VariantHolder<?> holder && holder.getVariant() != null ? variantName(holder.getVariant()) : null;
    }

    /** A variant value written: a registry entry's id, a named one's name, else its text in lower case. */
    public static String variantName(Object variant) {
        if (variant instanceof Holder<?> holder) {
            return holder.unwrapKey().map(key -> key.location().toString()).orElse(String.valueOf(holder.value()));
        }
        if (variant instanceof net.minecraft.util.StringRepresentable named) {
            return named.getSerializedName();
        }
        return variant.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** An entity's saved data, read once for every selector asked within {@link #sharingSavedData}. */
    private record Saved(WeakReference<Entity> entity, CompoundTag data) {}

    private static final ThreadLocal<Saved[]> SHARED = new ThreadLocal<>();

    /**
     * Runs the work with one reading of an entity's saved data for every selector asking it (November 9, 2026): an
     * NPC's chain asks each binding, and saving a villager is the dearest part of a check. Outside it, each match reads
     * anew, so a story changing a mob and asking about it at once is answered rightly.
     */
    public static <T> T sharingSavedData(java.util.function.Supplier<T> work) {
        if (SHARED.get() != null) {
            return work.get();
        }
        SHARED.set(new Saved[1]);
        try {
            return work.get();
        } finally {
            SHARED.remove();
        }
    }

    /** Everything the entity saves, as @e[nbt=...] compares it; empty when saving it fails (a mod's mob gone wrong). */
    static CompoundTag dataOf(Entity entity) {
        Saved[] shared = SHARED.get();
        if (shared != null && shared[0] != null && shared[0].entity().get() == entity) {
            return shared[0].data();
        }
        CompoundTag read;
        try {
            read = entity.saveWithoutId(new CompoundTag());
        } catch (RuntimeException e) {
            read = new CompoundTag();
        }
        if (shared != null) {
            shared[0] = new Saved(new WeakReference<>(entity), read);
        }
        return read;
    }

    /** Within one of the structures (a village, a fortress, a mod's), where it stands now. */
    private static boolean isIn(Entity entity, List<StructureRef> structures) {
        return entity.level() instanceof ServerLevel level && structures.stream().anyMatch(ref -> ref.contains(level, entity.blockPosition()));
    }

    private static boolean isInVillage(Entity entity) {
        return entity.level() instanceof ServerLevel level && level.isVillage(entity.blockPosition());
    }
}
