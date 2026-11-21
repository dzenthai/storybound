package dev.dzenthai.storybound.client.camera;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.client.camera.CameraShots.Framing;
import dev.dzenthai.storybound.client.camera.CameraShots.Pose;
import dev.dzenthai.storybound.client.dialogue.DialogueScreen;
import dev.dzenthai.storybound.mixin.client.CameraAccessor;
import dev.dzenthai.storybound.mixin.client.MouseHandlerAccessor;
import dev.dzenthai.storybound.module.dialogue.CameraCue;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
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
import org.lwjgl.glfw.GLFW;

/**
 * The cinematic camera of the dialogue screen: shot-reverse-shot between the player and the NPC. It only overrides
 * where the frame is drawn from (see {@code GameRendererMixin}); the vanilla camera type and options are never
 * touched, so nothing needs restoring after a crash. Any exception turns it off for the rest of the game session
 * and the dialogue goes on in first person.
 */
public final class DialogueCamera {
    /** The reputation HUD layer (see {@code StoryboundClient}), shown through a shot. */
    private static final net.minecraft.resources.ResourceLocation REPUTATION_LAYER = Storybound.id("reputation");
    /** The screen fade goes on over a shot: a scene opens out of the black as the camera frames it. */
    private static final net.minecraft.resources.ResourceLocation FADE_LAYER = Storybound.id("screen_fade");
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
    /** The mouse turning the frame while a line plays. */
    private static final FloatingCamera FLOATING = new FloatingCamera();
    /** The answers are shown: the frame returns to the shot's aim and the cursor is back. */
    private static boolean choosing;
    /** The cursor is hidden and the mouse moves without bounds, as in the game: a line plays on the dialogue screen. */
    private static boolean cursorHidden;
    private static double lastMouseX;
    private static double lastMouseY;
    private static long lastFrameNanos;

    private DialogueCamera() {}

    /** @param pose null when the shot is played in first person */
    /** @param look what the shot looks at instead of the speakers, or {@link CameraCue.Look#NONE} */
    private record Shot(int focus, int listener, CameraCue.Shot type, @Nullable Pose pose, CameraCue.Look look) {}

    /** A shot is on screen: the HUD, the hand and the block outline are hidden. */
    public static boolean isFraming() {
        return shot != null && shot.pose() != null;
    }

    public static @Nullable CameraShots.ScreenPoint projectFocus(double aspect) {
        Shot current = shot;
        if (current == null || current.pose() == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return null;
        }
        Entity focus = level.getEntity(current.focus());
        Pose currentPose = pose();
        if (focus == null || currentPose == null) {
            return null;
        }
        return currentPose.project(focus.getEyePosition().subtract(0, 0.25, 0), aspect, minecraft.options.fov().get());
    }

    /** @param speaker the speaking entity's id, or -1 for a speaker without an entity */
    public static void onLine(CameraCue cue, int speaker) {
        choosing = false;
        safeCue(cue, speaker);
    }

    /** A choice shows the player, over the partner's shoulder; the frame glides back to its aim, the cursor comes back. */
    public static void onChoices(CameraCue cue) {
        choosing = true;
        LocalPlayer player = Minecraft.getInstance().player;
        safeCue(cue, player != null ? player.getId() : -1);
        updateCursor();
    }

