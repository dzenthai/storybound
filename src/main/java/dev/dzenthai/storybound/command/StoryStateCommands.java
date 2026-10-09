package dev.dzenthai.storybound.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.state.FlagDeclaration;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.StoryValues;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /storybound flag|var ...}. The scope is optional and defaults to the flag's declared scope.
 * Shared scopes are written once, no matter how many players the selector matches.
 */
final class StoryStateCommands {
    private static final SuggestionProvider<CommandSourceStack> KEYS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(CoreDefinitions.FLAGS.serverStore().all().keySet(), builder);
    private static final SuggestionProvider<CommandSourceStack> SCOPES = (context, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(StoryScope.values()).map(StoryScope::getSerializedName), builder);

    private StoryStateCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> flag() {
        return Commands.literal("flag")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("get").then(targets().then(withScope(key(), StoryStateCommands::get))))
                .then(Commands.literal("set").then(targets().then(key().then(withScope(
                        Commands.argument("value", BoolArgumentType.bool()),
                        (context, scope) -> set(context, scope, new StoryValue.Bool(BoolArgumentType.getBool(context, "value"))))))))
                .then(Commands.literal("clear").then(targets().then(withScope(key(), StoryStateCommands::clear))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> var() {
        return Commands.literal("var")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("get").then(targets().then(withScope(key(), StoryStateCommands::get))))
                .then(Commands.literal("set").then(targets().then(key().then(withScope(
                        Commands.argument("value", StringArgumentType.string()),
                        (context, scope) -> set(context, scope, parseValue(context)))))))
                .then(Commands.literal("add").then(targets().then(key().then(withScope(
                        Commands.argument("amount", IntegerArgumentType.integer()), StoryStateCommands::add)))))
                .then(Commands.literal("clear").then(targets().then(withScope(key(), StoryStateCommands::clear))));
    }

    private static int get(CommandContext<CommandSourceStack> context, @Nullable StoryScope scopeArg) throws CommandSyntaxException {
        ResourceLocation key = ResourceLocationArgument.getId(context, "key");
        StoryScope scope = scopeOrDeclared(scopeArg, key);
        int count = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(context, "targets")) {
            Optional<StoryValue> value = StoryState.get(player, scope, key);
            context.getSource().sendSuccess(() -> value
                    .map(present -> Component.translatable("commands.storybound.value.get",
                            key.toString(), scope.getSerializedName(), player.getDisplayName(), StoryValues.display(present)))
                    .orElseGet(() -> Component.translatable("commands.storybound.value.unset",
                            key.toString(), scope.getSerializedName(), player.getDisplayName())), false);
            count++;
        }
        return count;
    }

