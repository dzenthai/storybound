package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.Trigger;

/**
 * A trigger together with what to do when it matches.
 *
 * @param name shown in logs, e.g. {@code story event mypack:village_burned}
 */
public record Subscription<E>(String name, Trigger<E> trigger, Listener listener) {
    @FunctionalInterface
    public interface Listener {
        void onTriggered(StoryContext context);
    }
}
