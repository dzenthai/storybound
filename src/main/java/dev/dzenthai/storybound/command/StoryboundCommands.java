package dev.dzenthai.storybound.command;

import com.mojang.brigadier.context.CommandContext;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationReport;
import dev.dzenthai.storybound.core.data.ValidationService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class StoryboundCommands {
    private static final int MAX_ISSUES_IN_CHAT = 10;

    private StoryboundCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("storybound")
                .then(Commands.literal("validate")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(StoryboundCommands::validate))
                .then(StoryStateCommands.flag())
                .then(StoryStateCommands.var())
                .then(TriggerCommands.trigger())
                .then(TraceCommands.trace())
                .then(DumpCommands.dump())
                .then(DumpCommands.export())
                .then(CaptureCommands.capture())
                .then(DocumentCommands.document())
                .then(QuestCommands.quest())
                .then(DialogueCommands.dialogue())
                .then(NpcCommands.npc())
                .then(CodexCommands.codex())
                .then(CodexCommands.boss())
                .then(WaypointCommands.waypoint())
                .then(PartyCommands.party())
                .then(ExampleCommands.example()));
    }

    private static int validate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ValidationReport report = ValidationService.validate();
        ValidationService.publish(report);

        source.sendSuccess(() -> Component.translatable("commands.storybound.validate.summary",
                report.definitionCount(), report.errorCount(), report.warningCount()), false);
        report.issues().stream().limit(MAX_ISSUES_IN_CHAT).forEach(issue -> source.sendSuccess(() ->
                Component.literal(issue.describe()).withStyle(issue.isError() ? ChatFormatting.RED : ChatFormatting.YELLOW), false));
        int hidden = report.issues().size() - MAX_ISSUES_IN_CHAT;
        if (hidden > 0) {
            source.sendSuccess(() -> Component.translatable("commands.storybound.validate.more", hidden), false);
        }
        return report.hasErrors() ? 0 : 1;
    }
}
