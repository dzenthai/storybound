package dev.dzenthai.storybound.client.camera;

import dev.dzenthai.storybound.module.dialogue.CameraCue;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where to put the dialogue camera for a shot, keeping it out of walls.
 *
 * <p>The main shot is the "dirty single" of RPG dialogues: the camera sits just behind the listener's shoulder,
 * which fills an edge of the frame, out of focus in spirit; the speaker stands near a vertical third on the other
 * side, eyes on the upper third, looking across the frame at the listener. The lens is narrower than the player's,
 * chosen from the distance so every shot of the speaker has the same size and the shot and reverse shot match.
 * Positions behind the shoulder are tried on a small grid and scored by that composition; the best one that is out
 * of walls and sees the speaker's face wins. Pure geometry: the world is asked only through {@link Obstacles}.
 */
public final class CameraShots {
    /**
     * Candidate positions relative to the listener's eyes: back (away from the speaker), aside (outward from the
     * axis), up. With a long lens the listener is a sliver at the edge only from well behind and to the side of them;
     * right beside them they fall far outside the narrow frame, and right behind them they fill it.
     */
    private static final double[] BACKS = {0.3, 0.6, 0.9, 1.2, 1.5, 1.8, 2.1};
    private static final double[] ASIDES = {0.45, 0.6, 0.75, 0.9, 1.1, 1.3, 1.5};
    private static final double[] UPS = {-0.3, -0.15, 0.0, 0.15};
    /** The listener's body: a camera this close to the axis must be behind their head (half a block deep) and the near plane. */
    private static final double BODY_HALF_WIDTH = 0.55;
    private static final double MIN_BEHIND = 0.32;

    /** Where the speaker's eyes go on screen ([-1, 1] each way): on the third away from the listener, eyes high. */
    private static final double SPEAKER_X = 0.3;
    private static final double SPEAKER_Y = 0.33;
    /**
     * The listener as a sliver, as in The Witcher: their near shoulder at the frame edge, below the middle; their
     * head out of frame or barely entering it, never reaching further in than {@link #LISTENER_INNER_X}.
     */
    private static final double LISTENER_SHOULDER_X = 0.9;
    private static final double LISTENER_SHOULDER_Y = -0.3;
    private static final double LISTENER_INNER_X = 0.75;
    /** The near shoulder, from the listener's eyes: down and out towards the camera's side. */
    private static final double SHOULDER_BELOW_EYES = 0.3;
    private static final double SHOULDER_OUT = 0.3;
    /** The listener's head radius on screen, in half frame heights, beyond which it starts to cost. */
    private static final double LISTENER_MAX_RADIUS = 0.6;
    /** A listener entirely out of frame: acceptable, but worse than a sliver at the edge. */
    private static final double UNSEEN_LISTENER_SCORE = 2.0;
    /** Free space kept between the listener's head and the speaker's face on screen. */
    private static final double FACE_CLEARANCE = 0.22;
    /** The head's centre is about this far above the eyes; its radius covers the half-block cube. */
    private static final double HEAD_ABOVE_EYES = 0.1;
    private static final double HEAD_RADIUS = 0.3;
    /** On screen, in half frame heights: a listener's head bigger than this fills the frame instead of framing it. */
    private static final double MAX_HEAD_RADIUS = 1.0;

    /** How much of the speaker the lens shows, in blocks of height at their distance: head to waist, above the dialogue panel. */
    private static final double SHOULDER_FRAME_HEIGHT = 1.7;
    private static final double CLOSE_UP_FRAME_HEIGHT = 1.0;
    private static final double MIN_FOV = 25;
    private static final double MAX_FOV = 70;

    /** How far in front of the listener's face a clean single stands: past the near plane and the nose. */
    private static final double CLEAN_SINGLE_AHEAD = 0.6;
    private static final double MIN_TWO_SHOT_DISTANCE = 1.2;
    private static final double MIN_CLOSE_UP_DISTANCE = 0.6;
    /** Rays start this far around the ideal line, like the vanilla third-person camera, so the near plane stays out of walls. */
    private static final double RAY_SPREAD = 0.1;
    /** Distance kept from the wall a ray hit. */
    private static final double WALL_MARGIN = 0.2;
    /** A line of sight "reaches" a face when it stops this close to it. */
    private static final double SIGHT_TOLERANCE = 0.1;

    private CameraShots() {}

