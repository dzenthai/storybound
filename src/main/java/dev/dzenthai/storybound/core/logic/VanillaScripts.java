package dev.dzenthai.storybound.core.logic;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.data.VanillaScriptDefinition;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The validator of vanilla scripts ({@link VanillaScriptDefinition}): every command is parsed, as the server would take
 * it with the permission level scripts run at ({@code actions.commandPermissionLevel}), not run; a {@code wait} needs a
 * number of seconds; an empty script is a warning; a {@code run_script} naming no script is an error.
 */
public final class VanillaScripts {
    /** The warning of a script with no command (the editor words it itself). */
    public static final String NO_COMMANDS = "The script has no commands";

    private VanillaScripts() {}

    public static void register() {
        ValidationService.registerValidator(VanillaScripts::validate);
    }

    private static void validate(Consumer<ValidationIssue> sink) {
        var store = CoreDefinitions.VANILLA_SCRIPTS.serverStore();
        LogicOwners.walkAll((owner, ownerId) -> new References() {
            @Override
            public void vanillaScript(ResourceLocation id) {
                if (!store.contains(id)) {
                    sink.accept(ValidationIssue.error(owner.id(), ownerId, "Unknown script " + id));
                }
            }
        });
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        store.all().forEach((id, script) -> {
            if (script.lines().stream().allMatch(VanillaScriptDefinition::skipped)) {
                sink.accept(ValidationIssue.warning(CoreDefinitions.VANILLA_SCRIPTS.id(), id, NO_COMMANDS));
                return;
            }
            if (server != null) {
                problems(server, script.lines()).forEach(problem -> sink.accept(ValidationIssue.error(CoreDefinitions.VANILLA_SCRIPTS.id(), id, problem)));
            }
        });
    }

    /** What is wrong with the lines, each as "line N: ..."; empty when every command parses. */
    public static List<String> problems(MinecraftServer server, List<String> lines) {
        CommandSourceStack source = server.createCommandSourceStack()
                .withPermission(StoryboundConfig.COMMON.commandPermissionLevel.get())
                .withSuppressedOutput();
        List<String> problems = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (VanillaScriptDefinition.skipped(line)) {
                continue;
            }
            Double wait = VanillaScriptDefinition.waitSeconds(line);
            if (wait != null) {
                if (wait.isNaN() || wait < 0.05 || wait > VanillaScriptDefinition.MAX_WAIT) {
                    problems.add("line " + (i + 1) + ": wait needs seconds, from 0.05 to " + (long) VanillaScriptDefinition.MAX_WAIT);
                }
                continue;
            }
            ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(VanillaScriptDefinition.command(line), source);
            CommandSyntaxException failure = Commands.getParseException(parsed);
            if (failure != null) {
                problems.add("line " + (i + 1) + ": " + failure.getMessage());
            } else if (parsed.getContext().getLastChild().getCommand() == null) {
                problems.add("line " + (i + 1) + ": incomplete command");
            }
        }
        return problems;
    }
}
