package dev.dzenthai.storybound.core.data;

import java.util.List;

public record ValidationReport(int definitionCount, List<ValidationIssue> issues) {
    public static final ValidationReport EMPTY = new ValidationReport(0, List.of());

    public long errorCount() {
        return issues.stream().filter(ValidationIssue::isError).count();
    }

    public long warningCount() {
        return issues.size() - errorCount();
    }

    public boolean hasErrors() {
        return errorCount() > 0;
    }
}
