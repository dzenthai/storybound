package dev.dzenthai.storybound.core.logic;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.Storybound;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/**
 * Conditions and actions implemented in code and referenced from JSON by id
 * ({@code "type": "storybound:kubejs", "id": "mypack:is_night"}), with the JSON's {@code args} object, so one handler
 * serves the whole story ({@code "args": { "boss": "mypack:wraith", "at": "altar" }}). KubeJS fills it from scripts;
 * Java mods may register here too.
 */
public final class ScriptHooks {
    private static final Map<ResourceLocation, BiPredicate<StoryContext, JsonObject>> CONDITIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, BiConsumer<StoryContext, JsonObject>> ACTIONS = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> REPORTED_MISSING = ConcurrentHashMap.newKeySet();

    private ScriptHooks() {}

    /** A condition taking the JSON's {@code args} (an empty object when the JSON has none; never to be changed). */
    public static void registerCondition(ResourceLocation id, BiPredicate<StoryContext, JsonObject> condition) {
        CONDITIONS.put(id, condition);
    }

    public static void registerCondition(ResourceLocation id, Predicate<StoryContext> condition) {
        registerCondition(id, (context, args) -> condition.test(context));
    }

    /** An action taking the JSON's {@code args} (an empty object when the JSON has none; never to be changed). */
    public static void registerAction(ResourceLocation id, BiConsumer<StoryContext, JsonObject> action) {
        ACTIONS.put(id, action);
    }

    public static void registerAction(ResourceLocation id, Consumer<StoryContext> action) {
        registerAction(id, (context, args) -> action.accept(context));
    }

    /** Called before scripts reload so that removed script hooks disappear. */
    public static void clear() {
        CONDITIONS.clear();
        ACTIONS.clear();
        REPORTED_MISSING.clear();
    }

    /** The conditions registered now (for the dev mode, which shows which script blocks have code behind them). */
    public static Set<ResourceLocation> conditions() {
        return Set.copyOf(CONDITIONS.keySet());
    }

    public static Set<ResourceLocation> actions() {
        return Set.copyOf(ACTIONS.keySet());
    }

    public static boolean hasCondition(ResourceLocation id) {
        return CONDITIONS.containsKey(id);
    }

    public static boolean hasAction(ResourceLocation id) {
        return ACTIONS.containsKey(id);
    }

    /** A missing hook fails closed: the condition is false. */
    public static boolean testCondition(ResourceLocation id, StoryContext context, JsonObject args) {
        BiPredicate<StoryContext, JsonObject> condition = CONDITIONS.get(id);
        if (condition == null) {
            reportMissing("condition", id);
            return false;
        }
        return condition.test(context, args.deepCopy());
    }

    public static void runAction(ResourceLocation id, StoryContext context, JsonObject args) {
        BiConsumer<StoryContext, JsonObject> action = ACTIONS.get(id);
        if (action == null) {
            StoryTrace.note(context.player(), "kubejs " + id, "no script registers the action: nothing ran");
            reportMissing("action", id);
            return;
        }
        // A copy: a handler changing its arguments must not change the next run's.
        action.accept(context, args.deepCopy());
    }

    private static void reportMissing(String kind, ResourceLocation id) {
        if (REPORTED_MISSING.add(id)) {
            Storybound.LOGGER.warn("Storybound: no script {} registered for {}", kind, id);
        }
    }
}
