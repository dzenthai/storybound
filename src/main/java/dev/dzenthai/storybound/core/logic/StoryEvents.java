package dev.dzenthai.storybound.core.logic;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.logic.action.WorldActions;
import dev.dzenthai.storybound.core.logic.trigger.Subscription;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/** Subscribes loaded story events to their triggers and runs them. */
public final class StoryEvents {
    private static final String SOURCE = Storybound.id("events").toString();
    private static final String ONCE_PREFIX = "once/";

    private StoryEvents() {}

    /** Re-subscribes all story events; called after every data load. */
    public static void rebuild() {
        List<Subscription<?>> subscriptions = new ArrayList<>();
        CoreDefinitions.EVENTS.serverStore().all().forEach((id, event) -> subscriptions.add(subscribe(id, event, event.trigger())));
        TriggerDispatcher.setSubscriptions(SOURCE, subscriptions);
    }

    /** The internal flag that remembers a {@code once} event has run. */
    public static ResourceLocation onceKey(ResourceLocation eventId) {
        return Storybound.id(ONCE_PREFIX + eventId.getNamespace() + "/" + eventId.getPath());
    }

    static void run(ResourceLocation id, StoryEvent event, StoryContext context) {
        if (event.once().isPresent() && StoryState.getStored(context.player(), event.once().get(), onceKey(id)).isPresent()) {
            return;
        }
        if (!event.conditions().test(context)) {
            return;
        }
        // Marked before the actions run, so an action that re-fires the same trigger cannot repeat the event.
        if (event.once().isPresent()) {
            setDone(id, event.once().get(), context, true);
        }
        boolean completed = Actions.runAll(event.actions(), context, "story event " + id, event.onFail());
        // An aborted event did not happen (e.g. the price could not be taken), so it may be tried again.
        if (!completed && event.onFail() == OnFail.ABORT && event.once().isPresent()) {
            setDone(id, event.once().get(), context, false);
        }
    }

    /** Walks every id a story event refers to; modules use it to validate references to their data. */
    public static void collectReferences(StoryEvent event, References references) {
        event.trigger().collectReferences(references);
        event.conditions().collectReferences(references);
        event.actions().forEach(action -> action.collectReferences(references));
    }

    /**
     * Warns about flags nobody declared (usually a typo; in every definition with logic) and about
     * {@code take_item} placed after other actions in a story event: if it aborts, the earlier actions have already happened.
     */
    static void validate(Consumer<ValidationIssue> sink) {
        LogicOwners.walkAll((type, id) -> new References() {
            private final Set<ResourceLocation> reported = new LinkedHashSet<>();

            @Override
            public void flag(ResourceLocation key) {
                if (!CoreDefinitions.FLAGS.serverStore().contains(key) && reported.add(key)) {
                    sink.accept(ValidationIssue.warning(type.id(), id, "Uses undeclared flag " + key));
                }
            }
        });
        CoreDefinitions.EVENTS.serverStore().all().forEach((id, event) -> {
            if (event.onFail() == OnFail.ABORT && hasTakeAfterOtherActions(event.actions())) {
                sink.accept(ValidationIssue.warning(CoreDefinitions.EVENTS.id(), id,
                        "take_item runs after other actions; if it fails, those actions have already happened. Put take_item first."));
            }
        });
    }

    private static boolean hasTakeAfterOtherActions(List<Action> actions) {
        boolean otherSeen = false;
        for (Action action : actions) {
            boolean take = action instanceof WorldActions.TakeItem;
            if (take && otherSeen) {
                return true;
            }
            otherSeen |= !take;
        }
        return false;
    }

    private static <E> Subscription<E> subscribe(ResourceLocation id, StoryEvent event, Trigger<E> trigger) {
        return new Subscription<>("story event " + id, trigger, context -> run(id, event, context));
    }

    private static void setDone(ResourceLocation id, StoryScope scope, StoryContext context, boolean done) {
        try {
            if (done) {
                StoryState.set(context.player(), scope, onceKey(id), new StoryValue.Bool(true));
            } else {
                StoryState.clear(context.player(), scope, onceKey(id));
            }
        } catch (StoryException e) {
            Storybound.LOGGER.warn("Storybound: could not update the once flag of story event {}: {}", id, e.getMessage());
        }
    }
}
