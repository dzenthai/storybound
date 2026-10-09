package dev.dzenthai.storybound.core.logic;

import dev.dzenthai.storybound.core.StoryException;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/** A server-side effect run by story events, dialogue nodes and quest transitions. */
public interface Action {
    ActionType<?> type();

    void run(StoryContext context) throws StoryException;

    /** Reports story value keys this action writes, so the validator can flag undeclared ones. */
    default void collectFlags(Consumer<ResourceLocation> sink) {}
}
