package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.List;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * {@code /storybound debug npc <entity>}: the bindings it matches, closest first, and which of them each setting comes
 * from (November 7, 2026; before, the one binding that applied); {@code ... unbind} takes away a
 * binding stored on the entity by the old {@code /storybound npc bind} (removed November 7, 2026), which still works in
 * worlds that have one.
 */
final class NpcCommands {

    private NpcCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> npc() {
        return Commands.literal("npc")
                .then(Commands.argument("entity", EntityArgument.entity()).executes(NpcCommands::info)
                        .then(Commands.literal("unbind").executes(NpcCommands::unbind)));
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
        NpcBindings.forget(entity);
        NpcBindings.Found found = NpcBindings.find(entity).orElse(null);
        if (found == null) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.none", entity.getDisplayName()), false);
            return 0;
        }
        // "elder (by tag or name) → smiths (by kind, 1 filter) → villagers (by kind)"
        String chain = String.join(" → ", found.chain().stream()
                .map(layer -> name(layer) + " (" + layer.rank().describe() + (layer.tie() ? ", tie" : "") + ")").toList());
        // "display_name: elder; chatter: smiths; when_attacked: villagers"; the rest is the default.
        List<String> settings = new ArrayList<>();
        found.sources().forEach((key, layer) -> {
            if (!key.equals("dialogues")) {
                settings.add(key + ": " + name(layer));
            }
        });
        // "elder 2, smiths 1": a click tries them in this order.
        String click = String.join(", ", found.chain().stream().filter(layer -> !layer.binding().dialogues().isEmpty())
                .map(layer -> name(layer) + " " + layer.binding().dialogues().size()).toList());
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.chain", entity.getDisplayName(), chain), false);
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.settings",
                settings.isEmpty() ? "-" : String.join("; ", settings)), false);
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.click", click.isEmpty() ? "-" : click), false);
        for (int i = 1; i < found.chain().size(); i++) {
            if (found.chain().get(i).tie()) {
                String first = name(found.chain().get(i - 1));
                String second = name(found.chain().get(i));
                context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.tie", first, second), false);
            }
        }
        return found.chain().size();
    }

    private static String name(NpcBindings.Layer layer) {
        return layer.id() != null ? layer.id().toString() : "/storybound npc bind";
    }
}
