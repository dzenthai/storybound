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
    private static @Nullable Matrix4f view;
    private static @Nullable Matrix4f projection;
    private static Vec3 camera = Vec3.ZERO;

    private ScreenProjection() {}

    /** Keeps the camera matrices of the frame; the HUD is drawn right after the level. */
    public static void capture(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            view = new Matrix4f(event.getModelViewMatrix());
            projection = new Matrix4f(event.getProjectionMatrix());
            camera = event.getCamera().getPosition();
        }
    }

    /** Where the camera was in the frame. */
    public static Vec3 camera() {
        return camera;
    }

    /** The point on a HUD of that size, or null before the first frame and for a point behind the camera. */
    public static float @Nullable [] project(Vec3 point, int width, int height) {
        if (view == null || projection == null) {
            return null;
        }
        Vector4f eye = new Vector4f((float) (point.x - camera.x), (float) (point.y - camera.y), (float) (point.z - camera.z), 1f);
        view.transform(eye);
        Vector4f clip = projection.transform(new Vector4f(eye));
        if (clip.w <= 0.05f) {
            return null;
        }
        return new float[]{(clip.x / clip.w * 0.5f + 0.5f) * width, (0.5f - clip.y / clip.w * 0.5f) * height};
    }
}
