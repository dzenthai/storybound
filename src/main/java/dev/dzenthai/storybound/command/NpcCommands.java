package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.NpcBinding;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/** {@code /storybound npc bind <entity> <dialogue> [interact|proximity|both]}, {@code unbind <entity>}, {@code info <entity>}. */
final class NpcCommands {
    private static final SuggestionProvider<CommandSourceStack> DIALOGUES = (context, builder) ->
            SharedSuggestionProvider.suggestResource(DialogueRegistries.DIALOGUES.serverStore().all().keySet(), builder);

    private NpcCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> npc() {
        return Commands.literal("npc")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("bind").then(Commands.argument("entity", EntityArgument.entity())
                        .then(Commands.argument("dialogue", ResourceLocationArgument.id()).suggests(DIALOGUES)
                                .executes(context -> bind(context, true, false))
                                .then(Commands.literal("interact").executes(context -> bind(context, true, false)))
                                .then(Commands.literal("proximity").executes(context -> bind(context, false, true)))
                                .then(Commands.literal("both").executes(context -> bind(context, true, true))))))
                .then(Commands.literal("unbind").then(Commands.argument("entity", EntityArgument.entity()).executes(NpcCommands::unbind)))
                .then(Commands.literal("info").then(Commands.argument("entity", EntityArgument.entity()).executes(NpcCommands::info)));
    }

    private static int bind(CommandContext<CommandSourceStack> context, boolean interact, boolean proximity) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "entity");
        ResourceLocation dialogue = ResourceLocationArgument.getId(context, "dialogue");
        if (!DialogueRegistries.DIALOGUES.serverStore().contains(dialogue)) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.dialogue.unknown", dialogue.toString())).create();
        }
        entity.setData(DialogueRegistries.NPC_BINDING, new NpcBinding.Attached(dialogue, interact, proximity));
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.bound", entity.getDisplayName(), dialogue.toString()), true);
        return 1;
    }

    private static int unbind(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "entity");
        if (!entity.hasData(DialogueRegistries.NPC_BINDING)) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.storybound.npc.not_bound", entity.getDisplayName())).create();
        }
        entity.removeData(DialogueRegistries.NPC_BINDING);
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.unbound", entity.getDisplayName()), true);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "entity");
        NpcBindings.Found found = NpcBindings.find(entity).orElse(null);
        if (found == null) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.none", entity.getDisplayName()), false);
            return 0;
        }
        String source = found.id() != null ? found.id().toString() : "/storybound npc bind";
        String dialogues = String.join(", ", found.binding().dialogues().stream().map(entry -> entry.dialogue().toString()).toList());
        String modes = (found.binding().interact() ? "interact " : "") + (found.binding().proximity().isPresent() ? "proximity " : "")
                + (found.binding().custom() ? "custom" : "");
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.info", entity.getDisplayName(), source,
                dialogues.isEmpty() ? "-" : dialogues, modes.isBlank() ? "-" : modes.strip()), false);
        return 1;
    }
}