    /** The level's blocks, as {@code ClipContext.Block.COLLIDER} sees them. */
    @FunctionalInterface
    public interface Obstacles {
        /** Where a straight line from {@code from} to {@code to} first hits a block; {@code to} when nothing is in the way. */
        Vec3 clip(Vec3 from, Vec3 to);
    }

    /**
     * A camera position, the direction it looks in Minecraft's yaw and pitch, and its vertical field of view.
     *
     * @param fov degrees; 0 keeps the player's own
     */
    public record Pose(Vec3 pos, float yaw, float pitch, float fov) {
        public static Pose looking(Vec3 from, Vec3 target, float fov) {
            double dx = target.x - from.x;
            double dy = target.y - from.y;
            double dz = target.z - from.z;
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            return new Pose(from, yaw, pitch, fov);
        }

        /**
         * Where a point lands on screen: x and y from -1 (left, bottom) to 1 (right, top) at the frame edges.
         *
         * @param defaultFov used when this pose keeps the player's field of view
         * @return null for a point behind the camera
         */
        public @Nullable ScreenPoint project(Vec3 point, double aspect, double defaultFov) {
            Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
            Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 up = right.cross(forward);
            Vec3 offset = point.subtract(pos);
            double depth = offset.dot(forward);
            if (depth < 0.01) {
                return null;
            }
            double tan = Math.tan(Math.toRadians(fov > 0 ? fov : defaultFov) / 2);
            return new ScreenPoint(offset.dot(right) / (depth * tan * aspect), offset.dot(up) / (depth * tan), depth);
        }
    }

    /** @param depth distance in front of the camera, in blocks */
    public record ScreenPoint(double x, double y, double depth) {}

    /**
     * @param side the side of the axis between the speakers that was used; later shots of the session keep it (the 180° rule)
     */
    public record Framing(Pose pose, int side) {}

    /**
     * Frames {@code focus}, the face being shown, with {@code listener} as the other speaker.
     *
     * @param player  the eyes of the player in the dialogue; with {@code partner} it defines the axis
     * @param side    +1 for the right of the axis from the player to the partner, -1 for the left
     * @param aspect  the window's width over its height
     * @return null when nothing fits: play the shot in first person
     */
    public static @Nullable Framing frame(CameraCue.Shot shot, Vec3 focus, Vec3 listener, Vec3 player, Vec3 partner, int side,
                                          double aspect, Obstacles obstacles) {
        if (shot == CameraCue.Shot.FIRST_PERSON) {
            return null;
        }
        Vec3 right = right(player, partner);
        Framing framing = null;
        if (shot == CameraCue.Shot.CLOSE_UP) {
            framing = closeUp(focus, listener, right, side, obstacles);
        }
        if (framing == null && shot != CameraCue.Shot.TWO_SHOT) {
            framing = shoulder(focus, listener, right, side, aspect, obstacles);
            if (framing == null) {
                framing = shoulder(focus, listener, right, -side, aspect, obstacles);
            }
            if (framing == null) {
                framing = cleanSingle(focus, listener, side, aspect, obstacles);
            }
        }
        if (framing == null) {
            framing = twoShot(player, partner, right, side, obstacles);
        }
        if (framing == null) {
            framing = twoShot(player, partner, right, -side, obstacles);
        }
        return framing;
    }

    /** The best-composed position behind the listener's shoulder on this side, or null when none fits. */
    private static @Nullable Framing shoulder(Vec3 focus, Vec3 listener, Vec3 right, int side, double aspect, Obstacles obstacles) {
        Vec3 back = horizontal(listener.subtract(focus));
        Vec3 aside = right.scale(side);
        Pose best = null;
        double bestScore = Double.MAX_VALUE;
        for (double behind : BACKS) {
            for (double out : ASIDES) {
                for (double up : UPS) {
                    Vec3 ideal = listener.add(back.scale(behind)).add(aside.scale(out)).add(0, up, 0);
                    Vec3 pos = reach(listener, ideal, obstacles);
                    Vec3 local = pos.subtract(listener);
                    boolean insideBody = Math.abs(local.dot(aside)) < BODY_HALF_WIDTH && local.dot(back) < MIN_BEHIND;
                    if (insideBody || !sees(pos, focus, obstacles)) {
                        continue;
                    }
                    Pose pose = compose(pos, focus, listener, lensFor(pos.distanceTo(focus), SHOULDER_FRAME_HEIGHT), aspect);
                    double score = score(pose, focus, listener, aside, aspect);
                    if (score < bestScore) {
                        bestScore = score;
                        best = pose;
                    }
                }
            }
        }
        return best == null ? null : new Framing(best, side);
    }

