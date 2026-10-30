package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.Trigger;

/**
 * A trigger together with what to do when it matches.
 *
 * @param name   shown in logs, e.g. {@code story event mypack:village_burned}
 * @param active whether firing it for the player now could do anything (an objective of the stage they are at, not
 *               done yet, its conditions holding); asked only by detectors that show progress before firing
 *               ({@link ObservationTracker}), so a player looking at a zombie without the quest gets no bar
 */
public record Subscription<E>(String name, Trigger<E> trigger, Listener listener, Active active) {
    public Subscription(String name, Trigger<E> trigger, Listener listener) {
        this(name, trigger, listener, context -> true);
    }

    @FunctionalInterface
    public interface Listener {
        void onTriggered(StoryContext context);
    }

    @FunctionalInterface
    public interface Active {
        boolean test(StoryContext context);
    }
}
