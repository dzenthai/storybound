package dev.dzenthai.storybound.core.data;

import dev.dzenthai.storybound.Storybound;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/** Combines load errors and cross-reference checks into a single report. */
public final class ValidationService {
    private static final ResourceLocation VALIDATOR_SOURCE = Storybound.id("validator");
    private static final Map<ResourceLocation, List<ValidationIssue>> LOAD_ISSUES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, List<ValidationIssue>> OTHER_LOAD_ISSUES = new ConcurrentHashMap<>();
    private static final List<Validator> VALIDATORS = new CopyOnWriteArrayList<>();
    private static volatile ValidationReport current = ValidationReport.EMPTY;

    private ValidationService() {}

    public static void registerValidator(Validator validator) {
        VALIDATORS.add(validator);
    }

    static void recordLoad(DefinitionType<?> type, List<ValidationIssue> issues) {
        LOAD_ISSUES.put(type.id(), List.copyOf(issues));
    }

    /** Load problems of something that is not a definition type, e.g. the story folder's texts; replaced on every load. */
    public static void recordLoad(ResourceLocation source, List<ValidationIssue> issues) {
        OTHER_LOAD_ISSUES.put(source, List.copyOf(issues));
    }

    public static ValidationReport current() {
        return current;
    }

    public static ValidationReport validate() {
        List<ValidationIssue> issues = new ArrayList<>();
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            issues.addAll(LOAD_ISSUES.getOrDefault(type.id(), List.of()));
        }
        OTHER_LOAD_ISSUES.values().forEach(issues::addAll);
        for (Validator validator : VALIDATORS) {
            try {
                validator.validate(issues::add);
            } catch (RuntimeException e) {
                Storybound.LOGGER.error("Storybound validator {} crashed", validator, e);
                issues.add(ValidationIssue.error(VALIDATOR_SOURCE, null, "Validator crashed: " + e));
            }
        }
        int definitionCount = DefinitionTypes.all().stream().mapToInt(type -> type.serverStore().size()).sum();
        current = new ValidationReport(definitionCount, List.copyOf(issues));
        return current;
    }

    /** Writes the report to the log and to {@code logs/storybound-validation.txt}. */
    public static void publish(ValidationReport report) {
        Storybound.LOGGER.info("Storybound: {} definitions loaded, {} errors, {} warnings",
                report.definitionCount(), report.errorCount(), report.warningCount());
        for (ValidationIssue issue : report.issues()) {
            if (issue.isError()) {
                Storybound.LOGGER.error("Storybound: {}", issue.describe());
            } else {
                Storybound.LOGGER.warn("Storybound: {}", issue.describe());
            }
        }
        writeReportFile(report);
    }

    private static void writeReportFile(ValidationReport report) {
        Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("storybound-validation.txt");
        List<String> lines = new ArrayList<>();
        lines.add("Storybound validation report, " + LocalDateTime.now());
        lines.add(report.definitionCount() + " definitions, " + report.errorCount() + " errors, " + report.warningCount() + " warnings");
        lines.add("");
        report.issues().forEach(issue -> lines.add(issue.describe()));
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not write {}", file, e);
        }
    }
}
