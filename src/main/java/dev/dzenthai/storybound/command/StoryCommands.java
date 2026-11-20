package dev.dzenthai.storybound.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.StoryFolder;
import java.io.IOException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * {@code /storybound story install <id> [replace]}: puts a story bundled in the mod (the vanilla story) into the story
 * folder as a story of its own, then reloads. One already there stays, unless {@code replace}: then it goes to
 * {@code .deleted} first.
 */
public final class StoryCommands {
    private StoryCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> story() {
        return Commands.literal("story")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("install")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(StoryFolder.bundledStories(), builder))
                                .executes(context -> install(context, false))
                                .then(Commands.literal("replace").executes(context -> install(context, true)))));
    }

    private static int install(CommandContext<CommandSourceStack> context, boolean replace) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        if (!StoryFolder.bundledStories().contains(id)) {
            source.sendFailure(Component.translatable("commands.storybound.story.unknown", id, String.join(", ", StoryFolder.bundledStories())));
            return 0;
        }
        int written;
        try {
            written = StoryFolder.installStory(ConfigStorySource.root(), id, replace);
        } catch (IllegalStateException e) {
            source.sendFailure(Component.translatable("commands.storybound.story.exists", id));
            return 0;
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not install the story {}", id, e);
            source.sendFailure(Component.translatable("commands.storybound.story.failed", id, e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.storybound.story.installed", StoryFolder.bundledTitle(id), written), true);
        source.getServer().getCommands().performPrefixedCommand(source, "reload");
        return written;
    }
}
