package dev.dzenthai.storybound.module.waypoint;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * A waypoint's trail ({@code trace}), shown by its tracks or its scent instead of a marker. Either the points the author
 * walked ({@code "trace": [[x, y, z], ...]}, the target, a point, at its end), or found as the game goes
 * ({@code "trace": { }}): the real tracks of the creature the target finds (an entity, the nearest mob, an NPC, a
 * boss).
 */
public sealed interface WaypointTrace {
    int MAX_POINTS = 512;

    /** The points the author walked, from start to end. */
    record Recorded(List<Vec3> points) implements WaypointTrace {}

    /** Found as the game goes: the creature's real tracks. */
    record Found() implements WaypointTrace {}

    /** An object, empty: the creature's tracks need nothing more. */
    Codec<Found> FOUND_CODEC = MapCodec.unit(new Found()).codec();

    Codec<WaypointTrace> CODEC = Codec.either(Vec3.CODEC.sizeLimitedListOf(MAX_POINTS), FOUND_CODEC).xmap(
            either -> either.map(Recorded::new, found -> found),
            trace -> trace instanceof Recorded recorded ? Either.left(recorded.points()) : Either.right((Found) trace));

    /** How a client draws a trail. */
    enum Kind {
        /** No trail: a marker. */
        NONE,
        /** The author's points, prints on any ground. */
        RECORDED,
        /** A creature's real steps, oldest first: prints on soft ground, the newest the brightest. */
        TRACKS
    }
}
