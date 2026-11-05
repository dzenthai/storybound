package dev.dzenthai.storybound.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.StoryFolder;
import dev.dzenthai.storybound.core.dev.StoryDebug;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import java.io.IOException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /storybound debug [player] | off}: a live panel of a player's story on the operator's own screen (see
 * {@link StoryDebug}). {@code /storybound debug scenario <name> | clear}: a small scenario showing one mechanic, put in
 * the story folder and started next to the operator, or all of them taken out again (November 5, 2026; it replaced the
 * demo story, which started for every player who joined).
 */
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
                .then(Commands.literal("scenario")
                        .then(Commands.literal("clear").executes(DebugCommands::clear))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(StoryFolder.scenarios(), builder))
                                .executes(DebugCommands::scenario)))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> watch(context, EntityArgument.getPlayer(context, "player"))));
    }

    private static int watch(CommandContext<CommandSourceStack> context, ServerPlayer target) throws CommandSyntaxException {
        StoryDebug.watch(context.getSource().getPlayerOrException(), target);
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.debug.on", target.getDisplayName()), false);
        return 1;
    }

    /**
     * Installs the scenario, reloads, then fires the {@code custom} trigger {@code scenario:<name>} for the operator: the
     * scenario's own story event sets the scene around them. Its hint ({@code scenario.<name>.hint}) says what to do.
     */
    private static int scenario(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String name = StringArgumentType.getString(context, "name");
        if (!StoryFolder.scenarios().contains(name)) {
            source.sendFailure(Component.translatable("commands.storybound.debug.scenario.unknown", name, String.join(", ", StoryFolder.scenarios())));
            return 0;
        }
        int written;
        try {
            written = StoryFolder.installScenario(ConfigStorySource.root(), name);
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not install scenario {}", name, e);
            source.sendFailure(Component.translatable("commands.storybound.debug.scenario.failed", name, e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.storybound.debug.scenario.installed", name, written), true);
        MinecraftServer server = source.getServer();
        server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((result, error) -> server.execute(() -> {
            if (error != null || player.isRemoved()) {
                return;
            }
            player.sendSystemMessage(Component.translatable("commands.storybound.debug.scenario.started", name,
                    Component.translatable(StoryFolder.SCENARIO + "." + name + ".hint")));
            TriggerDispatcher.fireCustom(player, ResourceLocation.fromNamespaceAndPath(StoryFolder.SCENARIO, name));
        }));
        return written;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int deleted;
        try {
            deleted = StoryFolder.clearScenarios(ConfigStorySource.root());
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not remove the scenarios", e);
            source.sendFailure(Component.translatable("commands.storybound.debug.scenario.failed", "clear", e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.storybound.debug.scenario.cleared", deleted), true);
        source.getServer().getCommands().performPrefixedCommand(source, "reload");
        return deleted;
    }
}
