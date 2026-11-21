package dev.dzenthai.storybound.core;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ChapterDefinition;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.DialogueLayout;
import dev.dzenthai.storybound.core.data.ScriptDefinition;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.state.FlagDeclaration;
import dev.dzenthai.storybound.core.state.Migration;
import dev.dzenthai.storybound.core.state.StoryMigrations;

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

    /**
     * Dev mode only: KubeJS script blocks, where KubeJS code enters the story, in {@code kubejs/} (the type keeps its id
     * {@code script}; the editor calls it "KubeJS script" and offers it only where KubeJS is installed).
     */
    public static final DefinitionType<ScriptDefinition> SCRIPTS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("script"), "storybound/kubejs", ScriptDefinition.CODEC));

    /** Scripts of vanilla commands, in {@code scripts/}, run by {@code run_script} (see {@link dev.dzenthai.storybound.core.data.VanillaScriptDefinition}). */
    public static final DefinitionType<dev.dzenthai.storybound.core.data.VanillaScriptDefinition> VANILLA_SCRIPTS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("vanilla_script"), "storybound/scripts", dev.dzenthai.storybound.core.data.VanillaScriptDefinition.CODEC));

    /** Dev mode only: where a dialogue's nodes are drawn, under the dialogue's id. */
    public static final DefinitionType<DialogueLayout> DIALOGUE_LAYOUTS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("dialogue_layout"), "storybound/dialogue_layouts", DialogueLayout.CODEC));

    /** Ids renamed since saves were made, carried over into saved progress (see {@link StoryMigrations}). */
    public static final DefinitionType<Migration> MIGRATIONS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("migration"), "storybound/migrations", Migration.CODEC));

    private CoreDefinitions() {}

    /** Loads this class so definition types are registered before the first data load. */
    public static void bootstrap() {}
}
