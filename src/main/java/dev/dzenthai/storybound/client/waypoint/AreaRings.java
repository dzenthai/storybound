package dev.dzenthai.storybound.client.waypoint;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.client.ShaderPacks;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.compat.cutscene.CutsceneClient;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The edge of search areas (waypoints with a radius and {@code show_ring}) as a line in the waypoint's colour (the
 * tracked quest's gold), fading out towards the ends, bolder than Minecraft's own lines, over a band one texture pixel
 * wide lying on that level, so it reads near the player as a stripe on the ground. Only the part of the
 * circle within {@link #REACH} blocks of the player is drawn: a large area is never drawn whole, and near it only that
 * part is seen. The line lies level at the player's own height, on the surface, in a cave or in the Nether alike,
 * rather than following the blocks: where the ground rises above it, the hill covers it; where the ground falls away,
 * it hangs in the air. Far above or below the area it fades out ({@link #heightShown}). Drawn with depth,
 * so walls and hills cover it as they cover the world.
 */
public final class AreaRings {
    /** How far from the player the edge is drawn, in blocks. */
    private static final double REACH = 48;
    /** The length of one piece of the line, in blocks. */
    private static final double STEP = 1;
    /** The line's width beside Minecraft's own lines (which grow with the window). */
    private static final float WIDTH = 2.5f;
    /** Leaving the ground by more than this (a fall, a ladder, flight), the line follows the player; a jump it ignores. */
    private static final double LEAVE_GROUND = 3;
    /** A change of height greater than this (a teleport) moves the line at once; a smaller one it glides to. */
    private static final double SNAP = 8;
    /** How quickly it glides to a new height, per second (about a tenth of a second). */
    private static final float GLIDE = 12f;
    /** Above the area's top ({@link #heightShown}), the line is whole this far, then fades out over as many again. */
    private static final double ABOVE_FULL = 16;
    private static final double ABOVE_FADE = 16;
    /**
     * Below the area's middle (its marker), the line is whole this far, enough for the foot of a tower with the marker
     * on top, then gone quickly over {@link #BELOW_FADE}: under a floating island, the area up there is not shown.
     */
    private static final double BELOW_FULL = 30;
    private static final double BELOW_FADE = 2;
    /**
     * The band under the line, in blocks: one texture pixel. Its width is the world's, so it is wide near the player
     * (looking down at it) and thin far off, where the line, a few screen pixels wide however far, still shows.
     */
    private static final double BAND = 1 / 16.0;
    /** How many points along the edge tell where the ground around the area lies. */
    private static final int EDGE_SAMPLES = 8;
    private static final float ALPHA = 0.8f;
    private static final float DIMMED_ALPHA = 0.4f;
    private static final float INSIDE_ALPHA = 0.5f;
    /** The most pieces one circle is cut into, however large: enough for a smooth radius of about 500 blocks. */
    private static final int MAX_PIECES = 3600;

    /** Lines as Minecraft draws them, {@link #WIDTH} times as wide. */
    private static final RenderType RING_LINES = RenderType.create("storybound_area_ring", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 1536, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()) {
                        @Override
                        public void setupRenderState() {
                            RenderSystem.lineWidth(Math.max(2.5f, Minecraft.getInstance().getWindow().getWidth() / 1920f * 2.5f) * WIDTH);
                        }
                    })
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    /**
     * See-through quads in a colour, unlit, not writing depth: the band under the line here, and a scent's strands
     * ({@link ScentStrands}).
     */
    static final RenderType RING_BAND = RenderType.create("storybound_area_band", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1536, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    /** One area's edge to draw this frame. */
    private record Edge(Vec3 center, double radius, int rgb, float alpha) {}

    /** Where the player last stood (or swam, climbed, flew), and the height the line lies at, gliding to it. */
    private static double standing = Double.NaN;
    private static double height = Double.NaN;
    private static long lastFrame;

    private AreaRings() {}

    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != ShaderPacks.worldOverlayStage() || ShaderPacks.renderingShadowPass()
                || minecraft.level == null || minecraft.player == null
                || minecraft.options.hideGui || !StoryboundClientConfig.INSTANCE.waypointAreaRings.get()
                || !ClientModules.isEnabled("waypoints") || CutsceneClient.playing()) {
            return;
        }
        ClientLevel level = minecraft.level;
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 player = minecraft.player.getPosition(partialTick);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        double y = height(minecraft.player, player);
        List<Edge> edges = new ArrayList<>();
        for (ClientWaypoint waypoint : ClientWaypoints.shown()) {
            if (waypoint.radius() <= 0 || !waypoint.showRing() || waypoint.isTrace() || !waypoint.dimension().equals(level.dimension().location())) {
                continue;
            }
            Vec3 center = center(level, waypoint, partialTick);
            double horizontal = Math.hypot(player.x - center.x, player.z - center.z);
            // Only while the edge passes within reach of the player.
            if (Math.abs(horizontal - waypoint.radius()) > REACH) {
                continue;
            }
            float upDown = heightShown(level, center, waypoint.radius(), y);
            if (upDown <= 0) {
                continue;
            }
            ClientWaypoints.Display display = ClientWaypoints.display(waypoint);
            float alpha = upDown * (display == ClientWaypoints.Display.DIMMED ? DIMMED_ALPHA : ALPHA);
            if (horizontal <= waypoint.radius()) {
                alpha *= INSIDE_ALPHA;
            }
            edges.add(new Edge(center, waypoint.radius(), MarkerContrast.readable(waypoint.color()), alpha));
        }
        if (edges.isEmpty()) {
            return;
        }
        // The bands first, then the lines over them; one batch each, as the buffer source keeps one batch at a time.
        VertexConsumer bands = buffers.getBuffer(RING_BAND);
        for (Edge edge : edges) {
            drawEdge(pose, bands, true, camera, player, y, edge);
        }
        buffers.endBatch(RING_BAND);
        VertexConsumer lines = buffers.getBuffer(RING_LINES);
        for (Edge edge : edges) {
            drawEdge(pose, lines, false, camera, player, y, edge);
        }
        buffers.endBatch(RING_LINES);
    }

    /**
     * How much of the area's edge shows at height {@code y}, 0 to 1; 1 is also where the marker says "Search the area".
     * Above, it is measured from the area's top: the higher of its middle (its marker) and the ground along its edge,
     * so a player on the surface over a dungeon still sees it ({@link #ABOVE_FULL}, {@link #ABOVE_FADE}). Below, from
     * the middle alone, so the ground far under a floating island does not count: whole down to {@link #BELOW_FULL},
     * then gone at once. Where there is a ceiling (the Nether) the ground says nothing, so only the middle counts; edge
     * points in chunks not loaded are left out.
     */
    static float heightShown(ClientLevel level, Vec3 center, double radius, double y) {
        if (y < center.y) {
            return (float) Mth.clamp((BELOW_FULL + BELOW_FADE - (center.y - y)) / BELOW_FADE, 0, 1);
        }
        double high = center.y;
        if (!level.dimensionType().hasCeiling()) {
            for (int i = 0; i < EDGE_SAMPLES; i++) {
                double angle = Mth.TWO_PI * i / EDGE_SAMPLES;
                int bx = Mth.floor(center.x + Math.cos(angle) * radius);
                int bz = Mth.floor(center.z + Math.sin(angle) * radius);
                if (level.hasChunk(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(bz))) {
                    high = Math.max(high, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz));
                }
            }
        }
        return (float) Mth.clamp(1 - (y - high - ABOVE_FULL) / ABOVE_FADE, 0, 1);
    }

    /** At the entity it follows, smoothly, while the client has it; else where the server says. */
    private static Vec3 center(ClientLevel level, ClientWaypoint waypoint, float partialTick) {
        Entity entity = waypoint.entity() == ClientWaypoint.NO_ENTITY ? null : level.getEntity(waypoint.entity());
        return entity != null && !entity.isRemoved() ? entity.getPosition(partialTick) : waypoint.pos();
    }

    /**
     * The height the line lies at: where the player stands, held through a jump, followed once they leave the ground by
     * more than {@link #LEAVE_GROUND} or while they swim, climb or fly; glided to, unless it jumps by more than
     * {@link #SNAP}.
     */
    private static double height(LocalPlayer entity, Vec3 player) {
        boolean held = entity.onGround() || entity.isInWater() || entity.onClimbable() || entity.getAbilities().flying || entity.isFallFlying();
        if (held || Double.isNaN(standing) || Math.abs(player.y - standing) > LEAVE_GROUND) {
            standing = player.y;
        }
        double target = standing + 0.05;
        long now = Util.getMillis();
        float seconds = Math.min(1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (Double.isNaN(height) || Math.abs(target - height) > SNAP) {
            height = target;
        } else {
            height += (target - height) * Math.min(1f, seconds * GLIDE);
        }
        return height;
    }

    /** The edge as the line, or as the band under it. */
    private static void drawEdge(PoseStack pose, VertexConsumer out, boolean band, Vec3 camera, Vec3 player, double y, Edge edge) {
        Vec3 center = edge.center();
        double radius = edge.radius();
        int rgb = edge.rgb();
        float alpha = edge.alpha();
        int pieces = Mth.clamp((int) Math.ceil(Mth.TWO_PI * radius / STEP), 16, MAX_PIECES);
        Vec3 previous = null;
        float previousFade = 0;
        for (int i = 0; i <= pieces; i++) {
            double angle = Mth.TWO_PI * i / pieces;
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;
            double distance = Math.hypot(x - player.x, z - player.z);
            if (distance > REACH) {
                previous = null;
                continue;
            }
            Vec3 point = new Vec3(x, y, z);
            float fade = alpha * (float) Mth.clamp(1 - distance / REACH, 0, 1);
            if (previous != null) {
                if (band) {
                    bandPiece(pose, out, camera, center, previous, point, rgb, previousFade, fade);
                } else {
                    piece(pose, out, camera, previous, point, rgb, previousFade, fade);
                }
            }
            previous = point;
            previousFade = fade;
        }
    }

    /** A piece of the band: a flat quad {@link #BAND} wide across the edge, from the middle outwards. */
    private static void bandPiece(PoseStack pose, VertexConsumer band, Vec3 camera, Vec3 center, Vec3 from, Vec3 to, int rgb,
                                  float fromAlpha, float toAlpha) {
        PoseStack.Pose last = pose.last();
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        Vec3[] corners = {across(center, from, -1), across(center, from, 1), across(center, to, 1), across(center, to, -1)};
        float[] alphas = {fromAlpha, fromAlpha, toAlpha, toAlpha};
        for (int i = 0; i < 4; i++) {
            band.addVertex(last, (float) (corners[i].x - camera.x), (float) (corners[i].y - camera.y), (float) (corners[i].z - camera.z))
                    .setColor(r, g, b, (int) (alphas[i] * 255));
        }
    }

    /** A point of the edge moved half the band's width towards the middle (-1) or away from it (1). */
    private static Vec3 across(Vec3 center, Vec3 point, int side) {
        double dx = point.x - center.x;
        double dz = point.z - center.z;
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-6) {
            return point;
        }
        double shift = side * BAND / 2 / length;
        return new Vec3(point.x + dx * shift, point.y, point.z + dz * shift);
    }

    private static void piece(PoseStack pose, VertexConsumer lines, Vec3 camera, Vec3 from, Vec3 to, int rgb, float fromAlpha, float toAlpha) {
        float nx = (float) (to.x - from.x);
        float ny = (float) (to.y - from.y);
        float nz = (float) (to.z - from.z);
        float length = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-4f) {
            return;
        }
        nx /= length;
        ny /= length;
        nz /= length;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        PoseStack.Pose last = pose.last();
        lines.addVertex(last, (float) (from.x - camera.x), (float) (from.y - camera.y), (float) (from.z - camera.z))
                .setColor(r, g, b, (int) (fromAlpha * 255)).setNormal(last, nx, ny, nz);
        lines.addVertex(last, (float) (to.x - camera.x), (float) (to.y - camera.y), (float) (to.z - camera.z))
                .setColor(r, g, b, (int) (toAlpha * 255)).setNormal(last, nx, ny, nz);
    }
}
