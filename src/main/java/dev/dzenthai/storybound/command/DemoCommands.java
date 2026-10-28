package dev.dzenthai.storybound.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.StoryFolder;
import java.io.IOException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * {@code /storybound demo install}: copies the demo story into the story folder and reloads the data. A new story
 * folder has it already; this brings it back after it was deleted, or into a folder made before.
 */
public final class DemoCommands {
    private DemoCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> demo() {
        return Commands.literal("demo")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("install").executes(DemoCommands::install));
    }

    /**
     * An operator joining a world whose demo copy is older than the mod's is told so, with the command to click: the
     * demo's files are never replaced on their own, as the author may have changed them.
     */
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.hasPermissions(Commands.LEVEL_GAMEMASTERS)
                && !ConfigStorySource.isElsewhere() && StoryFolder.demoOutdated(ConfigStorySource.root())) {
            Component command = Component.literal("/storybound demo install").withStyle(style -> style.withColor(ChatFormatting.GOLD).withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/storybound demo install")));
            player.sendSystemMessage(Component.translatable("commands.storybound.demo.outdated", command));
        }
    }

    private static int install(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int written;
        try {
            written = StoryFolder.installDemo(ConfigStorySource.root());
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not install the demo story", e);
            source.sendFailure(Component.translatable("commands.storybound.demo.failed", e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.storybound.demo.installed", written), true);
        source.getServer().getCommands().performPrefixedCommand(source, "reload");
        return written;
    }
}
