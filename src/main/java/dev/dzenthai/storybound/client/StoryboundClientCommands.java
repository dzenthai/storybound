package dev.dzenthai.storybound.client;

import com.mojang.brigadier.context.CommandContext;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.state.StoryValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class StoryboundClientCommands {
    private StoryboundClientCommands() {}

    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("storybound_client")
                .then(Commands.literal("definitions").executes(StoryboundClientCommands::definitions))
                .then(Commands.literal("flags").executes(StoryboundClientCommands::flags))
                .then(Commands.literal("validate").executes(StoryboundClientCommands::validate)));
    }

    /** How many issues show in chat; all of them go to the file. */
    private static final int SHOWN = 10;

    /**
     * Checks what only a client can (see {@link ClientValidation}) in the game's language: prints the first issues and
     * writes them all to {@code logs/storybound-client-validation.txt}.
     */
    private static int validate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        List<ClientValidation.Issue> issues = ClientValidation.check(language);
        Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("storybound-client-validation.txt");
        List<String> lines = new ArrayList<>();
        lines.add("Storybound client validation, " + LocalDateTime.now() + ", language " + language + ": " + issues.size() + " issues");
        issues.forEach(issue -> lines.add(issue.describe()));
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not write {}: {}", file, e.getMessage());
        }
        source.sendSuccess(() -> Component.translatable("commands.storybound_client.validate.result", issues.size(), language, file.toString()), false);
        for (ClientValidation.Issue issue : issues.subList(0, Math.min(SHOWN, issues.size()))) {
            source.sendSuccess(() -> Component.literal(issue.describe()).withStyle(ChatFormatting.YELLOW), false);
        }
        return issues.size();
    }

    /** Sync flag values as the client sees them, defaults included. */
    private static int flags(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Set<ResourceLocation> keys = new TreeSet<>(CoreDefinitions.FLAGS.clientStore().all().keySet());
        keys.addAll(ClientStoryState.stored().keySet());
        for (ResourceLocation key : keys) {
            String value = ClientStoryState.get(key).map(StoryValues::display).orElse("-");
            source.sendSuccess(() -> Component.translatable("commands.storybound_client.definitions.entry", key.toString(), value), false);
        }
        if (keys.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.storybound_client.flags.empty"), false);
        }
        return keys.size();
    }

    /** How many definitions of each synced type the client received. */
    private static int definitions(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int shown = 0;
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            if (type.isSynced()) {
                source.sendSuccess(() -> Component.translatable("commands.storybound_client.definitions.entry",
                        type.id().toString(), type.clientStore().size()), false);
                shown++;
            }
        }
        if (shown == 0) {
            source.sendSuccess(() -> Component.translatable("commands.storybound_client.definitions.empty"), false);
        }
        return shown;
    }
}
