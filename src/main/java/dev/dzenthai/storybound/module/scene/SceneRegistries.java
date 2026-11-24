package dev.dzenthai.storybound.module.scene;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import net.neoforged.bus.api.IEventBus;

public final class SceneRegistries {
    /** Server only: a client gets a scene as it plays, its places worked out for that player. */
    public static final DefinitionType<SceneDefinition> SCENES = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("scene"), "storybound/scenes", SceneDefinition.CODEC));

    private SceneRegistries() {}

    public static void register(IEventBus modBus) {
        SceneLogic.REGISTER_ACTIONS.register(modBus);
        SceneLogic.REGISTER_CONDITIONS.register(modBus);
        SceneLogic.REGISTER_TRIGGERS.register(modBus);
    }
}
