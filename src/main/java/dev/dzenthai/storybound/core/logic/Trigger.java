package dev.dzenthai.storybound.core.logic;

/**
 * Something that happens in the game and can start story logic.
 *
 * @param <E> the event payload this trigger type receives, e.g. the killed entity
 */
public interface Trigger<E> {
    TriggerType<?, E> type();

    boolean matches(StoryContext context, E event);

    /** Reports the ids this trigger refers to (flags, documents), for validation. */
    default void collectReferences(References references) {}
}
