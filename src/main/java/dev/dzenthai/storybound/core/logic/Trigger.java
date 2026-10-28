package dev.dzenthai.storybound.core.logic;

/**
 * Something that happens in the game and can start story logic.
 *
 * <p>Many triggers announce a change of a state ("entered the village" is "is in the village" becoming true). Such a
 * trigger can say whether its state holds now ({@link #stateKind()}, {@link #holdsNow}), so whatever waits for it
 * (an objective, a quest start, a {@code once} story event) is not stuck when the change happened before it was
 * listening: before the quest reached the stage, while the player was away, before the file existed.
 *
 * @param <E> the event payload this trigger type receives, e.g. the killed entity
 */
public interface Trigger<E> {
    TriggerType<?, E> type();

    boolean matches(StoryContext context, E event);

    /** Reports the ids this trigger refers to (flags, documents), for validation. */
    default void collectReferences(References references) {}

    /** What kind of state this trigger announces; {@link StateKind#NONE} for a pure event (a kill, a line said). */
    default StateKind stateKind() {
        return StateKind.NONE;
    }

    /** Whether the state holds for the player now; only asked when {@link #stateKind()} is not {@link StateKind#NONE}. */
    default boolean holdsNow(StoryContext context) {
        return false;
    }

    enum StateKind {
        /** Only the moment counts: a kill, a dialogue ended. */
        NONE,
        /** Something true now that may become false again: being in a place, a flag's value, a quest's status. */
        CURRENT,
        /**
         * Something that, once it happened, stays done: a document read, a boss killed. A repeatable quest never
         * counts it from the past, or every repetition would be done at once.
         */
        HISTORY
    }
}
