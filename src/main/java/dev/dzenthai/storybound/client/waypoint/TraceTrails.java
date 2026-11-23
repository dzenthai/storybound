package dev.dzenthai.storybound.client.waypoint;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.compat.cutscene.CutsceneClient;
import dev.dzenthai.storybound.module.waypoint.AreaLook;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import dev.dzenthai.storybound.module.waypoint.WaypointParticles;
import dev.dzenthai.storybound.module.waypoint.WaypointTrace;
import dev.dzenthai.storybound.module.waypoint.WaypointTracks;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Trails ({@code trace} waypoints) as motes rising where the steps were: along the trail's points, a step about every
 * block (further apart for a big walker), left and right of the way in turn; from each step, now and then, a glowing
 * mote in the waypoint's colour ({@link TrailParticle}) rises and fades, so a chain of flickering sparks shows the
 * way, as the witcher's senses light tracks up. With the {@code scent} marker, wisps instead ({@link WispParticle}):
 * puffs at chest height drifting along the trail the way it leads (to the creature, for its tracks), over any ground,
 * as a scent in the air, and strands ({@link ScentStrands}), thin glowing lines running quicker along it. Only near the player: steps within {@link #FULL} blocks give motes
 * at full rate, fewer further off, none past {@link #REACH}, so a trail is found and followed, never seen whole. Each
 * step sits on the surface found nearest it ({@link #surface}), on a slope too, never in the air. The author's points give motes on any
 * ground; a creature's real steps only on ground that takes tracks ({@link #TAKES_TRACKS}: earth, sand, snow...), so
 * they break off on stone, the newest steps the liveliest, the oldest seldom, and a jump of more than {@link #GAP}
 * blocks (a teleport) is not walked. Shown whatever the player's particle setting is: a trail is the story's. While the
 * dev mode records a trail ({@link #preview}), that one gives motes too, in the default waypoint colour.
 */
public final class TraceTrails {
    /** Blocks that keep a creature's tracks: a tag, so packs and mods can add their own. */
    public static final TagKey<Block> TAKES_TRACKS = TagKey.create(Registries.BLOCK, Storybound.id("takes_tracks"));
    private static final double FULL = 5;
    private static final double REACH = 11;
    /** Steps further apart than this are not joined: the creature did not walk between them. */
    private static final double GAP = 8;
    /** The chance a step near the player gives a mote in a tick: about two a second. */
    private static final float RATE = 0.1f;
    private static final float DIMMED_RATE = 0.4f;
    /** For a scent, the chance a step near the player gives a wisp in a tick (one every second or two), and its speed. */
    private static final float WISP_RATE = 0.035f;
    private static final double WISP_SPEED = 0.035;
    /** For a scent, the chance a step near the player starts a strand in a tick: one every three or four seconds. */
    private static final float STRAND_RATE = 0.014f;
    /** The least a creature's oldest step gives, beside its newest. */
    private static final float OLDEST = 0.3f;
    /**
     * How far below and above a step's place a surface is looked for: a step between two points lies on the straight
     * line between them, which on a slope runs inside the hill or above the dip.
     */
    private static final double SEARCH_DOWN = 2.5;
    private static final double SEARCH_UP = 1.6;
    /** A block this thin over soft ground (a carpet of moss, a thin layer) keeps tracks as the ground does. */
    private static final double THIN = 0.2;

    private static final RandomSource RANDOM = RandomSource.create();
    private static @Nullable List<Vec3> preview;
    private static @Nullable ResourceLocation previewDimension;

    private TraceTrails() {}

    /** A trail being recorded, shown as it grows; null to stop showing it. */
    public static void preview(@Nullable List<Vec3> points, @Nullable ResourceLocation dimension) {
        preview = points == null ? null : List.copyOf(points);
        previewDimension = dimension;
    }

    /** Every client tick: the strands move on, and the steps near the player give motes, or for a scent wisps and strands. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        boolean running = level != null && minecraft.player != null && ClientModules.isEnabled("waypoints") && !CutsceneClient.playing();
        if (!running) {
            ScentStrands.tick(false);
            return;
        }
        // Paused, the strands wait where they are.
        if (minecraft.isPaused()) {
            return;
        }
        ScentStrands.tick(true);
        ResourceLocation here = level.dimension().location();
        Vec3 player = minecraft.player.position();
        for (ClientWaypoint waypoint : ClientWaypoints.shown()) {
            if (waypoint.isTrace() && waypoint.trace().size() >= 2 && waypoint.dimension().equals(here)) {
                float rate = ClientWaypoints.display(waypoint) == ClientWaypoints.Display.DIMMED ? RATE * DIMMED_RATE : RATE;
                walk(level, player, waypoint.traceKind(), waypoint.trace(), waypoint.traceSize(), MarkerContrast.readable(waypoint.color()), rate,
                        waypoint.marker() == AreaLook.Marker.SCENT);
            }
        }
        List<Vec3> recording = preview;
        if (recording != null && recording.size() >= 2 && here.equals(previewDimension)) {
            walk(level, player, WaypointTrace.Kind.RECORDED, recording, WaypointTracks.DEFAULT_WIDTH, MarkerContrast.readable(WaypointDefinition.DEFAULT_COLOR), RATE,
                    false);
        }
    }

    /**
     * The steps along the trail, one after another across segments, each near the player a chance of a mote, or for a
     * {@code scent} of a wisp: on the way itself, over any ground (a scent is in the air), drifting the way it leads.
     */
    private static void walk(ClientLevel level, Vec3 player, WaypointTrace.Kind kind, List<Vec3> points, float width, int rgb, float rate,
                             boolean scent) {
        double size = Math.max(0.3, width);
        // About a block for a player-sized walker (0.7 + half its width), further apart for a big one.
        double step = 0.7 + 0.5 * size;
        double walked = 0;
        double next = 0;
        int foot = 1;
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double length = Math.hypot(b.x - a.x, b.z - a.z);
            boolean joined = length > 1.0e-4 && (kind != WaypointTrace.Kind.TRACKS || a.distanceTo(b) <= GAP);
            boolean near = Math.min(flat(a, player), flat(b, player)) <= REACH + length;
            for (; next <= walked + length; next += step, foot = -foot) {
                if (!joined || !near) {
                    continue;
                }
                double t = (next - walked) / length;
                Vec3 at = a.lerp(b, t);
                float closeness = (float) Mth.clamp((REACH - flat(at, player)) / (REACH - FULL), 0, 1);
                boolean tracks = kind == WaypointTrace.Kind.TRACKS;
                float freshness = tracks ? OLDEST + (1 - OLDEST) * (float) ((i - 1 + t) / (points.size() - 1)) : 1f;
                if (scent && closeness > 0 && RANDOM.nextFloat() < STRAND_RATE * rate / RATE * closeness * freshness) {
                    ScentStrands.start(points, next, 0.7 + RANDOM.nextDouble() * 0.5 * size / 0.6, rgb);
                }
                float chance = (scent ? WISP_RATE * rate / RATE : rate) * closeness * freshness;
                if (closeness <= 0 || RANDOM.nextFloat() >= chance) {
                    continue;
                }
                double heading = Math.atan2(b.z - a.z, b.x - a.x);
                if (scent) {
                    Surface under = surface(level, at.x, at.y, at.z);
                    double speed = WISP_SPEED * (0.8 + 0.4 * RANDOM.nextDouble());
                    level.addAlwaysVisibleParticle(ColorParticleOption.create(WaypointParticles.WISP.get(), 0xFF000000 | rgb),
                            at.x + (RANDOM.nextDouble() - 0.5) * 0.4, (under != null ? under.y() : at.y) + 0.6 + RANDOM.nextDouble() * 0.7 * size / 0.6,
                            at.z + (RANDOM.nextDouble() - 0.5) * 0.4, Math.cos(heading) * speed, 0.002, Math.sin(heading) * speed);
                    continue;
                }
                // Set to its side of the way, then onto the surface under it; off soft ground at the side (the edge of a
                // path), on the way itself, where the creature walked.
                double x = at.x - Math.sin(heading) * 0.16 * size * foot;
                double z = at.z + Math.cos(heading) * 0.16 * size * foot;
                Surface ground = surface(level, x, at.y, z);
                if (tracks && (ground == null || !ground.soft())) {
                    x = at.x;
                    z = at.z;
                    ground = surface(level, x, at.y, z);
                }
                if (ground == null || tracks && !ground.soft()) {
                    continue;
                }
                double spread = 0.12 * size;
                level.addAlwaysVisibleParticle(ColorParticleOption.create(WaypointParticles.TRAIL.get(), 0xFF000000 | rgb),
                        x + (RANDOM.nextDouble() - 0.5) * spread, ground.y() + 0.05, z + (RANDOM.nextDouble() - 0.5) * spread, 0, 0, 0);
            }
            walked += length;
        }
    }

    private static double flat(Vec3 point, Vec3 player) {
        return Math.hypot(point.x - player.x, point.z - player.z);
    }

    /** The top of what a step stands on, and whether that keeps tracks. */
    private record Surface(double y, boolean soft) {}

    /**
     * The surface under a step: of the tops between {@link #SEARCH_DOWN} below {@code y} and {@link #SEARCH_UP} above
     * it that something can stand on (a block's collision shape; a snow layer by its layers) with room above, the one
     * nearest {@code y}. Soft when the block is in {@link #TAKES_TRACKS}, or is a thin cover over such a block. Null
     * when there is none: no mote rather than one in the air.
     */
    private static @Nullable Surface surface(ClientLevel level, double x, double y, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        Surface best = null;
        double bestOff = Double.MAX_VALUE;
        for (int by = Mth.floor(y + SEARCH_UP); by >= Mth.floor(y - SEARCH_DOWN); by--) {
            BlockPos pos = new BlockPos(bx, by, bz);
            BlockState state = level.getBlockState(pos);
            double top;
            if (state.getBlock() instanceof SnowLayerBlock) {
                top = by + state.getValue(SnowLayerBlock.LAYERS) / 8.0;
            } else {
                VoxelShape shape = state.getCollisionShape(level, pos);
                if (shape.isEmpty()) {
                    continue;
                }
                top = by + shape.max(Direction.Axis.Y);
            }
            double off = Math.abs(top - y);
            if (top > y + SEARCH_UP || top < y - SEARCH_DOWN || off >= bestOff) {
                continue;
            }
            BlockPos above = BlockPos.containing(x, top + 0.01, z);
            if (!above.equals(pos) && !level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
                continue;
            }
            boolean soft = state.is(TAKES_TRACKS) || top - by <= THIN && level.getBlockState(pos.below()).is(TAKES_TRACKS);
            best = new Surface(top, soft);
            bestOff = off;
        }
        return best;
    }

}
