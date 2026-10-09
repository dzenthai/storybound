package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** {@code /storybound trigger fire <id> <targets>}: fires a {@code custom} trigger, e.g. from a command block. */
final class TriggerCommands {
    private TriggerCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> trigger() {
        return Commands.literal("trigger")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("fire").then(Commands.argument("id", ResourceLocationArgument.id())
                        .then(Commands.argument("targets", EntityArgument.players()).executes(TriggerCommands::fire))));
    }

    private static int fire(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(context, "id");
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        for (ServerPlayer player : targets) {
            TriggerDispatcher.fireCustom(player, id);
        }
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.trigger.fired", id.toString(), targets.size()), true);
        return targets.size();
    }
}
