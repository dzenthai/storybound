package dev.dzenthai.storybound.core.logic;

import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/** A server-side check shared by story events, dialogues, quests, codex and waypoints. */
public interface Condition {
    ConditionType<?> type();

    boolean test(StoryContext context);

    /** Reports story value keys this condition reads, so the validator can flag undeclared ones. */
    default void collectFlags(Consumer<ResourceLocation> sink) {}
}
