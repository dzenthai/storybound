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
import net.minecraft.world.phys.Vec2;
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
    private static final SceneDefinition.Ease[] EASES = SceneDefinition.Ease.values();

    /** The editor's camera view: where the camera is this frame (the partial tick given), or null while it is closed. */
    private static java.util.function.@Nullable Function<Float, Pose> preview;
    /** Two-slot path cache so ease transitions between consecutive shots never thrash and rebuild ScenePath per frame. */
    private static @Nullable ScenePayloads.Shot cachedPathShotA;
    private static @Nullable ScenePath cachedPathA;
    private static @Nullable ScenePayloads.Shot cachedPathShotB;
    private static @Nullable ScenePath cachedPathB;
    /** Active camera FOV set by the current frame's pose (0 when using the player's own FOV). */
    private static float activeFov;
    /** Active camera roll (Dutch tilt / bank + shake) in degrees for the current frame. */
    private static float activeRoll;
    /** Spring-damped target for smooth entity tracking (eliminates step-up/jump jerks). */
    private static int trackedShotIndex = -1;
    private static int trackedEntityId = -1;
    private static @Nullable Vec3 smoothedTarget;
    private static long lastFrameNanos;
    /** Active cue shake impulse on the scene's clock. */
    private static float impulseIntensity;
    private static double impulseStart;
    private static double impulseDuration;

    private SceneCamera() {}

    /** The editor's camera view takes the frame; null gives it back. */
    public static void preview(java.util.function.@Nullable Function<Float, Pose> source) {
        if (source != null && preview == null) {
            CameraIntegrations.pause();
        } else if (source == null && preview != null) {
            CameraIntegrations.resume();
            activeFov = 0f;
            activeRoll = 0f;
        }
        preview = source;
    }

    private static boolean framing() {
        return ClientScene.framing() || preview != null;
    }

    public static void begin() {
        resetState();
        CameraIntegrations.pause();
    }

    public static void end() {
        activeFov = 0f;
        activeRoll = 0f;
        resetState();
        CameraIntegrations.resume();
    }

    private static void resetState() {
        trackedShotIndex = -1;
        trackedEntityId = -1;
        smoothedTarget = null;
        lastFrameNanos = 0L;
        impulseIntensity = 0f;
        impulseStart = 0.0;
        impulseDuration = 0.0;
        cachedPathShotA = null;
        cachedPathA = null;
        cachedPathShotB = null;
        cachedPathB = null;
    }

    private static ScenePath pathFor(ScenePayloads.Shot shot) {
        if (shot == cachedPathShotA && cachedPathA != null) {
            return cachedPathA;
        }
        if (shot == cachedPathShotB && cachedPathB != null) {
            return cachedPathB;
        }
        ScenePath built = ScenePath.of(shot.from(), shot.via(), shot.to());
        cachedPathShotB = cachedPathShotA;
        cachedPathB = cachedPathA;
        cachedPathShotA = shot;
        cachedPathA = built;
        return built;
    }

    /** Triggers a decaying camera-shake impulse (horn blast, heavy footstep, explosion) on the scene's clock. */
    public static void triggerShake(float intensity, int ticks) {
        if (intensity <= 0f || ticks <= 0) {
            return;
        }
        double now = ClientScene.time(0f);
        float current = currentImpulse(now);
        impulseIntensity = Math.max(current, intensity);
        impulseStart = now;
        impulseDuration = ticks;
    }

    private static float currentImpulse(double now) {
        if (impulseDuration <= 0.0 || impulseIntensity <= 0f || now < impulseStart) {
            return 0f;
        }
        double progress = (now - impulseStart) / impulseDuration;
        if (progress >= 1.0) {
            return 0f;
        }
        double decay = (1.0 - progress) * (1.0 - progress);
        return (float) (impulseIntensity * decay);
    }

    /** Places the frame for the scene; false when no scene plays and the camera is someone else's. */
    public static boolean apply(Camera camera, float partialTick) {
        if (preview != null && !ClientScene.framing()) {
            Pose pose = preview.apply(partialTick);
            if (pose == null) {
                activeFov = 0f;
                activeRoll = 0f;
                return false;
            }
            activeFov = pose.fov();
            activeRoll = pose.roll();
            CameraAccessor accessor = (CameraAccessor) camera;
            accessor.storybound$setDetached(true);
            accessor.storybound$setRotation(pose.yaw(), pose.pitch(), pose.roll());
            accessor.storybound$setPosition(pose.pos());
            return true;
        }
        if (!ClientScene.framing()) {
            activeFov = 0f;
            activeRoll = 0f;
            return false;
        }
        List<ScenePayloads.Shot> shots = ClientScene.shots();
        if (shots.isEmpty()) {
            activeFov = 0f;
            activeRoll = 0f;
            return false;
        }
        double time = ClientScene.time(partialTick);
        int index = 0;
        while (index < shots.size() - 1 && time >= shots.get(index + 1).start()) {
            index++;
        }
        long nowNanos = System.nanoTime();
        double dt = lastFrameNanos == 0L || Minecraft.getInstance().isPaused()
                ? 0.0
                : Math.min(0.1, (nowNanos - lastFrameNanos) / 1_000_000_000.0);
        lastFrameNanos = nowNanos;

        ScenePayloads.Shot shot = shots.get(index);
        Pose pose = livePose(shot, index, (time - shot.start()) / shot.ticks(), partialTick, dt);
        double into = time - shot.start();
        if (index > 0 && shot.transition() == SceneDefinition.Transition.EASE.ordinal() && into < GLIDE) {
            ScenePayloads.Shot last = shots.get(index - 1);
            Pose from = pose(last, 1, partialTick);
            pose = from.toward(pose, SceneDefinition.Ease.IN_OUT.apply(into / GLIDE));
        }
        // Subtle organic handheld breathing + decaying cue impact shake.
        double seconds = time / 20.0;
        float shakeYaw = 0f;
        float shakePitch = 0f;
        float shakeRoll = 0f;
        float breathing = shot.shake();
        if (breathing > 0f) {
            shakeYaw += (float) (breathing * (Math.sin(seconds * 1.7) * 0.55 + Math.sin(seconds * 4.1 + 1.3) * 0.25) * 1.6);
            shakePitch += (float) (breathing * (Math.sin(seconds * 1.3 + 0.7) * 0.50 + Math.sin(seconds * 3.7 + 2.1) * 0.25) * 1.2);
            shakeRoll += (float) (breathing * Math.sin(seconds * 1.1 + 2.4) * 0.65);
        }
        float trauma = currentImpulse(time);
        if (trauma > 0f) {
            shakeYaw += (float) (Math.sin(seconds * 23.0) * trauma * 3.4);
            shakePitch += (float) (Math.sin(seconds * 29.0 + 1.1) * trauma * 2.8);
            shakeRoll += (float) (Math.sin(seconds * 19.0 + 2.3) * trauma * 3.8);
        }
        activeFov = pose.fov();
        activeRoll = pose.roll() + shakeRoll;
        CameraAccessor accessor = (CameraAccessor) camera;
        accessor.storybound$setDetached(true);
        accessor.storybound$setRotation(pose.yaw() + shakeYaw, pose.pitch() + shakePitch, activeRoll);
        accessor.storybound$setPosition(pose.pos());
        return true;
    }

    public record Pose(Vec3 pos, float yaw, float pitch, float fov, float roll) {
        public Pose(Vec3 pos, float yaw, float pitch) {
            this(pos, yaw, pitch, 0f, 0f);
        }

        public Pose(Vec3 pos, float yaw, float pitch, float fov) {
            this(pos, yaw, pitch, fov, 0f);
        }

        public Pose withFov(float fov) {
            return new Pose(pos, yaw, pitch, fov, roll);
        }

        public Pose withRoll(float roll) {
            return new Pose(pos, yaw, pitch, fov, roll);
        }

        public Pose toward(Pose other, double share) {
            float blendedFov = 0f;
            if (fov > 0f || other.fov > 0f) {
                float fallback = defaultPlayerFov();
                float a = fov > 0f ? fov : fallback;
                float b = other.fov > 0f ? other.fov : fallback;
                blendedFov = (float) Mth.lerp(share, a, b);
            }
            return new Pose(pos.lerp(other.pos, share), (float) (yaw + Mth.wrapDegrees(other.yaw - yaw) * share),
                    (float) Mth.lerp(share, pitch, other.pitch), blendedFov, (float) Mth.lerp(share, roll, other.roll));
        }
    }

    /** Live pose during playback: applies spring-damped smoothing to entity look targets so step-ups never jerk the camera. */
    private static Pose livePose(ScenePayloads.Shot shot, int shotIndex, double t, float partialTick, double dt) {
        double gone = EASES[Math.max(0, Math.min(EASES.length - 1, shot.ease()))].apply(t);
        ScenePath path = pathFor(shot);
        Vec3 pos = path.pointAt(gone);
        Vec3 target = shot.lookPoint();
        if (shot.look() == ScenePayloads.LOOK_ENTITY) {
            Entity entity = entity(shot.lookEntity());
            if (entity != null) {
                Vec3 raw = entity.getEyePosition(partialTick);
                if (shotIndex == trackedShotIndex && shot.lookEntity() == trackedEntityId && smoothedTarget != null && dt > 0.0) {
                    double alphaXZ = 1.0 - Math.exp(-dt * 12.0);
                    double alphaY = 1.0 - Math.exp(-dt * 6.5);
                    smoothedTarget = new Vec3(
                            Mth.lerp(alphaXZ, smoothedTarget.x, raw.x),
                            Mth.lerp(alphaY, smoothedTarget.y, raw.y),
                            Mth.lerp(alphaXZ, smoothedTarget.z, raw.z));
                } else {
                    smoothedTarget = raw;
                    trackedShotIndex = shotIndex;
                    trackedEntityId = shot.lookEntity();
                }
                target = smoothedTarget;
            }
        } else {
            trackedShotIndex = shotIndex;
            trackedEntityId = -1;
            smoothedTarget = null;
            if (shot.look() == ScenePayloads.LOOK_PATH) {
                Vec3 way = path.tangentAt(gone);
                if (way.lengthSqr() > 1.0e-6) {
                    target = pos.add(way);
                }
            }
        }
        return composePose(pos, target, shot, path, gone);
    }

    /** Where the camera is at {@code t} (0 to 1) of the shot, and where it looks. */
    public static Pose pose(ScenePayloads.Shot shot, double t, float partialTick) {
        double gone = EASES[Math.max(0, Math.min(EASES.length - 1, shot.ease()))].apply(t);
        ScenePath path = pathFor(shot);
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
        return composePose(pos, target, shot, path, gone);
    }

    private static Pose composePose(Vec3 pos, Vec3 target, ScenePayloads.Shot shot, ScenePath path, double gone) {
        float fov = shot.fov();
        if (shot.fovTo() > 0f) {
            float startFov = fov > 0f ? fov : defaultPlayerFov();
            fov = (float) Mth.lerp(gone, startFov, shot.fovTo());
        }
        float roll = (float) Mth.lerp(gone, shot.roll(), shot.rollTo());
        if (shot.look() == ScenePayloads.LOOK_FREE && !shot.rotations().isEmpty()) {
            Vec2 angles = interpolateRotation(shot.rotations(), path, gone);
            return new Pose(pos, angles.x, angles.y, fov, roll);
        }
        Vec3 aimed = applyLookOffset(pos, target, shot.lookOffset());
        return facing(pos, aimed).withFov(fov).withRoll(roll);
    }

    /**
     * Smoothly interpolates per-control-point {@code [yaw, pitch]} angles along {@code path} at arc-length fraction
     * {@code gone} in {@code [0, 1]}, always turning yaw along the shortest arc.
     */
    public static Vec2 interpolateRotation(List<Vec2> rotations, ScenePath path, double gone) {
        int count = rotations.size();
        if (count == 0) {
            return Vec2.ZERO;
        }
        if (count == 1) {
            return rotations.getFirst();
        }
        double u = Math.clamp(gone, 0.0, 1.0);
        int seg = count - 2;
        for (int i = 0; i < count - 1; i++) {
            double u1 = i + 1 < path.controlCount() ? path.parameterOfControl(i + 1) : (i + 1) / (double) (count - 1);
            if (u <= u1 || i == count - 2) {
                seg = i;
                break;
            }
        }
        double u0 = seg < path.controlCount() ? path.parameterOfControl(seg) : seg / (double) (count - 1);
        double u1 = seg + 1 < path.controlCount() ? path.parameterOfControl(seg + 1) : (seg + 1) / (double) (count - 1);
        double span = u1 - u0;
        double s = span > 1.0e-6 ? Math.clamp((u - u0) / span, 0.0, 1.0) : 0.0;
        Vec2 a = rotations.get(seg);
        Vec2 b = rotations.get(seg + 1);
        float yaw = Mth.wrapDegrees((float) (a.x + Mth.wrapDegrees(b.x - a.x) * s));
        float pitch = (float) Mth.lerp(s, a.y, b.y);
        return new Vec2(yaw, pitch);
    }

    /** Shifts {@code target} by {@code [right, up, forward]} in the camera's horizontal view frame (Rule of Thirds / headroom). */
    public static Vec3 applyLookOffset(Vec3 pos, Vec3 target, Vec3 offset) {
        if (offset.lengthSqr() <= 1.0e-8) {
            return target;
        }
        Vec3 flat = new Vec3(target.x - pos.x, 0.0, target.z - pos.z);
        double len = flat.length();
        Vec3 forward = len > 1.0e-6 ? flat.scale(1.0 / len) : new Vec3(0.0, 0.0, 1.0);
        Vec3 right = forward.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
        return target.add(right.scale(offset.x)).add(0.0, offset.y, 0.0).add(forward.scale(offset.z));
    }

    private static float defaultPlayerFov() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.options != null ? minecraft.options.fov().get() : 70f;
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

    /** Ensures NeoForge's camera-angle hook preserves the scene's Dutch tilt / bank / shake roll. */
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (framing() && activeRoll != 0f) {
            event.setRoll(activeRoll);
        }
    }

    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        if (framing()) {
            event.setCanceled(true);
        }
    }
}