    /**
     * Aims the camera so the speaker's eyes land at the composition point on the side away from the listener.
     * A few corrections are enough: yaw and pitch barely affect each other this close to the centre.
     */
    static Pose compose(Vec3 pos, Vec3 focus, Vec3 listener, float fov, double aspect) {
        Pose pose = Pose.looking(pos, focus, fov);
        // Beside the listener they may be level with or behind the camera, so their side comes from geometry, not projection.
        Vec3 viewRight = Vec3.directionFromRotation(pose.pitch(), pose.yaw()).cross(new Vec3(0, 1, 0));
        double wantedX = listener.subtract(pos).dot(viewRight) > 0 ? -SPEAKER_X : SPEAKER_X;
        double tan = Math.tan(Math.toRadians(fov) / 2);
        for (int i = 0; i < 4; i++) {
            ScreenPoint at = pose.project(focus, aspect, fov);
            if (at == null) {
                break;
            }
            float yaw = pose.yaw() - (float) Math.toDegrees(Math.atan((wantedX - at.x()) * tan * aspect));
            float pitch = pose.pitch() + (float) Math.toDegrees(Math.atan((SPEAKER_Y - at.y()) * tan));
            pose = new Pose(pos, yaw, pitch, fov);
        }
        return pose;
    }

    /**
     * Lower is better; {@link Double#MAX_VALUE} when the listener would hide the speaker's face or fill the frame.
     * The aim is the listener as a sliver at the edge: the near shoulder in place, the head out of frame or at its rim.
     *
     * @param outward the side of the axis the camera is on, from the listener
     */
    private static double score(Pose pose, Vec3 focus, Vec3 listener, Vec3 outward, double aspect) {
        ScreenPoint face = pose.project(focus, aspect, pose.fov());
        if (face == null) {
            return Double.MAX_VALUE;
        }
        double tilt = pose.pitch() / 30.0;
        ScreenPoint head = pose.project(listener.add(0, HEAD_ABOVE_EYES, 0), aspect, pose.fov());
        ScreenPoint shoulder = pose.project(listener.add(outward.scale(SHOULDER_OUT)).add(0, -SHOULDER_BELOW_EYES, 0), aspect, pose.fov());
        if (head == null || shoulder == null) {
            return UNSEEN_LISTENER_SCORE + 0.5 * tilt * tilt;
        }
        double tan = Math.tan(Math.toRadians(pose.fov()) / 2);
        double headRadius = HEAD_RADIUS / (head.depth() * tan);
        // Compared in units of the frame's height, so x is stretched by the aspect ratio.
        double apart = Math.hypot((face.x() - head.x()) * aspect, face.y() - head.y());
        if (apart < headRadius + FACE_CLEARANCE || headRadius > MAX_HEAD_RADIUS) {
            return Double.MAX_VALUE;
        }
        double edge = Math.abs(shoulder.x()) - LISTENER_SHOULDER_X;
        double low = shoulder.y() - LISTENER_SHOULDER_Y;
        // The head's inner rim may reach the frame edge zone but no further in; above the frame it is out anyway.
        boolean headAbove = head.y() - headRadius > 1;
        double intrusion = headAbove ? 0 : Math.max(0, LISTENER_INNER_X - (Math.abs(head.x()) - headRadius / aspect));
        // A big blurred shoulder is fine; a head filling a third of the frame is not a sliver.
        double tooBig = Math.max(0, headRadius - LISTENER_MAX_RADIUS);
        return 4 * edge * edge + 2 * low * low + 4 * intrusion * intrusion + 3 * tooBig * tooBig + 0.5 * tilt * tilt;
    }

