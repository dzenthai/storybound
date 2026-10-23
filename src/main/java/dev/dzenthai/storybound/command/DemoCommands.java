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

/**
 * {@code /storybound demo install}: copies the demo story into the story folder and reloads the data. A new story
 * folder has it already; this brings it back after it was deleted, or into a folder made before.
 */
final class DemoCommands {
    private DemoCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> demo() {
        return Commands.literal("demo")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("install").executes(DemoCommands::install));
    }

    private static int install(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int written;
        try {
            written = StoryFolder.installDemo(ConfigStorySource.root());
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not install the demo story", e);
            source.sendFailure(Component.translatable("commands.storybound.demo.failed", e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.storybound.demo.installed", written), true);
        source.getServer().getCommands().performPrefixedCommand(source, "reload");
        return written;
    }
}
