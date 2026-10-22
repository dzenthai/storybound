package dev.dzenthai.storybound.core.logic;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.action.FlowActions;
import java.util.List;

public final class Actions {
    private Actions() {}

    /**
     * Runs actions in order. A failure ({@link StoryException} or a crashed script) is logged;
     * with {@link OnFail#ABORT} the remaining actions are skipped. A {@code wait} leaves the rest for later (see
     * {@link ScheduledActions}). Each action goes to the trace (see {@link StoryTrace}).
     *
     * @return true if every action ran successfully
     */
    public static boolean runAll(List<Action> actions, StoryContext context, String origin, OnFail onFail) {
        boolean success = true;
        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);
            if (action instanceof FlowActions.Wait wait) {
                // The rest runs later; for its owner the list is done.
                StoryTrace.action(context, origin, i + 1, action, "ok", null);
                ScheduledActions.schedule(context, List.copyOf(actions.subList(i + 1, actions.size())), wait.ticks(), origin, onFail);
                return success;
            }
            try {
                action.run(context);
                StoryTrace.action(context, origin, i + 1, action, "ok", null);
                continue;
            } catch (StoryException e) {
                Storybound.LOGGER.warn("Storybound: {} action #{} ({}) failed for {}: {}",
                        origin, i + 1, LogicRegistries.ACTION_TYPES.getKey(action.type()), context.player().getGameProfile().getName(), e.getMessage());
                StoryTrace.action(context, origin, i + 1, action, "failed", e.getMessage());
            } catch (RuntimeException e) {
                Storybound.LOGGER.error("Storybound: {} action #{} ({}) crashed", origin, i + 1, LogicRegistries.ACTION_TYPES.getKey(action.type()), e);
                StoryTrace.action(context, origin, i + 1, action, "crashed", String.valueOf(e));
            }
            success = false;
            if (onFail == OnFail.ABORT) {
                if (i + 1 < actions.size()) {
                    Storybound.LOGGER.warn("Storybound: {} aborted, {} remaining actions skipped", origin, actions.size() - i - 1);
                    StoryTrace.note(context.player(), origin, "aborted: " + (actions.size() - i - 1) + " remaining actions skipped (on_fail: abort)");
                }
                return false;
            }
        }
        return success;
    }
}
