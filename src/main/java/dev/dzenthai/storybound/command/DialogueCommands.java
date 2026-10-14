package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** {@code /storybound dialogue start <targets> <dialogue> [speaker]} and {@code stop <targets>}. */
final class DialogueCommands {
    private static final SuggestionProvider<CommandSourceStack> DIALOGUES = (context, builder) ->
            SharedSuggestionProvider.suggestResource(DialogueRegistries.DIALOGUES.serverStore().all().keySet(), builder);

    private DialogueCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> dialogue() {
        return Commands.literal("dialogue")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("start").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("dialogue", ResourceLocationArgument.id()).suggests(DIALOGUES)
                                .executes(context -> start(context, null))
                                .then(Commands.argument("speaker", EntityArgument.entity())
                                        .executes(context -> start(context, EntityArgument.getEntity(context, "speaker")))))))
                .then(Commands.literal("stop").then(Commands.argument("targets", EntityArgument.players()).executes(DialogueCommands::stop)));
    }

    private static int start(CommandContext<CommandSourceStack> context, @Nullable Entity speaker) throws CommandSyntaxException {
        ResourceLocation dialogue = ResourceLocationArgument.getId(context, "dialogue");
        if (!DialogueRegistries.DIALOGUES.serverStore().contains(dialogue)) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.dialogue.unknown", dialogue.toString())).create();
        }
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        int started = 0;
        for (ServerPlayer player : targets) {
            if (Dialogues.start(player, dialogue, speaker)) {
                started++;
            }
        }
        if (started == 0) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.dialogue.cannot_start", dialogue.toString())).create();
        }
        int total = started;
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.dialogue.started", dialogue.toString(), total), true);
        return started;
    }

    private static int stop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int stopped = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(context, "targets")) {
            if (Dialogues.interrupt(player, EndReason.STOPPED)) {
                stopped++;
            }
        }
        int total = stopped;
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.dialogue.stopped", total), true);
        return stopped;
    }
}
