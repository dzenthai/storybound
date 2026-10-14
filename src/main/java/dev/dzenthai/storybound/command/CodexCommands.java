package dev.dzenthai.storybound.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dzenthai.storybound.module.boss.BossData;
import dev.dzenthai.storybound.module.boss.BossDefinition;
import dev.dzenthai.storybound.module.boss.BossRegistries;
import dev.dzenthai.storybound.module.boss.Bosses;
import dev.dzenthai.storybound.module.codex.Codex;
import dev.dzenthai.storybound.module.codex.CodexDefinition;
import dev.dzenthai.storybound.module.codex.CodexRegistries;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /storybound codex unlock|lock <targets> <entry> [section]}, {@code codex list <player>},
 * {@code /storybound boss unlock <targets> <boss> <section>}, {@code boss reset <targets> <boss>}, {@code boss info <player> <boss>}.
 */
final class CodexCommands {
    private static final SuggestionProvider<CommandSourceStack> ENTRIES = (context, builder) ->
            SharedSuggestionProvider.suggestResource(CodexRegistries.CODEX.serverStore().all().keySet(), builder);
    private static final SuggestionProvider<CommandSourceStack> SECTIONS = (context, builder) -> {
        CodexDefinition entry = CodexRegistries.CODEX.serverStore().getOrNull(ResourceLocationArgument.getId(context, "entry"));
        return entry == null ? builder.buildFuture() : SharedSuggestionProvider.suggest(entry.sections().stream().map(CodexDefinition.Section::id), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> BOSSES = (context, builder) ->
            SharedSuggestionProvider.suggestResource(BossRegistries.BOSSES.serverStore().all().keySet(), builder);
    private static final SuggestionProvider<CommandSourceStack> BOSS_SECTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(BossDefinition.SECTIONS, builder);

    private CodexCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> codex() {
        return Commands.literal("codex")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("unlock").then(targets().then(entry()
                        .executes(context -> unlock(context, null))
                        .then(section().executes(context -> unlock(context, StringArgumentType.getString(context, "section")))))))
                .then(Commands.literal("lock").then(targets().then(entry()
                        .executes(context -> lock(context, null))
                        .then(section().executes(context -> lock(context, StringArgumentType.getString(context, "section")))))))
                .then(Commands.literal("list").then(Commands.argument("target", EntityArgument.player()).executes(CodexCommands::list)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> boss() {
        return Commands.literal("boss")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("unlock").then(targets().then(bossArgument()
                        .then(Commands.argument("section", StringArgumentType.word()).suggests(BOSS_SECTIONS).executes(CodexCommands::unlockBoss)))))
                .then(Commands.literal("reset").then(targets().then(bossArgument().executes(CodexCommands::resetBoss))))
                .then(Commands.literal("info").then(Commands.argument("target", EntityArgument.player()).then(bossArgument().executes(CodexCommands::bossInfo))));
    }

    private static int unlock(CommandContext<CommandSourceStack> context, @Nullable String section) throws CommandSyntaxException {
        ResourceLocation entry = requireEntry(context);
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        int changed = 0;
        for (ServerPlayer player : targets) {
            if (Codex.unlock(player, entry, section)) {
                changed++;
            }
        }
        if (changed == 0) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.codex.cannot_unlock", entry.toString())).create();
        }
        int total = changed;
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.codex.unlocked", entry.toString(), section == null ? "*" : section, total), true);
        return changed;
    }

    private static int lock(CommandContext<CommandSourceStack> context, @Nullable String section) throws CommandSyntaxException {
        ResourceLocation entry = requireEntry(context);
        int changed = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(context, "targets")) {
            if (Codex.lock(player, entry, section)) {
                changed++;
            }
        }
        int total = changed;
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.codex.locked", entry.toString(), section == null ? "*" : section, total), true);
        return changed;
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "target");
        Map<ResourceLocation, List<String>> unlocked = Codex.data(player).unlocked();
        if (unlocked.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.codex.none", player.getDisplayName()), false);
            return 0;
        }
        unlocked.forEach((entry, sections) -> context.getSource().sendSuccess(() ->
                Component.translatable("commands.storybound.codex.entry", entry.toString(), String.join(", ", sections)), false));
        return unlocked.size();
    }

    private static int unlockBoss(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation boss = requireBoss(context);
        String section = StringArgumentType.getString(context, "section");
        int changed = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(context, "targets")) {
            if (Bosses.unlock(player, boss, section)) {
                changed++;
            }
        }
        if (changed == 0) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.boss.cannot_unlock", boss.toString(), section)).create();
        }
        int total = changed;
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.boss.unlocked", boss.toString(), section, total), true);
        return changed;
    }

    private static int resetBoss(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation boss = requireBoss(context);
        int changed = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(context, "targets")) {
            if (Bosses.reset(player, boss)) {
                changed++;
            }
        }
        int total = changed;
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.boss.reset", boss.toString(), total), true);
        return changed;
    }

    private static int bossInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "target");
        ResourceLocation boss = requireBoss(context);
        BossData.Record record = Bosses.data(player).get(boss);
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.boss.info", boss.toString(), record.encountered(),
                record.victories(), record.bestTicks() < 0 ? "-" : record.bestTicks() / 20 + " s",
                record.sections().isEmpty() ? "-" : String.join(", ", record.sections())), false);
        return record.victories();
    }

    private static ResourceLocation requireEntry(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation entry = ResourceLocationArgument.getId(context, "entry");
        if (!CodexRegistries.CODEX.serverStore().contains(entry)) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.codex.unknown", entry.toString())).create();
        }
        return entry;
    }

    private static ResourceLocation requireBoss(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation boss = ResourceLocationArgument.getId(context, "boss");
        if (!BossRegistries.BOSSES.serverStore().contains(boss)) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.boss.unknown", boss.toString())).create();
        }
        return boss;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> targets() {
        return Commands.argument("targets", EntityArgument.players());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> entry() {
        return Commands.argument("entry", ResourceLocationArgument.id()).suggests(ENTRIES);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> section() {
        return Commands.argument("section", StringArgumentType.word()).suggests(SECTIONS);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> bossArgument() {
        return Commands.argument("boss", ResourceLocationArgument.id()).suggests(BOSSES);
    }
}
