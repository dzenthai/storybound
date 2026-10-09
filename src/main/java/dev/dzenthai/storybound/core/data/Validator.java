package dev.dzenthai.storybound.core.data;

import java.util.function.Consumer;

/**
 * Cross-reference check that runs after all definition types are loaded:
 * broken references, unreachable dialogue nodes, undeclared flags.
 */
@FunctionalInterface
public interface Validator {
    void validate(Consumer<ValidationIssue> sink);
}
