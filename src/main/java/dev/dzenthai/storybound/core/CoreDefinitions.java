package dev.dzenthai.storybound.core;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ChapterDefinition;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.DialogueLayout;
import dev.dzenthai.storybound.core.data.ScriptDefinition;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.state.FlagDeclaration;

public final class CoreDefinitions {
    /** Only declarations with {@code "sync": true} reach the client; the rest would be spoilers. */
    public static final DefinitionType<FlagDeclaration> FLAGS = DefinitionTypes.register(
            DefinitionType.synced(Storybound.id("flag"), "storybound/flags", FlagDeclaration.CODEC, FlagDeclaration::sync));

    /** Story events stay on the server: their conditions and actions would spoil the story. */
    public static final DefinitionType<StoryEvent> EVENTS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("event"), "storybound/events", StoryEvent.CODEC));

    /** Dev mode only: the story canvas's chapters. */
    public static final DefinitionType<ChapterDefinition> CHAPTERS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("chapter"), "storybound/chapters", ChapterDefinition.CODEC));

    /** Dev mode only: script blocks, where KubeJS code enters the story. */
    public static final DefinitionType<ScriptDefinition> SCRIPTS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("script"), "storybound/scripts", ScriptDefinition.CODEC));

    /** Dev mode only: where a dialogue's nodes are drawn, under the dialogue's id. */
    public static final DefinitionType<DialogueLayout> DIALOGUE_LAYOUTS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("dialogue_layout"), "storybound/dialogue_layouts", DialogueLayout.CODEC));

    private CoreDefinitions() {}

    /** Loads this class so definition types are registered before the first data load. */
    public static void bootstrap() {}
}
