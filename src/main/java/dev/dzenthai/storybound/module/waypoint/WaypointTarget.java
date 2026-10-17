package dev.dzenthai.storybound.module.waypoint;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.StructureRef;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where a waypoint points: {@code { "pos": [x, y, z], "dimension": "minecraft:overworld" }}, {@code { "entity": "<uuid>" }},
 * {@code { "structure": "#minecraft:village" }} or {@code { "nearest": "minecraft:zombie" }}.
 */
public sealed interface WaypointTarget {
    Codec<WaypointTarget> CODEC = Codec.either(Fixed.CODEC, Codec.either(Follow.CODEC, Codec.either(Structure.CODEC, Nearest.CODEC))).xmap(
            either -> either.map(fixed -> (WaypointTarget) fixed, rest -> rest.map(follow -> (WaypointTarget) follow,
                    last -> last.map(structure -> (WaypointTarget) structure, nearest -> nearest))),
            target -> switch (target) {
                case Fixed fixed -> Either.left(fixed);
                case Follow follow -> Either.right(Either.left(follow));
                case Structure structure -> Either.right(Either.right(Either.left(structure)));
                case Nearest nearest -> Either.right(Either.right(Either.right(nearest)));
            });

    /** @param dimension defaults to the overworld */
    record Fixed(Vec3 pos, ResourceKey<Level> dimension) implements WaypointTarget {
        public static final Codec<Fixed> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Fixed::pos),
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension", Level.OVERWORLD).forGetter(Fixed::dimension)
        ).apply(instance, Fixed::new));
    }

    /** Follows an entity (an NPC) wherever it is loaded; while it is not, its last known position is used. */
    record Follow(UUID entity) implements WaypointTarget {
        public static final Codec<Follow> CODEC = UUIDUtil.STRING_CODEC.fieldOf("entity").codec().xmap(Follow::new, Follow::entity);
    }

    /**
     * The nearest structure of that kind, found by a background search (see {@link StructureLocator}).
     *
     * @param dimension    where to search; the overworld by default
     * @param searchRadius in chunks
     * @param origin       where to search from, once for everyone; without it, near each player, following them as they
     *                     travel (see {@link Waypoints})
     */
    record Structure(StructureRef structure, ResourceKey<Level> dimension, int searchRadius, Optional<BlockPos> origin) implements WaypointTarget {
        public static final Codec<Structure> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StructureRef.CODEC.fieldOf("structure").forGetter(Structure::structure),
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension", Level.OVERWORLD).forGetter(Structure::dimension),
                Codec.intRange(1, 512).optionalFieldOf("search_radius", StructureRef.DEFAULT_SEARCH_RADIUS).forGetter(Structure::searchRadius),
                BlockPos.CODEC.optionalFieldOf("origin").forGetter(Structure::origin)
        ).apply(instance, Structure::new));

        StructureLocator.Search searchFrom(BlockPos from) {
            return new StructureLocator.Search(structure, dimension, from, searchRadius);
        }
    }

    /**
     * The nearest living entity that matches, within {@code range} blocks of each player who sees the waypoint:
     * {@code { "nearest": "minecraft:zombie" }} (an id, a #tag or a list) or a selector,
     * {@code { "nearest": { "type": "minecraft:wolf", "tag": "alpha" }, "range": 128 }}. Only loaded entities count,
     * players never; nothing shows while none is near. See {@link Waypoints} for how a pick is kept.
     */
    record Nearest(EntitySelector entity, int range) implements WaypointTarget {
        public static final int DEFAULT_RANGE = 64;
        private static final Codec<EntitySelector> SELECTOR = Codec.either(EntitySelector.CODEC, RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE))
                .xmap(either -> either.map(selector -> selector,
                        types -> new EntitySelector(Optional.of(types), Optional.empty(), Optional.empty(), Optional.empty())), Either::left);
        public static final Codec<Nearest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                SELECTOR.fieldOf("nearest").forGetter(Nearest::entity),
                Codec.intRange(1, 256).optionalFieldOf("range", DEFAULT_RANGE).forGetter(Nearest::range)
        ).apply(instance, Nearest::new));
    }
}
