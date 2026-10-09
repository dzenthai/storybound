package dev.dzenthai.storybound.core.data;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** A single data problem: the definition type, the entry (if any) and a description. */
public record ValidationIssue(Severity severity, ResourceLocation type, @Nullable ResourceLocation entry, String message) {
    public enum Severity {
        ERROR,
        WARNING
    }

    public static ValidationIssue error(ResourceLocation type, @Nullable ResourceLocation entry, String message) {
        return new ValidationIssue(Severity.ERROR, type, entry, message);
    }

    public static ValidationIssue warning(ResourceLocation type, @Nullable ResourceLocation entry, String message) {
        return new ValidationIssue(Severity.WARNING, type, entry, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public String describe() {
        return "[" + severity + "] " + type + (entry != null ? " " + entry : "") + ": " + message;
    }
}
