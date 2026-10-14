package dev.dzenthai.storybound.core.logic;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Receives the ids a condition, action or trigger refers to, so validators can report
 * undeclared flags, unknown documents and quests. Override only what you need.
 */
public interface References {
    default void flag(ResourceLocation key) {}

    default void document(ResourceLocation id) {}

    /** @param stage a stage the reference names, or null for the quest as a whole */
    default void quest(ResourceLocation id, @Nullable String stage) {}
}
