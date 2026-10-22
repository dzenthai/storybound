package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.dzenthai.storybound.core.dev.StoryDump;
import dev.dzenthai.storybound.core.dev.StoryExport;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Files for authors who read instead of watching: {@code /storybound dump <player>} (the player's story state, see
 * {@link StoryDump}) and {@code /storybound export} (every name a story can use, see {@link StoryExport}).
 */
final class DumpCommands {
    private DumpCommands() {}

    @FunctionalInterface
    private interface Writer {
        Path write() throws IOException;
    }

    static LiteralArgumentBuilder<CommandSourceStack> dump() {
        return Commands.literal("dump")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("target", EntityArgument.player()).executes(DumpCommands::dump));
    }

    static LiteralArgumentBuilder<CommandSourceStack> export() {
        return Commands.literal("export")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> {
                    Path file = write(() -> StoryExport.write(context.getSource().getServer()));
                    context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.export.written", file.toString()), false);
                    return 1;
                });
    }

    private static int dump(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "target");
        Path file = write(() -> StoryDump.write(player));
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.dump.written", player.getDisplayName(), file.toString()), false);
        return 1;
    }

    private static Path write(Writer writer) throws CommandSyntaxException {
        try {
            return writer.write();
        } catch (IOException e) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.storybound.dump.failed", e.getMessage())).create();
        }
    }
}
