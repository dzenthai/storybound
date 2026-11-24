package dev.dzenthai.storybound.client.camera;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.scene.ClientScene;
import dev.dzenthai.storybound.mixin.client.CameraAccessor;
import dev.dzenthai.storybound.module.scene.SceneDefinition;
import dev.dzenthai.storybound.module.scene.ScenePath;
import dev.dzenthai.storybound.network.ScenePayloads;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jetbrains.annotations.Nullable;

/**
 * A scene's camera: each frame, the shot of the scene's time, flown from its first point to its last by its ease,
 * looking at its point, at its entity (followed as it moves) or along its way. A shot that eases in glides from where
 * the last one ended. Over the dialogue camera, which a scene never meets (a scene waits for a talk to end). The HUD
 * is hidden but for the titles and the screen's fade; the hand and the block outline too. Camera mods step aside as
 * for a dialogue.
 */
public final class SceneCamera {
    /** How long a shot that eases in glides from the last one, in ticks. */
    private static final double GLIDE = 10;
    private static final ResourceLocation FADE_LAYER = Storybound.id("screen_fade");

    /** The editor's camera view: where the camera is this frame (the partial tick given), or null while it is closed. */
    private static java.util.function.@Nullable Function<Float, Pose> preview;
    /** Active camera FOV set by the current frame's pose (0 when using the player's own FOV). */
    private static float activeFov;

    private SceneCamera() {}

    /** The editor's camera view takes the frame; null gives it back. */
    public static void preview(java.util.function.@Nullable Function<Float, Pose> source) {
        if (source != null && preview == null) {
            CameraIntegrations.pause();
        } else if (source == null && preview != null) {
            CameraIntegrations.resume();
            activeFov = 0f;
        }
        preview = source;
    }

    private static boolean framing() {
        return ClientScene.framing() || preview != null;
    }

    public static void begin() {
        CameraIntegrations.pause();
    }

    public static void end() {
        activeFov = 0f;
        CameraIntegrations.resume();
    }

    /** Places the frame for the scene; false when no scene plays and the camera is someone else's. */
    public static boolean apply(Camera camera, float partialTick) {
        if (preview != null && !ClientScene.framing()) {
            Pose pose = preview.apply(partialTick);
            if (pose == null) {
                activeFov = 0f;
                return false;
            }
            activeFov = pose.fov();
            CameraAccessor accessor = (CameraAccessor) camera;
            accessor.storybound$setDetached(true);
            accessor.storybound$setRotation(pose.yaw(), pose.pitch(), 0f);
            accessor.storybound$setPosition(pose.pos());
            return true;
        }
        if (!ClientScene.framing()) {
            activeFov = 0f;
            return false;
        }
        List<ScenePayloads.Shot> shots = ClientScene.shots();
        if (shots.isEmpty()) {
            activeFov = 0f;
            return false;
        }
        double time = ClientScene.time(partialTick);
        int index = 0;
        while (index < shots.size() - 1 && time >= shots.get(index + 1).start()) {
            index++;
        }
        ScenePayloads.Shot shot = shots.get(index);
        Pose pose = pose(shot, (time - shot.start()) / shot.ticks(), partialTick);
        double into = time - shot.start();
        if (index > 0 && shot.transition() == SceneDefinition.Transition.EASE.ordinal() && into < GLIDE) {
            ScenePayloads.Shot last = shots.get(index - 1);
            Pose from = pose(last, 1, partialTick);
            pose = from.toward(pose, SceneDefinition.Ease.IN_OUT.apply(into / GLIDE));
        }
        activeFov = pose.fov();
        CameraAccessor accessor = (CameraAccessor) camera;
        accessor.storybound$setDetached(true);
        accessor.storybound$setRotation(pose.yaw(), pose.pitch(), 0f);
        accessor.storybound$setPosition(pose.pos());
        return true;
    }

    public record Pose(Vec3 pos, float yaw, float pitch, float fov) {
        public Pose(Vec3 pos, float yaw, float pitch) {
            this(pos, yaw, pitch, 0f);
        }

        public Pose withFov(float fov) {
            return new Pose(pos, yaw, pitch, fov);
        }

        public Pose toward(Pose other, double share) {
            float blendedFov = 0f;
            if (fov > 0f || other.fov > 0f) {
                Minecraft minecraft = Minecraft.getInstance();
                float fallback = minecraft != null && minecraft.options != null ? minecraft.options.fov().get() : 70f;
                float a = fov > 0f ? fov : fallback;
                float b = other.fov > 0f ? other.fov : fallback;
                blendedFov = (float) Mth.lerp(share, a, b);
            }
            return new Pose(pos.lerp(other.pos, share), (float) (yaw + Mth.wrapDegrees(other.yaw - yaw) * share),
                    (float) Mth.lerp(share, pitch, other.pitch), blendedFov);
        }
    }

    /** Where the camera is at {@code t} (0 to 1) of the shot, and where it looks. */
    public static Pose pose(ScenePayloads.Shot shot, double t, float partialTick) {
        SceneDefinition.Ease[] eases = SceneDefinition.Ease.values();
        double gone = eases[Math.max(0, Math.min(eases.length - 1, shot.ease()))].apply(t);
        ScenePath path = ScenePath.of(shot.from(), shot.via(), shot.to());
        Vec3 pos = path.pointAt(gone);
        Vec3 target = shot.lookPoint();
        if (shot.look() == ScenePayloads.LOOK_ENTITY) {
            Entity entity = entity(shot.lookEntity());
            if (entity != null) {
                target = entity.getEyePosition(partialTick);
            }
        } else if (shot.look() == ScenePayloads.LOOK_PATH) {
            Vec3 way = path.tangentAt(gone);
            if (way.lengthSqr() > 1.0e-6) {
                target = pos.add(way);
            }
        }
        return facing(pos, target).withFov(shot.fov());
    }

    /** The yaw and pitch that look from {@code pos} at {@code target}, as Minecraft turns the camera. */
    public static Pose facing(Vec3 pos, Vec3 target) {
        double dx = target.x - pos.x;
        double dy = target.y - pos.y;
        double dz = target.z - pos.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0e-6 && Math.abs(dy) < 1.0e-6) {
            return new Pose(pos, 0, 0);
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        return new Pose(pos, yaw, pitch);
    }

    private static @Nullable Entity entity(int id) {
        Minecraft minecraft = Minecraft.getInstance();
        return id >= 0 && minecraft.level != null ? minecraft.level.getEntity(id) : null;
    }

    /** Only the titles and the screen's fade show over a scene. */
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (framing() && !event.getName().equals(VanillaGuiLayers.TITLE) && !event.getName().equals(FADE_LAYER)) {
            event.setCanceled(true);
        }
    }

    public static void onRenderHand(RenderHandEvent event) {
        if (framing()) {
            event.setCanceled(true);
        }
    }

    /** Applies the shot's custom FOV while a scene or the camera preview frames the view. */
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov() || !framing()) {
            return;
        }
        if (activeFov > 0f) {
            event.setFOV(activeFov);
        }
    }

    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        if (framing()) {
            event.setCanceled(true);
        }
    }
}
