package dev.dzenthai.storybound.core;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.state.FlagDeclaration;

public final class CoreDefinitions {
    /** Only declarations with {@code "sync": true} reach the client; the rest would be spoilers. */
    public static final DefinitionType<FlagDeclaration> FLAGS = DefinitionTypes.register(
            DefinitionType.synced(Storybound.id("flag"), "storybound/flags", FlagDeclaration.CODEC, FlagDeclaration::sync));

    /** Story events stay on the server: their conditions and actions would spoil the story. */
    public static final DefinitionType<StoryEvent> EVENTS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("event"), "storybound/events", StoryEvent.CODEC));

    private CoreDefinitions() {}

    /** Loads this class so definition types are registered before the first data load. */
    public static void bootstrap() {}
}
