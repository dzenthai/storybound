package dev.dzenthai.storybound.module.scene;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Shared camera placement and obstacle-avoidance planner used identically by server playback ({@link Scenes}) and the
 * in-game 3D camera editor ({@code ScenePreview}) so what the author sees in the editor matches playback 1-to-1.
 *
 * <ul>
 *   <li><b>Explicit {@code above_ground > 0}:</b> lifts every control point and chord midpoint above terrain/roofs;
 *       when {@code safe_angle} is also on, keeps points clear of any solid ceiling/overhang.</li>
 *   <li><b>Subject-focused entity shot ({@code entityAnchor && lookAtSubject && safe_angle}):</b> rotates to an open
 *       angle when the authored {@code 0°} line of sight is blocked ({@code lookAtAnchor}) and pulls any obstructed
 *       point in front of the nearest collider so trees or walls never hide the speaker/character.</li>
 *   <li><b>Flythrough / village / location shot ({@code (!entityAnchor || !lookAtSubject) && safe_angle}):</b> preserves
 *       the authored path around buildings and streets without spinning or pulling back toward the anchor, only keeping
 *       camera points clear of solid blocks.</li>
 *   <li><b>{@code safe_angle: false} (and {@code above_ground <= 0}):</b> raw authored coordinates untouched.</li>
 * </ul>
 */
public final class SceneCameraSafety {
    /** Candidate yaw rotations (degrees) tried when an entity shot's authored {@code 0°} direction is blocked. */
    private static final float[] SAFE_YAW_DELTAS = {0f, 45f, -45f, 90f, -90f, 135f, -135f, 180f};
    /** Margin kept in front of a block hit when pulling a blocked LOS waypoint out of a wall. */
    private static final double WALL_PADDING = 0.35;
    /** Closest a pulled-back LOS waypoint may approach the look origin. */
    private static final double MIN_PULLBACK_DIST = 0.85;
    /** Half-extent of the camera collision box so the near clip plane never pokes inside a wall face. */
    public static final double CAMERA_HALF_EXTENT = 0.26;
    private static final double CAMERA_SIZE = CAMERA_HALF_EXTENT * 2.0;

