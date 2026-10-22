package dev.dzenthai.storybound.core.logic;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Checking conditions where the story decides something, so the trace can say why it did not happen. */
public final class Conditions {
    private Conditions() {}

    /**
     * Tests the condition. For a traced player (see {@link StoryTrace}) it walks {@code all}, {@code any} and
     * {@code not} itself, testing every other condition once (as {@link Condition#test} would, so {@code chance} is
     * not rolled twice), and writes which parts made it fail: where they are ({@code [1].conditions[0]}), how they are
     * written and, for the common ones, what the player has instead.
     *
     * @param source what decides, as the trace names it, e.g. {@code story event mypack:fire}
     */
    public static boolean check(Condition condition, StoryContext context, String source) {
        if (!StoryTrace.active(context.player())) {
            return condition.test(context);
        }
        List<JsonObject> failed = new ArrayList<>();
        boolean passed = traced(condition, context, "", failed);
        StoryTrace.conditions(context, source, passed, failed);
        return passed;
    }

    private static boolean traced(Condition condition, StoryContext context, String path, List<JsonObject> failed) {
        // A list in JSON is an "all" at the top; its elements are [i] there and conditions[i] deeper.
        String children = path.isEmpty() ? "" : path + ".conditions";
        if (condition instanceof LogicConditions.All all) {
            for (int i = 0; i < all.conditions().size(); i++) {
                if (!traced(all.conditions().get(i), context, children + "[" + i + "]", failed)) {
                    return false;
                }
            }
            return true;
        }
        if (condition instanceof LogicConditions.Any any) {
            // None held: every one of them is a reason.
            List<JsonObject> reasons = new ArrayList<>();
            for (int i = 0; i < any.conditions().size(); i++) {
                if (traced(any.conditions().get(i), context, children + "[" + i + "]", reasons)) {
                    return true;
                }
            }
            failed.addAll(reasons);
            return false;
        }
        if (condition instanceof LogicConditions.Not not) {
            boolean inner = traced(not.condition(), context, path + (path.isEmpty() ? "condition" : ".condition"), new ArrayList<>());
            if (inner) {
                failed.add(part(not, context, path, "the condition under \"not\" holds"));
            }
            return !inner;
        }
        boolean passed = condition.test(context);
        if (!passed) {
            failed.add(part(condition, context, path, condition.describe(context)));
        }
        return passed;
    }

    private static JsonObject part(Condition condition, StoryContext context, String path, @Nullable String why) {
        JsonObject part = new JsonObject();
        part.addProperty("path", path.isEmpty() ? "." : path);
        part.add("condition", StoryTrace.json(LogicCodecs.CONDITION, condition));
        if (why != null) {
            part.addProperty("why", why);
        }
        return part;
    }
}
