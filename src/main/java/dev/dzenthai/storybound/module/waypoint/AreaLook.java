package dev.dzenthai.storybound.module.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

/**
 * How a waypoint's marker shows. {@code marker}: a point's icon, {@code event}, three animated "!" for something
 * happening there, or {@code question}, an animated "?" for something unknown there; a trail's, its tracks (the
 * usual one) or {@code scent}, wisps drifting along it. For a search area (a waypoint with a {@code radius}): {@code show_radius} writes "radius 24 m" by its
 * name, {@code show_ring} draws its edge on the ground. Both on by default; off when the edge would give away too much
 * ("the cat is somewhere here"); they do nothing for a point (radius 0).
 */
public record AreaLook(boolean showRadius, boolean showRing, Marker marker) {
    public static final AreaLook DEFAULT = new AreaLook(true, true, Marker.POINT);
    public static final MapCodec<AreaLook> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("show_radius", true).forGetter(AreaLook::showRadius),
            Codec.BOOL.optionalFieldOf("show_ring", true).forGetter(AreaLook::showRing),
            Marker.CODEC.optionalFieldOf("marker", Marker.POINT).forGetter(AreaLook::marker)
    ).apply(instance, AreaLook::new));

    /** Whether the search area's switches are not their defaults. */
    public boolean areaChanged() {
        return !showRadius || !showRing;
    }

    /** What the marker in the world shows. */
    public enum Marker implements StringRepresentable {
        /** The waypoint's icon (a search area's icon with a radius). */
        POINT("point"),
        /** Three animated "!", the middle one larger: something is happening there. */
        EVENT("event"),
        /** An animated "?": something unknown is there. */
        QUESTION("question"),
        /** A trail's scent: wisps drifting along it the way it leads, instead of its tracks. */
        SCENT("scent");

        public static final Codec<Marker> CODEC = StringRepresentable.fromEnum(Marker::values);

        private final String name;

        Marker(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