    private static int set(CommandContext<CommandSourceStack> context, @Nullable StoryScope scopeArg, StoryValue value) throws CommandSyntaxException {
        ResourceLocation key = ResourceLocationArgument.getId(context, "key");
        StoryScope scope = scopeOrDeclared(scopeArg, key);
        Set<Slot> done = new HashSet<>();
        for (ServerPlayer player : EntityArgument.getPlayers(context, "targets")) {
            if (done.add(new Slot(scope, StoryState.owner(player, scope)))) {
                try {
                    StoryState.set(player, scope, key, value);
                } catch (StoryException e) {
                    throw fail(e);
                }
                context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.value.set",
                        key.toString(), scope.getSerializedName(), player.getDisplayName(), StoryValues.display(value)), true);
            }
        }
        return done.size();
    }

    private static int add(CommandContext<CommandSourceStack> context, @Nullable StoryScope scopeArg) throws CommandSyntaxException {
        ResourceLocation key = ResourceLocationArgument.getId(context, "key");
        StoryScope scope = scopeOrDeclared(scopeArg, key);
        int amount = IntegerArgumentType.getInteger(context, "amount");
        Set<Slot> done = new HashSet<>();
        for (ServerPlayer player : EntityArgument.getPlayers(context, "targets")) {
            if (done.add(new Slot(scope, StoryState.owner(player, scope)))) {
                int result;
                try {
                    result = StoryState.add(player, scope, key, amount);
                } catch (StoryException e) {
                    throw fail(e);
                }
                context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.value.set",
                        key.toString(), scope.getSerializedName(), player.getDisplayName(), String.valueOf(result)), true);
            }
        }
        return done.size();
    }

    private static int clear(CommandContext<CommandSourceStack> context, @Nullable StoryScope scopeArg) throws CommandSyntaxException {
        ResourceLocation key = ResourceLocationArgument.getId(context, "key");
        StoryScope scope = scopeOrDeclared(scopeArg, key);
        Set<Slot> done = new HashSet<>();
        for (ServerPlayer player : EntityArgument.getPlayers(context, "targets")) {
            if (done.add(new Slot(scope, StoryState.owner(player, scope)))) {
                try {
                    StoryState.clear(player, scope, key);
                } catch (StoryException e) {
                    throw fail(e);
                }
                context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.value.cleared",
                        key.toString(), scope.getSerializedName(), player.getDisplayName()), true);
            }
        }
        return done.size();
    }

    /** Parsed by the declared type; undeclared values become a number, true/false or a string. */
    private static StoryValue parseValue(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation key = ResourceLocationArgument.getId(context, "key");
        String raw = StringArgumentType.getString(context, "value");
        FlagDeclaration declaration = CoreDefinitions.FLAGS.serverStore().getOrNull(key);
        if (declaration == null) {
            if (raw.equals("true") || raw.equals("false")) {
                return new StoryValue.Bool(Boolean.parseBoolean(raw));
            }
            try {
                return new StoryValue.Int(Integer.parseInt(raw));
            } catch (NumberFormatException e) {
                return new StoryValue.Str(raw);
            }
        }
        return switch (declaration.type()) {
            case BOOL -> {
                if (!raw.equals("true") && !raw.equals("false")) {
                    throw badValue(raw, declaration);
                }
                yield new StoryValue.Bool(Boolean.parseBoolean(raw));
            }
            case INT -> {
                try {
                    yield new StoryValue.Int(Integer.parseInt(raw));
                } catch (NumberFormatException e) {
                    throw badValue(raw, declaration);
                }
            }
            case STRING -> new StoryValue.Str(raw);
        };
    }

    private static StoryScope scopeOrDeclared(@Nullable StoryScope scope, ResourceLocation key) {
        return scope != null ? scope : StoryState.declaredScope(key);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> targets() {
        return Commands.argument("targets", EntityArgument.players());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> key() {
        return Commands.argument("key", ResourceLocationArgument.id()).suggests(KEYS);
    }

    /** The node runs as is or with an optional trailing scope. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T withScope(T node, ScopedCommand command) {
        return node.executes(context -> command.run(context, null))
                .then(Commands.argument("scope", StringArgumentType.word()).suggests(SCOPES)
                        .executes(context -> command.run(context, parseScope(context))));
    }

    private static StoryScope parseScope(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(context, "scope");
        for (StoryScope scope : StoryScope.values()) {
            if (scope.getSerializedName().equals(raw)) {
                return scope;
            }
        }
        throw new SimpleCommandExceptionType(Component.translatable("commands.storybound.value.bad_scope", raw)).create();
    }

    private static CommandSyntaxException badValue(String raw, FlagDeclaration declaration) {
        return new SimpleCommandExceptionType(Component.translatable("commands.storybound.value.bad_value",
                raw, declaration.type().getSerializedName())).create();
    }

    static CommandSyntaxException fail(StoryException e) {
        return new SimpleCommandExceptionType(e.component()).create();
    }

    @FunctionalInterface
    private interface ScopedCommand {
        int run(CommandContext<CommandSourceStack> context, @Nullable StoryScope scope) throws CommandSyntaxException;
    }

    private record Slot(StoryScope scope, @Nullable UUID owner) {}
}
