package dev.dzenthai.storybound.core.data;

import dev.dzenthai.storybound.Storybound;
import java.nio.file.Path;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.apache.logging.log4j.LogManager;

/**
 * Checking a story without playing: started with {@code -Dstorybound.validate} (or {@code =<report.json>}, relative
 * to the game folder), a server loads the story with every mod of the pack, validates it, writes the JSON report
 * ({@code logs/storybound-validation.json} by default) and stops, exiting with code 1 when there are errors and 0
 * otherwise, so a pack's CI can fail on a broken story. With {@code -Dstorybound.folder=<path>} it checks another story
 * folder, e.g. the pack's repository.
 */
public final class HeadlessValidation {
    public static final String PROPERTY = "storybound.validate";

    private static volatile int exitCode = -1;

    private HeadlessValidation() {}

    public static void register(IEventBus gameBus) {
        if (System.getProperty(PROPERTY) == null) {
            return;
        }
        // LOWEST: after every other module has finished starting (structure searches, migrations).
        gameBus.addListener(EventPriority.LOWEST, HeadlessValidation::onServerStarted);
        gameBus.addListener(EventPriority.LOWEST, HeadlessValidation::onServerStopped);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ValidationReport report = ValidationService.validate();
        ValidationService.publish(report);
        Path file = report();
        ValidationService.writeJson(report, file);
        Storybound.LOGGER.info("Storybound: validation finished: {} definitions, {} errors, {} warnings; report {}; stopping the server",
                report.definitionCount(), report.errorCount(), report.warningCount(), file.toAbsolutePath().normalize());
        exitCode = report.hasErrors() ? 1 : 0;
        event.getServer().halt(false);
    }

    // The server has saved and stopped: exit with the result. halt, not exit: the shutdown hooks would wait for this thread.
    private static void onServerStopped(ServerStoppedEvent event) {
        if (exitCode >= 0) {
            LogManager.shutdown();
            Runtime.getRuntime().halt(exitCode);
        }
    }

    private static Path report() {
        String value = System.getProperty(PROPERTY, "").trim();
        String file = value.isEmpty() || value.equalsIgnoreCase("true") ? "logs/storybound-validation.json" : value;
        return FMLPaths.GAMEDIR.get().resolve(file);
    }
}
