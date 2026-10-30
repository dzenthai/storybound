package dev.dzenthai.storybound.core.logic;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.logic.action.FlowActions;
import dev.dzenthai.storybound.core.logic.action.SceneActions;
import dev.dzenthai.storybound.core.logic.action.StateActions;
import dev.dzenthai.storybound.core.logic.action.WorldActions;
import dev.dzenthai.storybound.core.logic.trigger.CoreTriggers;
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
        References actions = event.once().filter(scope -> scope == StoryScope.WORLD).isPresent() ? references.once() : references;
        event.actions().forEach(action -> action.collectReferences(actions));
    }

    /**
     * Whether a story event on a change that reaches everyone online who shares it (a party flag, a party or world quest)
     * runs once per such player: unless its {@code once} is at least as wide as the shared scope.
     */
    public static boolean runsForEachSharer(StoryEvent event, StoryScope shared) {
        return event.once().filter(once -> once == StoryScope.WORLD || once == shared).isEmpty();
    }

    /**
     * The actions among these (and inside their {@code schedule}s) whose effect is shared rather than the player's own,
     * so that running them once per member of a party repeats one thing: a spawn that is not {@code unique}, a sound in
     * the world, a command, an addition to a party or world value. A warning names them (October 29, 2026).
     */
    public static List<String> sharedActions(List<Action> actions) {
        List<String> shared = new ArrayList<>();
        for (Action action : actions) {
            switch (action) {
                case SceneActions.SpawnEntity spawn when !spawn.isUnique() -> shared.add("spawn_entity");
                case SceneActions.SoundAt sound -> shared.add("sound_at");
                case WorldActions.RunCommand command -> shared.add("run_command");
                case StateActions.AddVar add when add.scope().orElseGet(() -> StoryState.declaredScope(add.key())) != StoryScope.PLAYER ->
                        shared.add("add_var " + add.key());
                case FlowActions.Schedule schedule -> shared.addAll(sharedActions(schedule.actions()));
                default -> {}
            }
        }
        return shared;
    }

    /** The warning for a story event that runs once per sharing player online and repeats shared actions. */
    public static String eachSharerWarning(String change, StoryScope scope, List<String> shared) {
        String who = scope == StoryScope.WORLD ? "player" : "party member";
        return "Runs for every " + who + " online on " + change + ", so " + String.join(", ", new LinkedHashSet<>(shared))
                + " happens once per " + who + ": put it in the quest's own actions (on_enter, on_complete), which run once,"
                + " or give the event \"once\": \"" + scope.getSerializedName() + "\"";
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
            if (event.trigger() instanceof CoreTriggers.FlagChanged changed && StoryState.declaredScope(changed.key()) == StoryScope.PARTY
                    && runsForEachSharer(event, StoryScope.PARTY)) {
                List<String> shared = sharedActions(event.actions());
                if (!shared.isEmpty()) {
                    sink.accept(ValidationIssue.warning(CoreDefinitions.EVENTS.id(), id, eachSharerWarning("a change of the party flag " + changed.key(), StoryScope.PARTY, shared)));
                }
            }
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
        return new Subscription<>("story event " + id, trigger, context -> run(id, event, context), context -> wouldRun(id, event, context));
    }

    /** Whether the event would run now: not run already when it runs once, its conditions holding (tested quietly). */
    static boolean wouldRun(ResourceLocation id, StoryEvent event, StoryContext context) {
        return (event.once().isEmpty() || StoryState.getStored(context.player(), event.once().get(), onceKey(id)).isEmpty())
                && event.conditions().test(context);
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
