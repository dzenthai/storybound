package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Picks particular entities, not just a type: {@code { "type": "minecraft:wolf", "tag": "white_wolf" }}.
 * Every part that is given must match; at least one is required. Used by NPC bindings and bosses.
 *
 * @param type an entity type, a #tag or a list
 * @param tag  a scoreboard tag
 * @param name the exact custom name
 * @param uuid one entity, e.g. an Easy NPC
 */
public record EntitySelector(Optional<HolderSet<EntityType<?>>> type, Optional<String> tag, Optional<String> name, Optional<UUID> uuid) {
    public static final Codec<EntitySelector> CODEC = RecordCodecBuilder.<EntitySelector>create(instance -> instance.group(
            RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).optionalFieldOf("type").forGetter(EntitySelector::type),
            Codec.STRING.optionalFieldOf("tag").forGetter(EntitySelector::tag),
            Codec.STRING.optionalFieldOf("name").forGetter(EntitySelector::name),
            UUIDUtil.STRING_CODEC.optionalFieldOf("uuid").forGetter(EntitySelector::uuid)
    ).apply(instance, EntitySelector::new)).validate(selector ->
            selector.type().isPresent() || selector.tag().isPresent() || selector.name().isPresent() || selector.uuid().isPresent()
                    ? DataResult.success(selector)
                    : DataResult.error(() -> "An entity selector needs 'type', 'tag', 'name' or 'uuid'"));

    public static EntitySelector of(UUID uuid) {
        return new EntitySelector(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(uuid));
    }

    public boolean matches(Entity entity) {
        return type.map(set -> set.contains(entity.getType().builtInRegistryHolder())).orElse(true)
                && tag.map(entity.getTags()::contains).orElse(true)
                && name.map(value -> value.equals(Optional.ofNullable(entity.getCustomName()).map(Component::getString).orElse(null))).orElse(true)
                && uuid.map(entity.getUUID()::equals).orElse(true);
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
        String customName = Optional.ofNullable(entity.getCustomName()).map(Component::getString).orElse(null);
        if (name.isPresent() && !name.get().equals(customName)) {
            parts.add("custom name is " + (customName == null ? "none" : customName) + ", wanted " + name.get());
        }
        if (uuid.isPresent() && !uuid.get().equals(entity.getUUID())) {
            parts.add("uuid is " + entity.getUUID());
        }
        return String.join("; ", parts);
    }

    /** The entity type when the selector names exactly one, e.g. to draw a portrait; empty for tags and lists. */
    public Optional<EntityType<?>> singleType() {
        return type.filter(set -> set.size() == 1 && set.unwrapKey().isEmpty()).map(set -> set.get(0).value());
    }
}
