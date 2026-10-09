package dev.dzenthai.storybound.client;

import com.mojang.brigadier.context.CommandContext;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.state.StoryValues;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class StoryboundClientCommands {
    private StoryboundClientCommands() {}

    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("storybound_client")
                .then(Commands.literal("definitions").executes(StoryboundClientCommands::definitions))
                .then(Commands.literal("flags").executes(StoryboundClientCommands::flags)));
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
