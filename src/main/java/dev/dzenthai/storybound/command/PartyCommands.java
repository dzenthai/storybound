package dev.dzenthai.storybound.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.state.Party;
import dev.dzenthai.storybound.core.state.PartyData;
import dev.dzenthai.storybound.core.state.PartyManager;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** {@code /storybound party ...}, available to every player. */
final class PartyCommands {
    private PartyCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> party() {
        return Commands.literal("party")
                .then(Commands.literal("create")
                        .executes(context -> create(context, null))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> create(context, StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> run(() -> PartyManager.invite(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"))))))
                .then(Commands.literal("accept")
                        .executes(context -> run(() -> PartyManager.accept(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("leave")
                        .executes(context -> run(() -> PartyManager.leave(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("kick").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(PartyCommands::kick)))
                .then(Commands.literal("disband")
                        .executes(context -> run(() -> PartyManager.disband(context.getSource().getPlayerOrException()))))
                .then(Commands.literal("info").executes(PartyCommands::info));
    }

    private static int create(CommandContext<CommandSourceStack> context, @Nullable String name) throws CommandSyntaxException {
        return run(() -> PartyManager.create(context.getSource().getPlayerOrException(), name));
    }

    private static int kick(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer leader = context.getSource().getPlayerOrException();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "player");
        for (GameProfile target : targets) {
            run(() -> PartyManager.kick(leader, target));
        }
        return targets.size();
    }

    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        Optional<Party> party = PartyData.get(server).partyOf(player.getUUID());
        if (party.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("storybound.party.info.none"), false);
            return 0;
        }
        Party present = party.get();
        String members = present.members().stream()
                .map(member -> PartyManager.nameOf(server, member) + (server.getPlayerList().getPlayer(member) != null ? "" : "*"))
                .collect(Collectors.joining(", "));
        context.getSource().sendSuccess(() -> Component.translatable("storybound.party.info.header", PartyManager.displayName(server, present)), false);
        context.getSource().sendSuccess(() -> Component.translatable("storybound.party.info.leader", PartyManager.nameOf(server, present.leader())), false);
        context.getSource().sendSuccess(() -> Component.translatable("storybound.party.info.members", members), false);
        return present.members().size();
    }

    private static int run(PartyAction action) throws CommandSyntaxException {
        try {
            action.run();
            return 1;
        } catch (StoryException e) {
            throw StoryStateCommands.fail(e);
        }
    }

    @FunctionalInterface
    private interface PartyAction {
        void run() throws StoryException, CommandSyntaxException;
    }
}
