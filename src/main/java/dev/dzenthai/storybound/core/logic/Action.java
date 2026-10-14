package dev.dzenthai.storybound.core.logic;

import dev.dzenthai.storybound.core.StoryException;

/** A server-side effect run by story events, dialogue nodes and quest transitions. */
public interface Action {
    ActionType<?> type();

    void run(StoryContext context) throws StoryException;

    /** Reports the ids this action refers to (flags, documents), for validation. */
    default void collectReferences(References references) {}
}