    private static void safeCue(CameraCue cue, int speaker) {
        try {
            cue(cue, speaker);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    public static void stop() {
        if (running) {
            running = false;
            CameraIntegrations.resume();
        }
        shot = null;
        easeFrom = null;
        side = 0;
        choosing = false;
        FLOATING.reset();
        lastFrameNanos = 0;
        showCursor();
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
            drift();
            CameraAccessor accessor = (CameraAccessor) camera;
            accessor.storybound$setDetached(true);
            accessor.storybound$setRotation(pose.yaw() + FLOATING.yaw(), pose.pitch() + FLOATING.pitch(), 0f);
            accessor.storybound$setPosition(pose.pos());
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /** Feeds the frame's mouse movement to the floating camera: only while the cursor is hidden for a line. */
    private static void drift() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        MouseHandler mouse = Minecraft.getInstance().mouseHandler;
        double dx = cursorHidden ? mouse.xpos() - lastMouseX : 0;
        double dy = cursorHidden ? mouse.ypos() - lastMouseY : 0;
        lastMouseX = mouse.xpos();
        lastMouseY = mouse.ypos();
        FLOATING.update(dx, dy, dt, cursorHidden);
    }

    /**
     * Hides the cursor while a line plays on the dialogue screen with the floating camera on (the mouse then turns the
     * frame, unbounded by the window's edges, as in the game) and gives it back otherwise: for the answers, another
     * screen over it (a pause menu), the window losing focus, the dialogue's end. Checked every tick and at once when
     * the answers come.
     */
    private static void updateCursor() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean hide = isFraming() && !choosing && !broken && StoryboundClientConfig.INSTANCE.cameraFloating.get()
                && minecraft.screen instanceof DialogueScreen && minecraft.isWindowActive();
        if (hide && !cursorHidden) {
            Window window = minecraft.getWindow();
            InputConstants.grabOrReleaseMouse(window.getWindow(), GLFW.GLFW_CURSOR_DISABLED, minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
            cursorHidden = true;
            lastMouseX = minecraft.mouseHandler.xpos();
            lastMouseY = minecraft.mouseHandler.ypos();
        } else if (!hide) {
            showCursor();
        }
    }

    /** The cursor back in the middle of the window, as Minecraft's own release puts it; nothing when it is not hidden. */
    private static void showCursor() {
        if (!cursorHidden) {
            return;
        }
        cursorHidden = false;
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        double x = window.getScreenWidth() / 2.0;
        double y = window.getScreenHeight() / 2.0;
        MouseHandlerAccessor mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
        mouse.storybound$setXpos(x);
        mouse.storybound$setYpos(y);
        InputConstants.grabOrReleaseMouse(window.getWindow(), GLFW.GLFW_CURSOR_NORMAL, x, y);
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
        try {
            updateCursor();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /**
     * The HUD hides while a shot frames the talk, but for the standing with the NPC talked to, which is shown for the
     * very talk.
     */
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (isFraming() && !event.getName().equals(REPUTATION_LAYER) && !event.getName().equals(FADE_LAYER)) {
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
        if (cue.look().any() && lookCue(cue, player, level)) {
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
        if (shot != null && !shot.look().any() && shot.focus() == focus.getId() && shot.listener() == listener.getId() && shot.type() == type) {
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
        shot = new Shot(focus.getId(), listener.getId(), type, pose, CameraCue.Look.NONE);
    }

    /**
     * A line that looks at something else ({@code look_at}): framed by {@link CameraShots#lookAt}, an entity followed
     * frame by frame. False when it cannot be framed (gone, too close): the line gets its usual shot.
     */
    private static boolean lookCue(CameraCue cue, LocalPlayer player, ClientLevel level) {
        Vec3 target = lookTarget(cue.look(), level);
        if (target == null) {
            return false;
        }
        Pose pose = CameraShots.lookAt(player.getEyePosition(), target, side == 0 ? 1 : side, obstacles(level));
        if (pose == null) {
            return false;
        }
        if (shot != null && shot.look().equals(cue.look())) {
            return true;
        }
        Pose previous = pose();
        if (!running) {
            running = true;
            framedAt = Util.getMillis();
            CameraIntegrations.pause();
        }
        easeFrom = cue.transition() == CameraCue.Transition.EASE && previous != null ? previous : null;
        easeStart = Util.getMillis();
        shot = new Shot(-1, -1, cue.shot(), pose, cue.look());
        return true;
    }

    /** The point a look aims at: an entity's upper body, or the point as sent; null when the entity is not here. */
    private static @Nullable Vec3 lookTarget(CameraCue.Look look, ClientLevel level) {
        if (look.entity() >= 0) {
            Entity entity = level.getEntity(look.entity());
            return entity != null && entity.isAlive() ? entity.position().add(0, entity.getBbHeight() * 0.7, 0) : null;
        }
        return look.pos().orElse(null);
    }

    /** A look at an entity, framed again for where it is now; the shot as framed when the entity is gone. */
    private static Pose followed(Shot current) {
        Minecraft minecraft = Minecraft.getInstance();
        if (current.look().entity() < 0 || minecraft.player == null || minecraft.level == null) {
            return current.pose();
        }
        Vec3 target = lookTarget(current.look(), minecraft.level);
        Pose pose = target != null ? CameraShots.lookAt(minecraft.player.getEyePosition(), target, side == 0 ? 1 : side, obstacles(minecraft.level)) : null;
        return pose != null ? pose : current.pose();
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
        Pose to = followed(current);
        if (from == null) {
            return to;
        }
        float t = (Util.getMillis() - easeStart) / (float) EASE_MILLIS;
        if (t >= 1f) {
            easeFrom = null;
            return to;
        }
        float s = t * t * (3f - 2f * t);
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
