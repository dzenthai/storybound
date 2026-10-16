package dev.dzenthai.storybound.client.camera;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.client.camera.CameraShots.Framing;
import dev.dzenthai.storybound.client.camera.CameraShots.Pose;
import dev.dzenthai.storybound.mixin.client.CameraAccessor;
import dev.dzenthai.storybound.module.dialogue.CameraCue;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The cinematic camera of the dialogue screen: shot-reverse-shot between the player and the NPC. It only overrides
 * where the frame is drawn from (see {@code GameRendererMixin}); the vanilla camera type and options are never
 * touched, so nothing needs restoring after a crash. Any exception turns it off for the rest of the game session
 * and the dialogue goes on in first person.
 */
public final class DialogueCamera {
    private static final long EASE_MILLIS = 400;
    /** How long a framed shot may wait for the render hook before the camera gives up. */
    private static final long HOOK_GRACE_MILLIS = 2000;

    private static boolean broken;
    private static long framedAt;
    private static long appliedAt;
    /** A shot has been framed in this dialogue: other camera mods are paused until it ends. */
    private static boolean running;
    /** The side of the axis between the speakers, chosen with the first shot and kept (the 180° rule); 0 before that. */
    private static int side;
    private static @Nullable Shot shot;
    private static @Nullable Pose easeFrom;
    private static long easeStart;

    private DialogueCamera() {}

    /** @param pose null when the shot is played in first person */
    private record Shot(int focus, int listener, CameraCue.Shot type, @Nullable Pose pose) {}

    /** A shot is on screen: the HUD, the hand and the block outline are hidden. */
    public static boolean isFraming() {
        return shot != null && shot.pose() != null;
    }

    /** @param speaker the speaking entity's id, or -1 for a speaker without an entity */
    public static void onLine(CameraCue cue, int speaker) {
        try {
            cue(cue, speaker);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /** A choice shows the player, over the partner's shoulder. */
    public static void onChoices(CameraCue cue) {
        LocalPlayer player = Minecraft.getInstance().player;
        onLine(cue, player != null ? player.getId() : -1);
    }

    public static void stop() {
        if (running) {
            running = false;
            CameraIntegrations.resume();
        }
        shot = null;
        easeFrom = null;
        side = 0;
    }

    /** Called right after the vanilla camera is set up for a frame. */
    public static void apply(Camera camera) {
        if (shot == null) {
            return;
        }
        appliedAt = Util.getMillis();
        try {
            Pose pose = pose();
            if (pose == null) {
                return;
            }
            CameraAccessor accessor = (CameraAccessor) camera;
            accessor.storybound$setDetached(true);
            accessor.storybound$setRotation(pose.yaw(), pose.pitch(), 0f);
            accessor.storybound$setPosition(pose.pos());
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /**
     * The render hook is injected with {@code require = 0}, so a mod that rewrites {@code GameRenderer.renderLevel}
     * leaves it silently unapplied. Then the HUD would hide while the view stays put: turn the camera off instead.
     */
    public static void tick() {
        if (isFraming() && !broken && Util.getMillis() - framedAt > HOOK_GRACE_MILLIS && appliedAt < framedAt) {
            Storybound.LOGGER.warn("Storybound: the dialogue camera's render hook never ran (another mod may replace GameRenderer.renderLevel); the camera is off");
            broken = true;
            stop();
        }
    }

    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (isFraming()) {
            event.setCanceled(true);
        }
    }

    /** The shot's lens: narrower than the player's, so the speaker has the same size in every shot. */
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov() || shot == null) {
            return;
        }
        try {
            Pose pose = pose();
            if (pose != null && pose.fov() > 0) {
                event.setFOV(pose.fov());
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    public static void onRenderHand(RenderHandEvent event) {
        if (isFraming()) {
            event.setCanceled(true);
        }
    }

    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        if (isFraming()) {
            event.setCanceled(true);
        }
    }

    private static void cue(CameraCue cue, int speaker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!allowed(cue) || player == null || level == null) {
            stop();
            return;
        }
        Entity partner = level.getEntity(cue.partner());
        if (partner == null) {
            stop();
            return;
        }
        Entity focus;
        Entity listener;
        CameraCue.Shot type = cue.shot();
        if (speaker == partner.getId()) {
            focus = partner;
            listener = player;
        } else if (speaker == player.getId()) {
            focus = player;
            listener = partner;
        } else if (shot != null) {
            // A narrator has no face to show: the current shot stays.
            return;
        } else {
            focus = partner;
            listener = player;
            if (type == CameraCue.Shot.AUTO) {
                type = CameraCue.Shot.TWO_SHOT;
            }
        }
        if (shot != null && shot.focus() == focus.getId() && shot.listener() == listener.getId() && shot.type() == type) {
            return;
        }
        Pose previous = pose();
        Pose pose = null;
        if (type != CameraCue.Shot.FIRST_PERSON) {
            if (!running) {
                // Face the partner first, so the reverse shot shows the player looking at them.
                player.lookAt(EntityAnchorArgument.Anchor.EYES, partner.getEyePosition());
            }
            double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
            Framing framing = CameraShots.frame(type, focus.getEyePosition(), listener.getEyePosition(),
                    player.getEyePosition(), partner.getEyePosition(), side == 0 ? 1 : side, aspect, obstacles(level));
            if (framing != null) {
                pose = framing.pose();
                if (side == 0) {
                    side = framing.side();
                }
            }
        }
        if (pose != null && !running) {
            running = true;
            framedAt = Util.getMillis();
            CameraIntegrations.pause();
        }
        easeFrom = cue.transition() == CameraCue.Transition.EASE && previous != null && pose != null ? previous : null;
        easeStart = Util.getMillis();
        shot = new Shot(focus.getId(), listener.getId(), type, pose);
    }

    private static boolean allowed(CameraCue cue) {
        return cue.enabled() && !broken && StoryboundClientConfig.INSTANCE.cameraEnabled.get() && !CameraIntegrations.blocked();
    }

    private static CameraShots.Obstacles obstacles(ClientLevel level) {
        return (from, to) -> level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getLocation();
    }

    /** The pose on screen now, part way through an {@code ease} transition. */
    private static @Nullable Pose pose() {
        Shot current = shot;
        if (current == null || current.pose() == null) {
            return null;
        }
        Pose from = easeFrom;
        if (from == null) {
            return current.pose();
        }
        float t = (Util.getMillis() - easeStart) / (float) EASE_MILLIS;
        if (t >= 1f) {
            easeFrom = null;
            return current.pose();
        }
        float s = t * t * (3f - 2f * t);
        Pose to = current.pose();
        Vec3 pos = from.pos().lerp(to.pos(), s);
        float playerFov = Minecraft.getInstance().options.fov().get();
        float fov = Mth.lerp(s, from.fov() > 0 ? from.fov() : playerFov, to.fov() > 0 ? to.fov() : playerFov);
        return new Pose(pos, Mth.rotLerp(s, from.yaw(), to.yaw()), Mth.lerp(s, from.pitch(), to.pitch()), fov);
    }

    private static void fail(Throwable problem) {
        Storybound.LOGGER.error("Storybound: the dialogue camera failed and is off until the game restarts", problem);
        broken = true;
        stop();
    }
}
