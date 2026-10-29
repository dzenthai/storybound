package dev.dzenthai.storybound.client.waypoint;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.compat.cutscene.CutsceneClient;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The edge of search areas (waypoints with a radius and {@code show_ring}) as a thin line on the ground, in the
 * waypoint's colour (the tracked quest's gold), fading out towards the ends. Only the part of the circle within
 * {@link #REACH} blocks of the player is drawn: a large area is never drawn whole, and near it only that part is seen.
 * The line follows the terrain (water included); where a chunk is not loaded it is left out. A player well under the
 * surface (in a cave, an area that is a dungeon) gets the line at their own height, which the surface would hide.
 * Drawn with depth, so walls and hills cover it as they cover the world.
 */
public final class AreaRings {
    /** How far from the player the edge is drawn, in blocks. */
    private static final double REACH = 48;
    /** The length of one piece of the line, in blocks. */
    private static final double STEP = 1;
    /** A player this far under the surface is underground: the line goes at their height. */
    private static final int UNDERGROUND = 10;
    private static final float ALPHA = 0.8f;
    private static final float DIMMED_ALPHA = 0.4f;
    private static final float INSIDE_ALPHA = 0.5f;
    /** The most pieces one circle is cut into, however large: enough for a smooth radius of about 500 blocks. */
    private static final int MAX_PIECES = 3600;

    private AreaRings() {}

    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || minecraft.level == null || minecraft.player == null
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
        VertexConsumer lines = null;
        for (ClientWaypoint waypoint : ClientWaypoints.shown()) {
            if (waypoint.radius() <= 0 || !waypoint.showRing() || !waypoint.dimension().equals(level.dimension().location())) {
                continue;
            }
            Vec3 center = center(level, waypoint, partialTick);
            double horizontal = Math.hypot(player.x - center.x, player.z - center.z);
            // Only while the edge passes within reach of the player.
            if (Math.abs(horizontal - waypoint.radius()) > REACH) {
                continue;
            }
            ClientWaypoints.Display display = ClientWaypoints.display(waypoint);
            float alpha = display == ClientWaypoints.Display.DIMMED ? DIMMED_ALPHA : ALPHA;
            if (horizontal <= waypoint.radius()) {
                alpha *= INSIDE_ALPHA;
            }
            int rgb = display == ClientWaypoints.Display.TRACKED ? WaypointStyles.INSTANCE.get().trackedColor() : waypoint.color();
            if (lines == null) {
                lines = buffers.getBuffer(RenderType.lines());
            }
            drawEdge(level, pose, lines, camera, player, center, waypoint.radius(), rgb, alpha);
        }
        if (lines != null) {
            buffers.endBatch(RenderType.lines());
        }
    }

    /** At the entity it follows, smoothly, while the client has it; else where the server says. */
    private static Vec3 center(ClientLevel level, ClientWaypoint waypoint, float partialTick) {
        Entity entity = waypoint.entity() == ClientWaypoint.NO_ENTITY ? null : level.getEntity(waypoint.entity());
        return entity != null && !entity.isRemoved() ? entity.getPosition(partialTick) : waypoint.pos();
    }

    private static void drawEdge(ClientLevel level, PoseStack pose, VertexConsumer lines, Vec3 camera, Vec3 player, Vec3 center,
                                 double radius, int rgb, float alpha) {
        int pieces = Mth.clamp((int) Math.ceil(Mth.TWO_PI * radius / STEP), 16, MAX_PIECES);
        Double underground = underground(level, player);
        Vec3 previous = null;
        float previousFade = 0;
        for (int i = 0; i <= pieces; i++) {
            double angle = Mth.TWO_PI * i / pieces;
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;
            double distance = Math.hypot(x - player.x, z - player.z);
            Double y = distance > REACH ? null : underground != null ? underground : ground(level, x, z);
            if (y == null) {
                previous = null;
                continue;
            }
            Vec3 point = new Vec3(x, y, z);
            float fade = alpha * (float) Mth.clamp(1 - distance / REACH, 0, 1);
            if (previous != null) {
                piece(pose, lines, camera, previous, point, rgb, previousFade, fade);
            }
            previous = point;
            previousFade = fade;
        }
    }

    /** Just above the surface (water counts), or null where the chunk is not loaded. */
    private static Double ground(ClientLevel level, double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (!level.hasChunk(SectionPos.blockToSectionCoord(bx), SectionPos.blockToSectionCoord(bz))) {
            return null;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) + 0.05;
    }

    /** The player's height when they are well under the surface where they stand, else null. */
    private static Double underground(ClientLevel level, Vec3 player) {
        Double surface = ground(level, player.x, player.z);
        return surface != null && player.y < surface - UNDERGROUND ? player.y + 0.05 : null;
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
