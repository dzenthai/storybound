package dev.dzenthai.storybound.module.dialogue;

import java.util.Optional;

/**
 * The pause before an NPC answers a chosen option, shown as animated dots. Clients compute it from the
 * option's translated text (only they know its length); the server only extends timed lines by the maximum.
 */
public final class ThinkTime {
    /** No pause: not an answer to an option, or the option sets {@code "think": 0}. */
    public static final int NONE = -1;
    /** Computed by the client from the length of the player's line. */
    public static final int AUTO = 0;
    public static final int MIN_MILLIS = 1500;
    public static final int MAX_MILLIS = 4000;

    private ThinkTime() {}

    /** About 1 s plus 60 ms per character, between 1.5 and 4 seconds. */
    public static int forText(int characters) {
        return Math.clamp(1000L + 60L * characters, MIN_MILLIS, MAX_MILLIS);
    }

    /** {@link #NONE}, {@link #AUTO} or a fixed pause in milliseconds, from an option's {@code think} field. */
    public static int encode(Optional<Float> seconds) {
        if (seconds.isEmpty()) {
            return AUTO;
        }
        int millis = Math.round(seconds.get() * 1000);
        return millis <= 0 ? NONE : millis;
    }

    /** The pause for a code from {@link #encode}, given the player's line length for {@link #AUTO}. */
    public static int resolve(int code, int playerTextLength) {
        return code == AUTO ? forText(playerTextLength) : Math.max(0, code);
    }

    /** "." / ".." / "..." cycling every 0.4 s. */
    public static String dots(long elapsedMillis) {
        return ".".repeat((int) (elapsedMillis / 400 % 3) + 1);
    }
}
