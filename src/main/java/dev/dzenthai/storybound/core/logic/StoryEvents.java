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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/** Subscribes loaded story events to their triggers and runs them. */
public final class StoryEvents {
    private static final String SOURCE = Storybound.id("events").toString();
    static final String ONCE_PREFIX = "once/";
    /** Per player: once events run by their state that aborted, not tried again while the state keeps holding. */
    private static final Map<UUID, Set<ResourceLocation>> ATTEMPTED = new HashMap<>();

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
            StoryTrace.note(context.player(), "story event " + id, "skipped: already run once (" + event.once().get().getSerializedName() + ")");
            return;
        }
        if (!Conditions.check(event.conditions(), context, "story event " + id)) {
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
            // Not tried again by its state until that state stops holding (see checkStates).
            ATTEMPTED.computeIfAbsent(context.player().getUUID(), uuid -> new HashSet<>()).add(id);
        }
    }

    /**
     * A {@code once} story event whose trigger announces a state (see {@link Trigger#stateKind()}) that already holds
     * runs as if the trigger had just fired: the change came before the event could hear it (the player was away, the
     * file was added later). Every second for each player. Events without {@code once} stay events: run by state they
     * would repeat every second. One that aborts (its {@code take_item} found nothing) is not tried again until its
     * state stops holding, so it cannot repeat its earlier actions every second. Conditions are tested quietly.
     */
    public static void checkStates(ServerPlayer player) {
        StoryContext context = StoryContext.of(player);
        Set<ResourceLocation> attempted = ATTEMPTED.computeIfAbsent(player.getUUID(), uuid -> new HashSet<>());
        CoreDefinitions.EVENTS.serverStore().all().forEach((id, event) -> {
            if (event.once().isEmpty() || event.trigger().stateKind() == Trigger.StateKind.NONE) {
                return;
            }
            boolean holds;
            try {
                holds = event.trigger().holdsNow(context);
            } catch (RuntimeException e) {
                Storybound.LOGGER.error("Storybound: checking the state of story event {} failed", id, e);
                return;
            }
            if (!holds) {
                attempted.remove(id);
                return;
            }
            if (attempted.contains(id) || StoryState.getStored(player, event.once().get(), onceKey(id)).isPresent()
                    || !event.conditions().test(context)) {
                return;
            }
            StoryTrace.note(player, "story event " + id, "its trigger's state already holds: run");
            run(id, event, context);
        });
    }

    public static void forget(UUID player) {
        ATTEMPTED.remove(player);
    }

    public static void reset() {
        ATTEMPTED.clear();
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

            @Override
            public void script(ResourceLocation hook, boolean condition) {
                // Only with KubeJS: without it every script is missing, and the dev mode says so on its own.
                boolean registered = condition ? ScriptHooks.hasCondition(hook) : ScriptHooks.hasAction(hook);
                if (ModList.get().isLoaded("kubejs") && !registered && reported.add(hook)) {
                    sink.accept(ValidationIssue.warning(type.id(), id, "No script registers the " + (condition ? "condition " : "action ") + hook
                            + " (StoryboundEvents." + (condition ? "registerConditions" : "registerActions") + ", e.add('" + hook + "', ...)): it "
                            + (condition ? "is always false" : "does nothing")));
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
