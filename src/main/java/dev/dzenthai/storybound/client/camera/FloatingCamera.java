package dev.dzenthai.storybound.client.camera;

import net.minecraft.util.Mth;

/**
 * The floating camera of a dialogue shot: while a line plays, moving the mouse turns the frame a little, left and right
 * as the mouse goes, up and down inverted; left alone it drifts slowly back, and while the answers are shown it returns
 * to the shot's own aim. Only the aim turns, never the position, so the wall checks of the shot still hold. Pure
 * arithmetic, fed once a frame by {@link DialogueCamera}.
 * <p>
 * As smooth as it can be: the mouse moves an aim that slows down towards its limits instead of stopping at them
 * ({@code tanh}), and the frame follows that aim on a critically damped spring ({@link #smoothDamp}), so it never
 * starts or stops with a jerk, whatever the mouse does, the frame rate or a hitch.
 */
final class FloatingCamera {
    /** How far the frame may turn from the shot's aim, in degrees: approached ever more slowly, never reached. */
    static final float MAX_YAW = 2.5f;
    static final float MAX_PITCH = 1.5f;
    /** How far past the soft limit's knee the mouse may push: tanh(1.5) is about 0.9 of the limit. */
    static final float PUSH_LIMIT = 1.5f;
    /** Degrees per pixel of mouse movement, near the centre (less towards the limits). */
    static final float DEGREES_PER_PIXEL = 0.012f;
    /** The spring's time to catch up with the aim: long, so the frame glides. */
    static final float FOLLOW_SECONDS = 0.45f;
    /** Without mouse movement for this long, the aim starts drifting back. */
    static final float IDLE_SECONDS = 0.6f;
    /** The slow drift back of the aim (time constant). */
    static final float DRIFT_SECONDS = 2.5f;
    /** While the answers are shown, the aim goes back within about three times this; the spring keeps it smooth. */
    static final float CENTRE_SECONDS = 0.25f;
    /** A longer frame (a hitch) counts as this. */
    static final float MAX_STEP_SECONDS = 0.1f;

    /** Where the mouse has pushed the aim, before the soft limit; degrees near the centre. */
    private float pushYaw;
    private float pushPitch;
    private float yaw;
    private float pitch;
    private float yawSpeed;
    private float pitchSpeed;
    private float idle;

    /**
     * @param dx   mouse movement to the right since the last frame, pixels
     * @param dy   mouse movement down since the last frame, pixels
     * @param dt   seconds since the last frame
     * @param free a line plays and the mouse may turn the frame; false while the answers are shown
     */
    void update(double dx, double dy, float dt, boolean free) {
        float step = Mth.clamp(dt, 0f, MAX_STEP_SECONDS);
        if (step <= 0f) {
            return;
        }
        if (free && (dx != 0 || dy != 0)) {
            pushYaw = push(pushYaw, (float) dx * DEGREES_PER_PIXEL, MAX_YAW);
            // Inverted, as in The Witcher: the mouse going up (dy < 0) turns the frame down (a larger pitch looks lower).
            pushPitch = push(pushPitch, (float) -dy * DEGREES_PER_PIXEL, MAX_PITCH);
            idle = 0f;
        } else {
            idle += step;
        }
        if (!free) {
            pushYaw = decay(pushYaw, step, CENTRE_SECONDS);
            pushPitch = decay(pushPitch, step, CENTRE_SECONDS);
        } else if (idle > IDLE_SECONDS) {
            pushYaw = decay(pushYaw, step, DRIFT_SECONDS);
            pushPitch = decay(pushPitch, step, DRIFT_SECONDS);
        }
        float[] y = smoothDamp(yaw, aim(pushYaw, MAX_YAW), yawSpeed, step);
        yaw = y[0];
        yawSpeed = y[1];
        float[] p = smoothDamp(pitch, aim(pushPitch, MAX_PITCH), pitchSpeed, step);
        pitch = p[0];
        pitchSpeed = p[1];
    }

    void reset() {
        pushYaw = 0f;
        pushPitch = 0f;
        yaw = 0f;
        pitch = 0f;
        yawSpeed = 0f;
        pitchSpeed = 0f;
        idle = 0f;
    }

    /** Degrees added to the shot's yaw now. */
    float yaw() {
        return yaw;
    }

    /** Degrees added to the shot's pitch now. */
    float pitch() {
        return pitch;
    }

    /** The aim for a push: the push itself near the centre, ever closer to the limit beyond it, never past it. */
    static float aim(float push, float max) {
        return max * (float) Math.tanh(push / max);
    }

    /**
     * The push moved by the mouse, held within {@link #PUSH_LIMIT} times the limit (where the aim is at 90% of it), so
     * pushing on against the limit stores no travel that the mouse would have to unwind before the frame comes back.
     */
    private static float push(float push, float delta, float max) {
        return Mth.clamp(push + delta, -max * PUSH_LIMIT, max * PUSH_LIMIT);
    }

    private static float decay(float value, float dt, float seconds) {
        return value * (float) Math.exp(-dt / seconds);
    }

    /**
     * A critically damped spring towards the target (the well-known "smooth damp" of Game Programming Gems 4): no
     * overshoot, a smooth start and stop, stable for any step. Returns the new value and speed.
     */
    static float[] smoothDamp(float current, float target, float speed, float dt) {
        float omega = 2f / FOLLOW_SECONDS;
        float x = omega * dt;
        float exp = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x);
        float change = current - target;
        float temp = (speed + omega * change) * dt;
        float newSpeed = (speed - omega * temp) * exp;
        float value = target + (change + temp) * exp;
        return new float[] {value, newSpeed};
    }
}
