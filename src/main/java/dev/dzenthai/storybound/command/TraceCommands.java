package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /storybound trace on [players] | off | status}: writes what the story logic does for the players (everyone
 * without them) to {@code logs/storybound-trace.log}, see {@link StoryTrace}.
 */
final class TraceCommands {
    private TraceCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> trace() {
        return Commands.literal("trace")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("on")
                        .executes(context -> on(context, List.of()))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> on(context, EntityArgument.getPlayers(context, "targets")))))
                .then(Commands.literal("off").executes(TraceCommands::off))
                .then(Commands.literal("status").executes(TraceCommands::status));
    }

    private static int on(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        Set<UUID> ids = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : targets) {
            ids.add(player.getUUID());
            names.add(player.getGameProfile().getName());
        }
        StoryTrace.enable(context.getSource().getServer(), ids, names);
        String file = StoryTrace.file(context.getSource().getServer()).toString();
        context.getSource().sendSuccess(() -> names.isEmpty()
                ? Component.translatable("commands.storybound.trace.on_all", file)
                : Component.translatable("commands.storybound.trace.on", String.join(", ", names), file), true);
        return 1;
    }

    private static int off(CommandContext<CommandSourceStack> context) {
        StoryTrace.disable();
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.trace.off"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        String file = StoryTrace.file(context.getSource().getServer()).toString();
        Component text;
        if (!StoryTrace.enabled()) {
            text = Component.translatable("commands.storybound.trace.status_off");
        } else if (StoryTrace.players().isEmpty()) {
            text = Component.translatable("commands.storybound.trace.on_all", file);
        } else {
            List<String> names = new ArrayList<>();
            for (UUID id : StoryTrace.players()) {
                ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayer(id);
                names.add(player != null ? player.getGameProfile().getName() : id.toString());
            }
            text = Component.translatable("commands.storybound.trace.on", String.join(", ", names), file);
        }
        context.getSource().sendSuccess(() -> text, false);
        return StoryTrace.enabled() ? 1 : 0;
    }
}
