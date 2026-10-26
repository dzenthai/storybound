package dev.dzenthai.storybound.client.menu;

/** Analytic critically damped motion preserves velocity when a pull reverses. */
public final class RibbonMotion {
    private double position;
    private double velocity;
    private float target;
    private long previous = -1;

    public float sample(float next, long now) {
        if (previous >= 0) {
            double seconds = Math.max(0, now - previous) / 1000.0;
            double offset = position - target;
            double slope = velocity + 36 * offset;
            double decay = Math.exp(-36 * seconds);
            position = target + (offset + slope * seconds) * decay;
            velocity = (velocity - 36 * slope * seconds) * decay;
            if (Math.abs(position - target) < 0.0001 && Math.abs(velocity) < 0.001) {
                position = target;
                velocity = 0;
            }
        }
        previous = now;
        target = next;
        return (float) position;
    }
}
