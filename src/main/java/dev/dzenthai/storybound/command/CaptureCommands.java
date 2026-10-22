package dev.dzenthai.storybound.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.dzenthai.storybound.core.dev.StoryCaptures;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /storybound capture pos <name>} (where the player stands and what they look at) and
 * {@code /storybound capture entity <name> [entity]} (the given entity, else the one looked at), written to
 * {@code config/storybound/captures.json}, see {@link StoryCaptures}.
 */
final class CaptureCommands {
    private CaptureCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> capture() {
        return Commands.literal("capture")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("pos").then(Commands.argument("name", StringArgumentType.word()).executes(CaptureCommands::position)))
                .then(Commands.literal("entity").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> entity(context, null))
                        .then(Commands.argument("entity", EntityArgument.entity())
                                .executes(context -> entity(context, EntityArgument.getEntity(context, "entity"))))));
    }

    private static int position(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return save(context, StoryCaptures.position(player));
    }

    private static int entity(CommandContext<CommandSourceStack> context, @Nullable Entity given) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Entity entity = given != null ? given : StoryCaptures.lookedAt(player);
        if (entity == null) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.storybound.capture.no_entity")).create();
        }
        return save(context, StoryCaptures.entity(player, entity));
    }

    private static int save(CommandContext<CommandSourceStack> context, JsonObject capture) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        Path file;
        try {
            file = StoryCaptures.save(name, capture);
        } catch (IOException | RuntimeException e) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.storybound.dump.failed", e.getMessage())).create();
        }
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.capture.written", name, file.toString()), false);
        return 1;
    }
}
