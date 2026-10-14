package dev.dzenthai.storybound.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestLogic;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.Quests;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** {@code /storybound quest state|stage|reset|track|list ...}. */
final class QuestCommands {
    private static final SuggestionProvider<CommandSourceStack> QUESTS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(QuestRegistries.QUESTS.serverStore().all().keySet(), builder);
    private static final SuggestionProvider<CommandSourceStack> STATES = (context, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(QuestLogic.State.values()).map(QuestLogic.State::getSerializedName), builder);
    private static final SuggestionProvider<CommandSourceStack> STAGES = (context, builder) -> {
        QuestDefinition quest = QuestRegistries.QUESTS.serverStore().getOrNull(ResourceLocationArgument.getId(context, "quest"));
        return quest == null ? builder.buildFuture() : SharedSuggestionProvider.suggest(quest.stages().stream().map(QuestDefinition.Stage::id), builder);
    };

    private QuestCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> quest() {
        return Commands.literal("quest")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("state").then(targets().then(questArgument()
                        .then(Commands.argument("state", StringArgumentType.word()).suggests(STATES).executes(QuestCommands::setState)))))
                .then(Commands.literal("stage").then(targets().then(questArgument()
                        .then(Commands.argument("stage", StringArgumentType.word()).suggests(STAGES).executes(QuestCommands::setStage)))))
                .then(Commands.literal("reset").then(targets().then(questArgument().executes(QuestCommands::reset))))
                .then(Commands.literal("track").then(targets()
                        .executes(context -> track(context, null))
                        .then(questArgument().executes(context -> track(context, requireQuest(context))))))
                .then(Commands.literal("list").then(Commands.argument("target", EntityArgument.player()).executes(QuestCommands::list)));
    }

    private static int setState(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation quest = requireQuest(context);
        String name = StringArgumentType.getString(context, "state");
        QuestLogic.State state = Arrays.stream(QuestLogic.State.values()).filter(value -> value.getSerializedName().equals(name)).findFirst()
                .orElseThrow(() -> new SimpleCommandExceptionType(Component.translatable("commands.storybound.quest.unknown_state", name)).create());
        return apply(context, quest, player -> Quests.setStatus(player, quest, state.status()),
                Component.translatable("commands.storybound.quest.state_set", quest.toString(), name));
    }

    private static int setStage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation quest = requireQuest(context);
        String stage = StringArgumentType.getString(context, "stage");
        if (QuestRegistries.QUESTS.serverStore().getOrNull(quest).stage(stage).isEmpty()) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.storybound.quest.unknown_stage", quest.toString(), stage)).create();
        }
        return apply(context, quest, player -> Quests.setStage(player, quest, stage),
                Component.translatable("commands.storybound.quest.stage_set", quest.toString(), stage));
    }

    private static int reset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation quest = requireQuest(context);
        return apply(context, quest, player -> Quests.setStatus(player, quest, null),
                Component.translatable("commands.storybound.quest.reset", quest.toString()));
    }

    private static int track(CommandContext<CommandSourceStack> context, ResourceLocation quest) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        targets.forEach(player -> Quests.track(player, quest));
        context.getSource().sendSuccess(() -> quest == null
                ? Component.translatable("commands.storybound.quest.untracked", targets.size())
                : Component.translatable("commands.storybound.quest.tracked", quest.toString(), targets.size()), true);
        return targets.size();
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "target");
        CommandSourceStack source = context.getSource();
        int[] count = {0};
        QuestRegistries.QUESTS.serverStore().all().keySet().forEach(quest -> Quests.progress(player, quest).ifPresent(progress -> {
            count[0]++;
            source.sendSuccess(() -> describe(quest, progress, Quests.tracked(player)), false);
        }));
        if (count[0] == 0) {
            source.sendSuccess(() -> Component.translatable("commands.storybound.quest.none", player.getDisplayName()), false);
        }
        return count[0];
    }

    private static Component describe(ResourceLocation quest, QuestProgress progress, Optional<ResourceLocation> tracked) {
        String state = QuestLogic.State.of(Optional.of(progress.status())).getSerializedName();
        Component line = Component.translatable("commands.storybound.quest.entry", quest.toString(), state, progress.stage().orElse("-"));
        return tracked.equals(Optional.of(quest)) ? line.copy().append(Component.translatable("commands.storybound.quest.entry_tracked")) : line;
    }

    /** Runs the change for every target; targets for whom it did nothing are not counted. */
    private static int apply(CommandContext<CommandSourceStack> context, ResourceLocation quest, QuestChange change, Component message) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        int changed = 0;
        for (ServerPlayer player : targets) {
            if (change.apply(player)) {
                changed++;
            }
        }
        if (changed == 0) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.quest.cannot_change", quest.toString())).create();
        }
        int total = changed;
        context.getSource().sendSuccess(() -> message.copy().append(" (" + total + ")"), true);
        return changed;
    }

    private static ResourceLocation requireQuest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation quest = ResourceLocationArgument.getId(context, "quest");
        if (!QuestRegistries.QUESTS.serverStore().contains(quest)) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.quest.unknown", quest.toString())).create();
        }
        return quest;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> targets() {
        return Commands.argument("targets", EntityArgument.players());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> questArgument() {
        return Commands.argument("quest", ResourceLocationArgument.id()).suggests(QUESTS);
    }

    @FunctionalInterface
    private interface QuestChange {
        boolean apply(ServerPlayer player);
    }
}
