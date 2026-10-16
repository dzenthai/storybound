package dev.dzenthai.storybound.client.waypoint;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The thin light beam over waypoints that have {@code beam} on: a slim beacon beam in the waypoint's colour,
 * from the ground under the waypoint (like a beacon, whatever the waypoint's height) up to the build limit.
 * Everything else about waypoints is shown by map mods.
 */
public final class WaypointBeams {
    private static final float RADIUS = 0.05f;
    private static final float GLOW_RADIUS = 0.11f;
    /** Untracked quests' beams, when shown dimmed, are thinner still. */
    private static final float DIMMED_SCALE = 0.5f;

    private WaypointBeams() {}

    /** The surface under the waypoint; its own height when that chunk is not loaded. */
    private static int ground(Minecraft minecraft, Vec3 pos) {
        BlockPos block = BlockPos.containing(pos);
        if (minecraft.level.hasChunkAt(block)) {
            return minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, block.getX(), block.getZ());
        }
        return block.getY();
    }

    public static void render(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || minecraft.level == null || minecraft.options.hideGui
                || !StoryboundClientConfig.INSTANCE.waypointBeams.get() || !ClientModules.isEnabled("waypoints")) {
            return;
        }
        var dimension = minecraft.level.dimension().location();
        Vec3 camera = event.getCamera().getPosition();
        double maxDistance = minecraft.options.getEffectiveRenderDistance() * 16.0;
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long gameTime = minecraft.level.getGameTime();
        int top = minecraft.level.getMaxBuildHeight();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        boolean drawn = false;
        for (ClientWaypoint waypoint : ClientWaypoints.shown()) {
            if (!waypoint.beam() || !waypoint.dimension().equals(dimension)) {
                continue;
            }
            Vec3 pos = waypoint.pos();
            BlockPos block = BlockPos.containing(pos);
            // The block's corner: the beacon renderer adds half a block, so the beam stands in the block's centre like map icons.
            double dx = block.getX() - camera.x;
            double dz = block.getZ() - camera.z;
            int bottom = ground(minecraft, pos);
            int height = top - bottom;
            if (dx * dx + dz * dz > maxDistance * maxDistance || height <= 0) {
                continue;
            }
            float scale = ClientWaypoints.display(waypoint) == ClientWaypoints.Display.DIMMED ? DIMMED_SCALE : 1f;
            poseStack.pushPose();
            poseStack.translate(dx, bottom - camera.y, dz);
            BeaconRenderer.renderBeaconBeam(poseStack, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 1f, gameTime, 0, height,
                    0xFF000000 | waypoint.color(), RADIUS * scale, GLOW_RADIUS * scale);
            poseStack.popPose();
            drawn = true;
        }
        if (drawn) {
            buffers.endBatch();
        }
    }
}
