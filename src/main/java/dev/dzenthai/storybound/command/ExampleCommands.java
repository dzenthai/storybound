package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.StoryFolder;
import java.io.IOException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /storybound example install}: copies the example story into the story folder and reloads the data. */
final class ExampleCommands {
    private ExampleCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> example() {
        return Commands.literal("example")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("install").executes(ExampleCommands::install));
    }

    private static int install(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int written;
        try {
            written = StoryFolder.installExample(ConfigStorySource.root());
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not install the example", e);
            source.sendFailure(Component.translatable("commands.storybound.example.failed", e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.storybound.example.installed", written), true);
        source.getServer().getCommands().performPrefixedCommand(source, "reload");
        return written;
    }
}
