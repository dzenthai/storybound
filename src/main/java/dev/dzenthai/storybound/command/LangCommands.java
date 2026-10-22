package dev.dzenthai.storybound.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.ProjectSettings;
import dev.dzenthai.storybound.core.dev.LangExtract;
import java.io.IOException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * {@code /storybound lang extract [language]} moves texts written in the story's JSON to the lang file and reloads;
 * {@code /storybound lang preview [language]} only says what would change. The language defaults to the project's
 * source language. See {@link LangExtract}.
 */
final class LangCommands {
    private LangCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> lang() {
        return Commands.literal("lang")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("extract")
                        .executes(context -> extract(context, null, true))
                        .then(Commands.argument("language", StringArgumentType.word()).executes(context -> extract(context, StringArgumentType.getString(context, "language"), true))))
                .then(Commands.literal("preview")
                        .executes(context -> extract(context, null, false))
                        .then(Commands.argument("language", StringArgumentType.word()).executes(context -> extract(context, StringArgumentType.getString(context, "language"), false))));
    }

    private static int extract(CommandContext<CommandSourceStack> context, String language, boolean write) throws CommandSyntaxException {
        String target = language != null ? language : ProjectSettings.load(ConfigStorySource.root()).sourceLanguage();
        LangExtract.Result result;
        try {
            result = LangExtract.run(target, write);
        } catch (IOException | IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.storybound.lang.failed", e.getMessage())).create();
        }
        if (result.moved() == 0 && result.fallbacks() == 0) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.lang.nothing"), false);
            return 0;
        }
        String files = String.join(", ", result.files());
        context.getSource().sendSuccess(() -> Component.translatable(write ? "commands.storybound.lang.extracted" : "commands.storybound.lang.preview",
                result.moved(), result.language(), result.fallbacks(), files.isEmpty() ? "—" : files), true);
        if (write) {
            MinecraftServer server = context.getSource().getServer();
            server.reloadResources(server.getPackRepository().getSelectedIds());
        }
        return result.moved() + result.fallbacks();
    }
}
