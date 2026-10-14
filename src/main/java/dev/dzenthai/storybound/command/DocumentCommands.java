package dev.dzenthai.storybound.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.Documents;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** {@code /storybound document give|open|mark_read <targets> <document>}. */
final class DocumentCommands {
    private static final SuggestionProvider<CommandSourceStack> DOCUMENTS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(DocumentRegistries.DOCUMENTS.serverStore().all().keySet(), builder);

    private DocumentCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> document() {
        return Commands.literal("document")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("give").then(targets().then(documentArgument()
                        .executes(context -> give(context, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(context -> give(context, IntegerArgumentType.getInteger(context, "count")))))))
                .then(Commands.literal("open").then(targets().then(documentArgument()
                        .executes(context -> read(context, true, "commands.storybound.document.opened")))))
                .then(Commands.literal("mark_read").then(targets().then(documentArgument()
                        .executes(context -> read(context, false, "commands.storybound.document.marked")))));
    }

    private static int give(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
        ResourceLocation document = requireDocument(context);
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        targets.forEach(player -> Documents.give(player, document, count));
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.document.given", document.toString(), targets.size()), true);
        return targets.size();
    }

    /** {@code open} shows the screen; {@code mark_read} records the read silently. Both run {@code on_read} on the first read. */
    private static int read(CommandContext<CommandSourceStack> context, boolean open, String message) throws CommandSyntaxException {
        ResourceLocation document = requireDocument(context);
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        targets.forEach(player -> Documents.read(player, document, open));
        context.getSource().sendSuccess(() -> Component.translatable(message, document.toString(), targets.size()), true);
        return targets.size();
    }

    private static ResourceLocation requireDocument(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation document = ResourceLocationArgument.getId(context, "document");
        if (!DocumentRegistries.DOCUMENTS.serverStore().contains(document)) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.document.unknown", document.toString())).create();
        }
        return document;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> targets() {
        return Commands.argument("targets", EntityArgument.players());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> documentArgument() {
        return Commands.argument("document", ResourceLocationArgument.id()).suggests(DOCUMENTS);
    }
}
