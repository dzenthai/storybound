package dev.dzenthai.storybound.module.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * How a search area (a waypoint with a {@code radius}) shows: {@code show_radius} writes "radius 24 m" by its name,
 * {@code show_ring} draws its edge on the ground. Both on by default; off when the edge would give away too much
 * ("the cat is somewhere here"). Nothing for a point (radius 0).
 */
public record AreaLook(boolean showRadius, boolean showRing) {
    public static final AreaLook DEFAULT = new AreaLook(true, true);
    public static final MapCodec<AreaLook> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("show_radius", true).forGetter(AreaLook::showRadius),
            Codec.BOOL.optionalFieldOf("show_ring", true).forGetter(AreaLook::showRing)
    ).apply(instance, AreaLook::new));
}
