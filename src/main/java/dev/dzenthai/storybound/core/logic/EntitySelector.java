package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.biome.Biome;

/**
 * Picks particular entities, not just a type: {@code { "type": "minecraft:wolf", "tag": "white_wolf" }} or
 * {@code { "type": "minecraft:villager", "profession": "minecraft:weaponsmith", "in_village": true }}. Every part that is
 * given must match; at least one part saying which entities (not only where they stand) is required. Used by NPC
 * bindings, bosses, waypoints' nearest entity and the "observed" trigger, always on the server.
 *
 * @param type       an entity type, a #tag or a list
 * @param tag        a scoreboard tag
 * @param name       the exact custom name
 * @param uuid       one entity, e.g. an Easy NPC
 * @param profession a villager profession or a list; only entities with villager data (villagers, zombie villagers) match
 * @param outfit     a villager type (plains, desert…: the biome it was born in, seen on its clothes) or a list; only
 *                   entities with villager data match
 * @param baby       a baby or not; an entity that cannot be a baby is not one
 * @param biome      the biome the entity stands in now: an id, a #tag or a list
 * @param inVillage  in a village or not, by vanilla's own notion (the one raids use: claimed beds, workstations and
 *                   bells within a section), so generated, modded and player-built villages all count
 */
public record EntitySelector(Optional<HolderSet<EntityType<?>>> type, Optional<String> tag, Optional<String> name, Optional<UUID> uuid,
                             Optional<HolderSet<VillagerProfession>> profession, Optional<HolderSet<VillagerType>> outfit,
                             Optional<Boolean> baby, Optional<HolderSet<Biome>> biome, Optional<Boolean> inVillage) {
    public static final Codec<EntitySelector> CODEC = RecordCodecBuilder.<EntitySelector>create(instance -> instance.group(
            RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).optionalFieldOf("type").forGetter(EntitySelector::type),
            Codec.STRING.optionalFieldOf("tag").forGetter(EntitySelector::tag),
            Codec.STRING.optionalFieldOf("name").forGetter(EntitySelector::name),
            UUIDUtil.STRING_CODEC.optionalFieldOf("uuid").forGetter(EntitySelector::uuid),
            RegistryCodecs.homogeneousList(Registries.VILLAGER_PROFESSION).optionalFieldOf("profession").forGetter(EntitySelector::profession),
            RegistryCodecs.homogeneousList(Registries.VILLAGER_TYPE).optionalFieldOf("outfit").forGetter(EntitySelector::outfit),
            Codec.BOOL.optionalFieldOf("baby").forGetter(EntitySelector::baby),
            RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("biome").forGetter(EntitySelector::biome),
            Codec.BOOL.optionalFieldOf("in_village").forGetter(EntitySelector::inVillage)
    ).apply(instance, EntitySelector::new)).validate(selector ->
            selector.type().isPresent() || selector.tag().isPresent() || selector.name().isPresent() || selector.uuid().isPresent()
                    || selector.profession().isPresent() || selector.outfit().isPresent()
                    ? DataResult.success(selector)
                    // "baby", "biome" and "in_village" alone would take every entity there: items, arrows, players.
                    : DataResult.error(() -> "An entity selector needs 'type', 'tag', 'name', 'uuid', 'profession' or 'outfit'"));

    public static EntitySelector of(UUID uuid) {
        return new EntitySelector(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(uuid),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** A selector of only entity types (an id, a #tag or a list). */
    public static EntitySelector ofTypes(HolderSet<EntityType<?>> types) {
        return new EntitySelector(Optional.of(types), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Checked cheapest first: what the entity is, then where it stands (a biome lookup, then the village's points). */
    public boolean matches(Entity entity) {
        return type.map(set -> set.contains(entity.getType().builtInRegistryHolder())).orElse(true)
                && tag.map(entity.getTags()::contains).orElse(true)
                && name.map(value -> value.equals(customName(entity))).orElse(true)
                && uuid.map(entity.getUUID()::equals).orElse(true)
                && baby.map(value -> value == isBaby(entity)).orElse(true)
                && profession.map(set -> villagerData(entity) instanceof VillagerData data && set.contains(professionHolder(data))).orElse(true)
                && outfit.map(set -> villagerData(entity) instanceof VillagerData data && set.contains(outfitHolder(data))).orElse(true)
                && biome.map(set -> set.contains(biomeHere(entity))).orElse(true)
                && inVillage.map(value -> value == isInVillage(entity)).orElse(true);
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
        VillagerData data = villagerData(entity);
        if (profession.isPresent() && (data == null || !profession.get().contains(professionHolder(data)))) {
            parts.add(data == null ? "no profession (not a villager)" : "profession is " + BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()));
        }
        if (outfit.isPresent() && (data == null || !outfit.get().contains(outfitHolder(data)))) {
            parts.add(data == null ? "no outfit (not a villager)" : "outfit is " + BuiltInRegistries.VILLAGER_TYPE.getKey(data.getType()));
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
        return String.join("; ", parts);
    }

    /** How many filters it has (profession, outfit, baby, biome, in_village): of two NPCs by kind, more is closer. */
    public int filters() {
        return (profession.isPresent() ? 1 : 0) + (outfit.isPresent() ? 1 : 0) + (baby.isPresent() ? 1 : 0)
                + (biome.isPresent() ? 1 : 0) + (inVillage.isPresent() ? 1 : 0);
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

    private static VillagerData villagerData(Entity entity) {
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

    private static boolean isInVillage(Entity entity) {
        return entity.level() instanceof ServerLevel level && level.isVillage(entity.blockPosition());
    }
}
