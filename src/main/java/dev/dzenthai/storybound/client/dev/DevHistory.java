package dev.dzenthai.storybound.client.dev;

import java.util.ArrayDeque;
import java.util.Deque;
import org.jetbrains.annotations.Nullable;

/** Undo and redo of the dev mode's edits, up to 64 steps; kept while the game runs, across openings of the screen. */
final class DevHistory {
    private static final int LIMIT = 64;

    /** @param label shown after "Undone:" / "Redone:" */
    record Step(String label, Runnable undo, Runnable redo) {}

    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();

    /** Records an edit that was just made; a new edit ends what could be redone. */
    void push(String label, Runnable undoAction, Runnable redoAction) {
        undo.push(new Step(label, undoAction, redoAction));
        if (undo.size() > LIMIT) {
            undo.removeLast();
        }
        redo.clear();
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    /** @return the step undone, or null when there is none */
    @Nullable Step undo() {
        Step step = undo.poll();
        if (step != null) {
            step.undo().run();
            redo.push(step);
        }
        return step;
    }

    /** @return the step redone, or null when there is none */
    @Nullable Step redo() {
        Step step = redo.poll();
        if (step != null) {
            step.redo().run();
            undo.push(step);
        }
        return step;
    }
}
