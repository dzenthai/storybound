package dev.dzenthai.storybound.module.waypoint;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Where a waypoint points: {@code { "pos": [x, y, z], "dimension": "minecraft:overworld" }} or {@code { "entity": "<uuid>" }}. */
public sealed interface WaypointTarget {
    Codec<WaypointTarget> CODEC = Codec.either(Fixed.CODEC, Follow.CODEC).xmap(
            either -> either.map(fixed -> (WaypointTarget) fixed, follow -> follow),
            target -> target instanceof Fixed fixed ? Either.left(fixed) : Either.right((Follow) target));

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
}