    private static final double[] ESCAPE_RADII = {0.20, 0.42, 0.85, 1.4, 2.2, 3.2};
    private static final Vec3[] ESCAPE_DIRS = {
            new Vec3(0, 1, 0),
            new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1),
            new Vec3(0.7071, 0, 0.7071), new Vec3(-0.7071, 0, 0.7071),
            new Vec3(0.7071, 0, -0.7071), new Vec3(-0.7071, 0, -0.7071),
            new Vec3(0.7071, 0.7071, 0), new Vec3(-0.7071, 0.7071, 0),
            new Vec3(0, 0.7071, 0.7071), new Vec3(0, 0.7071, -0.7071),
            new Vec3(0, -1, 0)
    };

    private SceneCameraSafety() {}

    /**
     * Final planned world-space control points for a shot, preserving exact control-point count ({@code from},
     * {@code via...}, {@code to}) alongside the effective anchor yaw and whether safety shifted anything.
     */
    public record Plan(float yaw, Vec3 from, List<Vec3> via, Vec3 to, boolean adjusted) {
        public ScenePath path() {
            return ScenePath.of(from, via, to);
        }

        public List<Vec3> controlPoints() {
            List<Vec3> all = new ArrayList<>(via.size() + 2);
            all.add(from);
            all.addAll(via);
            all.add(to);
            return all;
        }
    }

    /** A point given as right, up, forward of an anchor at {@code anchorPos} facing {@code anchorYaw}. */
    public static Vec3 place(Vec3 anchorPos, float anchorYaw, Vec3 offset) {
        double yaw = Math.toRadians(anchorYaw);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        return anchorPos.add(right.scale(offset.x)).add(0, offset.y, 0).add(forward.scale(offset.z));
    }

    /**
     * Plans a shot's world-space control points using the shared obstacle-avoidance rules.
     *
     * @param level         world for block/heightmap queries (may be null in headless preview fallback)
     * @param anchorPos     anchor base position
     * @param anchorYaw     anchor facing yaw in degrees
     * @param entityAnchor  true when anchored to an entity (player, NPC, mob); false for structure/POI/static waypoint
     * @param lookOrigin    world-space subject eye/point when {@code lookAtSubject} is true
     * @param localFrom     authored anchor-local start offset
     * @param localVia      authored anchor-local intermediate offsets
     * @param localTo       authored anchor-local end offset
     * @param lookAtSubject true for {@code anchor}, {@code player}, or another anchor; false for {@code free} / {@code path}
     * @param lookAtAnchor  true only for {@code look: "anchor"} (enables open-angle yaw search on entity anchors)
     * @param aboveGround   minimum height above terrain/roofs (0 = disabled)
     * @param safeAngle     whether obstacle avoidance is enabled
     */
    public static Plan plan(@Nullable Level level, Vec3 anchorPos, float anchorYaw, boolean entityAnchor, Vec3 lookOrigin,
                            Vec3 localFrom, List<Vec3> localVia, Vec3 localTo,
                            boolean lookAtSubject, boolean lookAtAnchor, float aboveGround, boolean safeAngle) {
        float effectiveYaw = anchorYaw;
        if (level != null && safeAngle && aboveGround <= 0f && entityAnchor && lookAtAnchor) {
            effectiveYaw = chooseOpenAngle(level, anchorPos, anchorYaw, lookOrigin, localFrom, localVia, localTo);
        }

        Vec3 rawFrom = place(anchorPos, anchorYaw, localFrom);
        Vec3 rawTo = place(anchorPos, anchorYaw, localTo);
        Vec3 from = place(anchorPos, effectiveYaw, localFrom);
        List<Vec3> via = new ArrayList<>(localVia.size());
        for (Vec3 offset : localVia) {
            via.add(place(anchorPos, effectiveYaw, offset));
        }
        Vec3 to = place(anchorPos, effectiveYaw, localTo);

        boolean adjusted = Math.abs(Mth.wrapDegrees(effectiveYaw - anchorYaw)) > 1.0e-3f;
        if (level != null) {
            if (aboveGround > 0f) {
                from = liftAboveGround(level, from, aboveGround);
                for (int i = 0; i < via.size(); i++) {
                    via.set(i, liftAboveGround(level, via.get(i), aboveGround));
                }
                to = liftAboveGround(level, to, aboveGround);
                ScenePath prelim = ScenePath.of(from, via, to);
                double extraLift = 0.0;
                for (double sample : new double[] {0.25, 0.5, 0.75}) {
                    Vec3 mid = prelim.pointAt(sample);
                    double need = maxSurfaceY(level, mid.x, mid.z) + aboveGround - mid.y;
                    if (need > extraLift) {
                        extraLift = need;
                    }
                }
                if (extraLift > 0.0) {
                    from = from.add(0, extraLift, 0);
                    for (int i = 0; i < via.size(); i++) {
                        via.set(i, via.get(i).add(0, extraLift, 0));
                    }
                    to = to.add(0, extraLift, 0);
                }
                if (safeAngle) {
                    from = keepOutOfSolids(level, from);
                    for (int i = 0; i < via.size(); i++) {
                        via.set(i, keepOutOfSolids(level, via.get(i)));
                    }
                    to = keepOutOfSolids(level, to);
                }
            } else if (safeAngle) {
                if (entityAnchor && lookAtSubject) {
                    from = keepLineOfSightClear(level, lookOrigin, from);
                    for (int i = 0; i < via.size(); i++) {
                        via.set(i, keepLineOfSightClear(level, lookOrigin, via.get(i)));
                    }
                    to = keepLineOfSightClear(level, lookOrigin, to);
                } else {
                    from = keepOutOfSolids(level, from);
                    for (int i = 0; i < via.size(); i++) {
                        via.set(i, keepOutOfSolids(level, via.get(i)));
                    }
                    to = keepOutOfSolids(level, to);
                }
            }
        }

        if (!adjusted) {
            adjusted = from.distanceToSqr(rawFrom) > 1.0e-4 || to.distanceToSqr(rawTo) > 1.0e-4;
            if (!adjusted) {
                for (int i = 0; i < via.size(); i++) {
                    if (via.get(i).distanceToSqr(place(anchorPos, anchorYaw, localVia.get(i))) > 1.0e-4) {
                        adjusted = true;
                        break;
                    }
                }
            }
        }
        return new Plan(effectiveYaw, from, List.copyOf(via), to, adjusted);
    }

    /**
     * Picks the clearest yaw rotation around an entity anchor when the authored {@code 0°} direction would put the
     * camera behind a wall, tree trunk, or hillside. If {@code 0°} is completely unobstructed, it is kept untouched.
     */
    private static float chooseOpenAngle(Level level, Vec3 anchorPos, float anchorYaw, Vec3 lookOrigin,
                                         Vec3 localFrom, List<Vec3> localVia, Vec3 localTo) {
        List<Vec3> offsets = new ArrayList<>(localVia.size() + 2);
        offsets.add(localFrom);
        offsets.addAll(localVia);
        if (!localTo.equals(localFrom)) {
            offsets.add(localTo);
        }
        float bestYaw = anchorYaw;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (float delta : SAFE_YAW_DELTAS) {
            float candidateYaw = anchorYaw + delta;
            int clear = 0;
            double freeRatioSum = 0.0;
            for (Vec3 offset : offsets) {
                Vec3 pt = place(anchorPos, candidateYaw, offset);
                double dist = lookOrigin.distanceTo(pt);
                if (dist < 1.0e-4) {
                    clear++;
                    freeRatioSum += 1.0;
                    continue;
                }
                BlockHitResult hit = level.clip(new ClipContext(lookOrigin, pt, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
                boolean inAir = isCameraClear(level, pt);
                if (hit.getType() == HitResult.Type.MISS && inAir) {
                    clear++;
                    freeRatioSum += 1.0;
                } else {
                    double hitDist = hit.getType() == HitResult.Type.MISS ? dist : lookOrigin.distanceTo(hit.getLocation());
                    freeRatioSum += Math.clamp(hitDist / dist, 0.0, 1.0) * (inAir ? 1.0 : 0.5);
                }
            }
            if (delta == 0f && clear == offsets.size()) {
                return anchorYaw;
            }
            double score = clear * 10.0 + freeRatioSum - Math.abs(delta) * 0.001;
            if (score > bestScore) {
                bestScore = score;
                bestYaw = candidateYaw;
            }
        }
        return bestYaw;
    }

    /** Pulls a blocked LOS waypoint safely in front of the nearest collider along {@code origin -> target}, and out of solid blocks. */
    public static Vec3 keepLineOfSightClear(Level level, Vec3 origin, Vec3 target) {
        Vec3 diff = target.subtract(origin);
        double dist = diff.length();
        Vec3 result = target;
        if (dist > 1.0e-4) {
            BlockHitResult hit = level.clip(new ClipContext(origin, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) {
                double hitDist = origin.distanceTo(hit.getLocation());
                double safeDist = Math.max(Math.min(dist, MIN_PULLBACK_DIST), hitDist - WALL_PADDING);
                if (safeDist < dist) {
                    result = origin.add(diff.scale(safeDist / dist));
                }
            }
        }
        return keepOutOfSolids(level, result);
    }

    /**
     * Keeps a camera point out of solid block colliders without pulling it back toward the anchor: if already in clear
     * air it is returned untouched; otherwise the closest unobstructed air position nearby is chosen.
     */
    public static Vec3 keepOutOfSolids(Level level, Vec3 target) {
        if (isCameraClear(level, target)) {
            return target;
        }
        for (double radius : ESCAPE_RADII) {
            Vec3 best = null;
            double bestCost = Double.POSITIVE_INFINITY;
            for (Vec3 dir : ESCAPE_DIRS) {
                Vec3 candidate = target.add(dir.scale(radius));
                if (!isCameraClear(level, candidate)) {
                    continue;
                }
                // Slight preference for staying near original Y or lifting gently over pushing downward.
                double cost = radius + (dir.y < 0 ? 0.35 : 0.0);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = candidate;
                }
            }
            if (best != null) {
                return best;
            }
        }
        Vec3 lifted = target;
        for (int step = 0; step < 16 && !isCameraClear(level, lifted); step++) {
            lifted = lifted.add(0, 0.5, 0);
        }
        return lifted;
    }

    /**
     * Moves a piloted editor camera from {@code from} toward {@code to} while sliding smoothly along solid walls,
     * floors, and ceilings so Pilot editing matches safe-angle playback without clipping through blocks.
     */
    public static Vec3 slideMove(Level level, Vec3 from, Vec3 to) {
        if (from.distanceToSqr(to) < 1.0e-9) {
            return from;
        }
        if (!isCameraClear(level, from)) {
            // Already embedded (e.g. just toggled Safe angle on): let the author move freely toward clear air.
            return isCameraClear(level, to) ? to : keepOutOfSolids(level, to);
        }
        if (isSegmentClear(level, from, to)) {
            return to;
        }
        Vec3 delta = to.subtract(from);
        Vec3 cur = from;
        // Try each axis independently so flying diagonally into a wall glides smoothly along it.
        cur = tryAxisStep(level, cur, new Vec3(delta.x, 0, 0));
        cur = tryAxisStep(level, cur, new Vec3(0, delta.y, 0));
        cur = tryAxisStep(level, cur, new Vec3(0, 0, delta.z));
        return cur;
    }

    private static Vec3 tryAxisStep(Level level, Vec3 cur, Vec3 step) {
        if (step.lengthSqr() < 1.0e-9) {
            return cur;
        }
        Vec3 next = cur.add(step);
        if (isSegmentClear(level, cur, next)) {
            return next;
        }
        // Binary search to stop cleanly right against the wall face without snagging or bouncing.
        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < 6; i++) {
            double mid = (lo + hi) * 0.5;
            Vec3 probe = cur.add(step.scale(mid));
            if (isSegmentClear(level, cur, probe)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo > 0.01 ? cur.add(step.scale(lo)) : cur;
    }

    private static boolean isSegmentClear(Level level, Vec3 from, Vec3 to) {
        if (!isCameraClear(level, to)) {
            return false;
        }
        if (from.distanceToSqr(to) > 0.25 && !isCameraClear(level, from.lerp(to, 0.5))) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** True when a small camera box around {@code pt} does not overlap any solid block collision shape. */
    public static boolean isCameraClear(Level level, Vec3 pt) {
        AABB box = AABB.ofSize(pt, CAMERA_SIZE, CAMERA_SIZE, CAMERA_SIZE);
        return !level.getBlockCollisions(null, box).iterator().hasNext();
    }

    /** Lifts {@code pt} to at least {@code clearance} blocks above the highest nearby motion-blocking surface (hills, canopies, roofs). */
    public static Vec3 liftAboveGround(Level level, Vec3 pt, float clearance) {
        double minY = maxSurfaceY(level, pt.x, pt.z) + clearance;
        return pt.y < minY ? new Vec3(pt.x, minY, pt.z) : pt;
    }

    public static double maxSurfaceY(Level level, double x, double z) {
        int cx = Mth.floor(x);
        int cz = Mth.floor(z);
        int max = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz);
        max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx + 2, cz));
        max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx - 2, cz));
        max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz + 2));
        max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz - 2));
        return max;
    }
}
