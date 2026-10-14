package dev.dzenthai.storybound.core.logic;

/** A server-side check shared by story events, dialogues, quests, codex and waypoints. */
public interface Condition {
    ConditionType<?> type();

    boolean test(StoryContext context);

    /** Reports the ids this condition refers to (flags, documents), for validation. */
    default void collectReferences(References references) {}
}
