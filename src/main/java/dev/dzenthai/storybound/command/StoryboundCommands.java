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
        // The author's debugging tools are under debug (November 7, 2026; before, at the top: twenty entries in the
        // command hints). capture is gone (the dev mode's 🎯 pickers took its place), and so is npc bind (a dialogue
        // stored on an entity beside the NPC nodes, two ways to make an NPC).
        event.getDispatcher().register(Commands.literal("storybound")
                .then(DebugCommands.debug()
                        .then(Commands.literal("validate").executes(StoryboundCommands::validate))
                        .then(TriggerCommands.trigger())
                        .then(TraceCommands.trace())
                        .then(DumpCommands.dump())
                        .then(DumpCommands.export())
                        .then(LangCommands.lang())
                        .then(NpcCommands.npc()))
                .then(StoryStateCommands.flag())
                .then(StoryStateCommands.var())
                .then(DocumentCommands.document())
                .then(QuestCommands.quest())
                .then(DialogueCommands.dialogue())
                .then(CodexCommands.codex())
                .then(CodexCommands.boss())
                .then(WaypointCommands.waypoint())
                .then(PartyCommands.party()));
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
