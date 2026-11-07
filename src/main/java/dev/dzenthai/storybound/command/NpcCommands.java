package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.List;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import dev.dzenthai.storybound.module.dialogue.NpcParts;
import java.util.Map;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * {@code /storybound debug npc <entity>}: the bindings it matches, closest first, and which of them each setting comes
 * from (November 7, 2026; before, the one binding that applied); {@code ... unbind} takes away a
 * binding stored on the entity by the old {@code /storybound npc bind} (removed November 7, 2026), which still works in
 * worlds that have one. {@code ... npc parts} lists the parts of "one" NPCs, who holds each, where, ended or not; {@code ... npc
 * part <npc> reset} frees one, to try a picking again (November 7, 2026).
 */
final class NpcCommands {

    private NpcCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> npc() {
        return Commands.literal("npc")
                .then(Commands.literal("parts").executes(NpcCommands::parts))
                .then(Commands.literal("part").then(Commands.argument("npc", ResourceLocationArgument.id())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                NpcParts.all(context.getSource().getServer()).keySet(), builder))
                        .then(Commands.literal("reset").executes(NpcCommands::reset))))
                .then(Commands.argument("entity", EntityArgument.entity()).executes(NpcCommands::info)
                        .then(Commands.literal("unbind").executes(NpcCommands::unbind)));
    }

    private static int parts(CommandContext<CommandSourceStack> context) {
        Map<ResourceLocation, NpcParts.Part> parts = NpcParts.all(context.getSource().getServer());
        // "One" NPCs nobody holds yet: waiting for a fitting entity near a player.
        List<ResourceLocation> empty = DialogueRegistries.NPC_BINDINGS.serverStore().all().entrySet().stream()
                .filter(entry -> entry.getValue().isOne() && !parts.containsKey(entry.getKey())).map(Map.Entry::getKey).sorted().toList();
        if (parts.isEmpty() && empty.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.parts.none"), false);
            return 0;
        }
        empty.forEach(npc -> context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.parts.empty", npc.toString()), false));
        parts.forEach((npc, part) -> context.getSource().sendSuccess(() -> Component.translatable(
                part.ended() ? "commands.storybound.npc.parts.ended" : "commands.storybound.npc.parts.held", npc.toString(), part.holder().toString(),
                part.dimension().location().toString(), part.pos().toShortString()), false));
        return parts.size();
    }

    private static int reset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation npc = ResourceLocationArgument.getId(context, "npc");
        if (!NpcParts.reset(context.getSource().getServer(), npc)) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.storybound.npc.part.none", npc.toString())).create();
        }
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.npc.part.reset", npc.toString()), true);
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
