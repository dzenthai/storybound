package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.dzenthai.storybound.core.dev.StoryDebug;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /storybound debug [player] | off}: a live panel of a player's story on the operator's own screen (see {@link StoryDebug}). */
final class DebugCommands {
    private DebugCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> debug() {
        return Commands.literal("debug")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> watch(context, context.getSource().getPlayerOrException()))
                .then(Commands.literal("off").executes(context -> {
                    StoryDebug.watch(context.getSource().getPlayerOrException(), null);
                    context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.debug.off"), false);
                    return 1;
                }))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> watch(context, EntityArgument.getPlayer(context, "player"))));
    }

    private static int watch(CommandContext<CommandSourceStack> context, ServerPlayer target) throws CommandSyntaxException {
        StoryDebug.watch(context.getSource().getPlayerOrException(), target);
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.debug.on", target.getDisplayName()), false);
        return 1;
    }
}
