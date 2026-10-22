package dev.dzenthai.storybound.core.logic;

import org.jetbrains.annotations.Nullable;

/** A server-side check shared by story events, dialogues, quests, codex and waypoints. */
public interface Condition {
    ConditionType<?> type();

    boolean test(StoryContext context);

    /** Reports the ids this condition refers to (flags, documents), for validation. */
    default void collectReferences(References references) {}

    /**
     * Why the condition does not hold, for the trace ({@link StoryTrace}): what the player has instead, e.g.
     * {@code "mypack:gold is 3, wanted >= 10"}. Null when how it is written says enough.
     */
    default @Nullable String describe(StoryContext context) {
        return null;
    }
}
