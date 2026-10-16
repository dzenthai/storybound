package dev.dzenthai.storybound.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import dev.dzenthai.storybound.module.waypoint.WaypointData;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import dev.dzenthai.storybound.module.waypoint.WaypointTarget;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Shared waypoints everyone sees, for admins: {@code /storybound waypoint add <id> <pos> <name>}, {@code remove <id>},
 * and {@code list <player>} (what that player sees).
 */
final class WaypointCommands {
    private WaypointCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> waypoint() {
        return Commands.literal("waypoint")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("add").then(Commands.argument("id", ResourceLocationArgument.id())
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(WaypointCommands::add)))))
                .then(Commands.literal("remove").then(Commands.argument("id", ResourceLocationArgument.id())
                        .suggests(WaypointCommands::suggestShared)
                        .executes(WaypointCommands::remove)))
                .then(Commands.literal("list").then(Commands.argument("target", EntityArgument.player()).executes(WaypointCommands::list)));
    }

    private static int add(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (!Waypoints.isEnabled()) {
            throw new SimpleCommandExceptionType(Component.translatable("storybound.waypoint.disabled")).create();
        }
        ResourceLocation id = ResourceLocationArgument.getId(context, "id");
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        String name = StringArgumentType.getString(context, "name");
        // An admin's label, typed in chat: shown as written, not translated.
        Waypoints.addShared(context.getSource().getServer(), id, new StoryText.Literal(name),
                new WaypointTarget.Fixed(pos, context.getSource().getLevel().dimension()), WaypointDefinition.DEFAULT_COLOR);
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.waypoint.added", id.toString(), name), true);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestShared(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(WaypointData.get(context.getSource().getServer()).all().stream()
                .filter(added -> added.scope() == StoryScope.WORLD).map(WaypointData.Added::id), builder);
    }

    private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(context, "id");
        if (!Waypoints.remove(context.getSource().getServer(), id, StoryScope.WORLD, null)) {
            throw new SimpleCommandExceptionType(Component.translatable("commands.storybound.waypoint.unknown", id.toString())).create();
        }
        context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.waypoint.removed", id.toString()), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "target");
        Map<String, ClientWaypoint> visible = Waypoints.visible(player);
        if (visible.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.waypoint.none", player.getDisplayName()), false);
            return 0;
        }
        visible.values().forEach(waypoint -> context.getSource().sendSuccess(() -> Component.translatable("commands.storybound.waypoint.entry",
                waypoint.key(), waypoint.dimension().toString(), Math.round(waypoint.pos().x), Math.round(waypoint.pos().y), Math.round(waypoint.pos().z)), false));
        return visible.size();
    }
}
