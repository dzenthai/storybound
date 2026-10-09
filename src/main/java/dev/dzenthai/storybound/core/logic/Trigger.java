package dev.dzenthai.storybound.core.logic;

import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/**
 * Something that happens in the game and can start story logic.
 *
 * @param <E> the event payload this trigger type receives, e.g. the killed entity
 */
public interface Trigger<E> {
    TriggerType<?, E> type();

    boolean matches(StoryContext context, E event);

    /** Reports story value keys this trigger listens to, so the validator can flag undeclared ones. */
    default void collectFlags(Consumer<ResourceLocation> sink) {}
}
