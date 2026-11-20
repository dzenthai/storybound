package dev.dzenthai.storybound.module.music;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import net.neoforged.bus.api.IEventBus;

public final class MusicRegistries {
    /** Server only: the server works out each player's track and names it to the client. */
    public static final DefinitionType<MusicDefinition> MUSIC = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("music"), "storybound/music", MusicDefinition.CODEC));

    private MusicRegistries() {}

    public static void register(IEventBus gameBus) {
        StoryMusic.register(gameBus);
    }
}
