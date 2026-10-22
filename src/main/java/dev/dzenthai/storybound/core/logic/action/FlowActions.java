package dev.dzenthai.storybound.core.logic.action;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.ScheduledActions;
import dev.dzenthai.storybound.core.logic.StoryContext;
import java.util.List;

/** Actions about when the others run: later, kept through a restart (see {@link ScheduledActions}). */
public final class FlowActions {
    /** A day of game time: enough for any scene, and a typo of minutes for seconds stays visible. */
    private static final double MAX_SECONDS = 24 * 60 * 60;

    private FlowActions() {}

    static int ticks(double seconds) {
        return (int) Math.max(1, Math.round(seconds * 20));
    }

    /**
     * {@code { "type": "wait", "seconds": 5 }}: the rest of the action list runs that much later (game time, so a
     * restart only pauses it). The list itself counts as done: what waits for it (a dialogue, a quest) goes on.
     * {@link dev.dzenthai.storybound.core.logic.Actions#runAll} does the waiting; run alone, it does nothing.
     */
    public record Wait(double seconds) implements Action {
        public static final MapCodec<Wait> CODEC = Codec.doubleRange(0.05, MAX_SECONDS).fieldOf("seconds").xmap(Wait::new, Wait::seconds);

        @Override
        public ActionType<?> type() {
            return BuiltinActions.WAIT.get();
        }

        @Override
        public void run(StoryContext context) {}

        public int ticks() {
            return FlowActions.ticks(seconds);
        }
    }

    /**
     * {@code { "type": "schedule", "seconds": 5, "actions": [ ... ] }}: the actions run that much later, with the same
     * player and entity; the list it is in goes on at once.
     */
    public record Schedule(double seconds, List<Action> actions, OnFail onFail) implements Action {
        public static final MapCodec<Schedule> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.doubleRange(0.05, MAX_SECONDS).fieldOf("seconds").forGetter(Schedule::seconds),
                LogicCodecs.ACTIONS.fieldOf("actions").forGetter(Schedule::actions),
                OnFail.CODEC.optionalFieldOf("on_fail", OnFail.ABORT).forGetter(Schedule::onFail)
        ).apply(instance, Schedule::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.SCHEDULE.get();
        }

        @Override
        public void run(StoryContext context) {
            ScheduledActions.schedule(context, actions, ticks(seconds), "scheduled actions", onFail);
        }

        @Override
        public void collectReferences(References references) {
            actions.forEach(action -> action.collectReferences(references));
        }
    }
}