    /**
     * No room behind the shoulder (a corridor): a "clean single" from just in front of the listener's face, the
     * listener out of frame, with the same lens and the speaker's eyes on the upper third.
     */
    private static @Nullable Framing cleanSingle(Vec3 focus, Vec3 listener, int side, double aspect, Obstacles obstacles) {
        Vec3 toward = horizontal(focus.subtract(listener));
        Vec3 pos = reach(listener, listener.add(toward.scale(CLEAN_SINGLE_AHEAD)).add(0, -0.05, 0), obstacles);
        if (pos.distanceTo(listener) < CLEAN_SINGLE_AHEAD - 0.15 || pos.distanceTo(focus) < MIN_CLOSE_UP_DISTANCE + 0.4 || !sees(pos, focus, obstacles)) {
            return null;
        }
        float fov = lensFor(pos.distanceTo(focus), SHOULDER_FRAME_HEIGHT);
        Pose pose = Pose.looking(pos, focus, fov);
        float pitch = pose.pitch() + (float) Math.toDegrees(Math.atan(SPEAKER_Y * Math.tan(Math.toRadians(fov) / 2)));
        return new Framing(new Pose(pos, pose.yaw(), pitch, fov), side);
    }

    /** A field of view showing {@code height} blocks at {@code distance}; the same shot size whatever the distance. */
    static float lensFor(double distance, double height) {
        double fov = Math.toDegrees(2 * Math.atan(height / 2 / Math.max(0.1, distance)));
        return (float) Math.clamp(fov, MIN_FOV, MAX_FOV);
    }

    private static @Nullable Framing twoShot(Vec3 player, Vec3 partner, Vec3 right, int side, Obstacles obstacles) {
        Vec3 middle = player.add(partner).scale(0.5).add(0, -0.1, 0);
        double apart = Math.hypot(partner.x - player.x, partner.z - player.z);
        double distance = Math.clamp(apart * 0.9 + 1.0, 1.8, 4.5);
        Vec3 ideal = middle.add(right.scale(side * distance)).add(0, 0.3, 0);
        Vec3 pos = reach(middle, ideal, obstacles);
        if (pos.distanceTo(middle) < MIN_TWO_SHOT_DISTANCE || !sees(pos, player, obstacles) || !sees(pos, partner, obstacles)) {
            return null;
        }
        return new Framing(Pose.looking(pos, middle, 0f), side);
    }

    private static @Nullable Framing closeUp(Vec3 focus, Vec3 listener, Vec3 right, int side, Obstacles obstacles) {
        Vec3 front = horizontal(listener.subtract(focus));
        Vec3 ideal = focus.add(front.scale(1.1)).add(right.scale(side * 0.25)).add(0, 0.05, 0);
        Vec3 pos = reach(focus, ideal, obstacles);
        if (pos.distanceTo(focus) < MIN_CLOSE_UP_DISTANCE) {
            return null;
        }
        return new Framing(Pose.looking(pos, focus, lensFor(pos.distanceTo(focus), CLOSE_UP_FRAME_HEIGHT)), side);
    }

    /**
     * How far towards {@code ideal} the camera may go from {@code from}: the nearest hit of a few rays around the
     * line, minus a margin. Like the vanilla third-person camera, so the near plane does not cut into walls.
     */
    static Vec3 reach(Vec3 from, Vec3 ideal, Obstacles obstacles) {
        Vec3 line = ideal.subtract(from);
        double length = line.length();
        if (length < 1e-6) {
            return from;
        }
        double free = length;
        for (int i = 0; i < 8; i++) {
            Vec3 offset = new Vec3((i & 1) == 0 ? -RAY_SPREAD : RAY_SPREAD, (i & 2) == 0 ? -RAY_SPREAD : RAY_SPREAD, (i & 4) == 0 ? -RAY_SPREAD : RAY_SPREAD);
            Vec3 start = from.add(offset);
            double hit = obstacles.clip(start, ideal.add(offset)).distanceTo(start);
            if (hit < length - 1e-6) {
                free = Math.min(free, hit - WALL_MARGIN);
            }
        }
        return from.add(line.scale(Math.max(0, free) / length));
    }

    private static boolean sees(Vec3 from, Vec3 face, Obstacles obstacles) {
        return obstacles.clip(from, face).distanceTo(face) <= SIGHT_TOLERANCE;
    }

    /** The right-hand side of someone at {@code player} facing {@code partner}, level with the ground. */
    static Vec3 right(Vec3 player, Vec3 partner) {
        Vec3 forward = horizontal(partner.subtract(player));
        if (forward.lengthSqr() < 1e-8) {
            forward = new Vec3(0, 0, 1);
        }
        return new Vec3(-forward.z, 0, forward.x);
    }

    private static Vec3 horizontal(Vec3 vector) {
        Vec3 flat = new Vec3(vector.x, 0, vector.z);
        double length = flat.length();
        return length < 1e-8 ? Vec3.ZERO : flat.scale(1 / length);
    }
}
