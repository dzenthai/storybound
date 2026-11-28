package dev.dzenthai.storybound.client;

import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Points of the world on the HUD: the camera matrices of the frame being drawn, kept after the level, project a point
 * onto the HUD's width and height. What is drawn this way stays out of the world's rendering, so shader packs, clouds
 * and fog do not touch it.
 */
public final class ScreenProjection {
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Vector4f SCRATCH = new Vector4f();
    private static boolean ready;
    private static Vec3 camera = Vec3.ZERO;

    private ScreenProjection() {}

    /** Keeps the camera matrices of the frame; the HUD is drawn right after the level. */
    public static void capture(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            VIEW.set(event.getModelViewMatrix());
            PROJECTION.set(event.getProjectionMatrix());
            camera = event.getCamera().getPosition();
            ready = true;
        }
    }

    /** Whether level camera matrices have been captured at least once. */
    public static boolean ready() {
        return ready;
    }

    /** Read-only access to the captured model-view matrix for HUD world projection on the render thread. */
    public static Matrix4f viewMatrix() {
        return VIEW;
    }

    /** Read-only access to the captured projection matrix for HUD world projection on the render thread. */
    public static Matrix4f projectionMatrix() {
        return PROJECTION;
    }

    /** Where the camera was in the frame. */
    public static Vec3 camera() {
        return camera;
    }

    /** The point on a HUD of that size, or null before the first frame and for a point behind the camera. */
    public static float @Nullable [] project(Vec3 point, int width, int height) {
        if (!ready) {
            return null;
        }
        SCRATCH.set((float) (point.x - camera.x), (float) (point.y - camera.y), (float) (point.z - camera.z), 1f);
        VIEW.transform(SCRATCH);
        PROJECTION.transform(SCRATCH);
        if (SCRATCH.w <= 0.05f) {
            return null;
        }
        return new float[]{(SCRATCH.x / SCRATCH.w * 0.5f + 0.5f) * width, (0.5f - SCRATCH.y / SCRATCH.w * 0.5f) * height};
    }
}
