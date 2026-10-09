package dev.dzenthai.storybound.core.logic;

import dev.dzenthai.storybound.Storybound;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/**
 * Conditions and actions implemented in code and referenced from JSON by id
 * ({@code "type": "storybound:kubejs", "id": "mypack:is_night"}). KubeJS fills it from scripts;
 * Java mods may register here too.
 */
public final class ScriptHooks {
    private static final Map<ResourceLocation, Predicate<StoryContext>> CONDITIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Consumer<StoryContext>> ACTIONS = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> REPORTED_MISSING = ConcurrentHashMap.newKeySet();

    private ScriptHooks() {}

    public static void registerCondition(ResourceLocation id, Predicate<StoryContext> condition) {
        CONDITIONS.put(id, condition);
    }

    public static void registerAction(ResourceLocation id, Consumer<StoryContext> action) {
        ACTIONS.put(id, action);
    }

    /** Called before scripts reload so that removed script hooks disappear. */
    public static void clear() {
        CONDITIONS.clear();
        ACTIONS.clear();
        REPORTED_MISSING.clear();
    }

    public static boolean hasCondition(ResourceLocation id) {
        return CONDITIONS.containsKey(id);
    }

    public static boolean hasAction(ResourceLocation id) {
        return ACTIONS.containsKey(id);
    }

    /** A missing hook fails closed: the condition is false. */
    public static boolean testCondition(ResourceLocation id, StoryContext context) {
        Predicate<StoryContext> condition = CONDITIONS.get(id);
        if (condition == null) {
            reportMissing("condition", id);
            return false;
        }
        return condition.test(context);
    }

    public static void runAction(ResourceLocation id, StoryContext context) {
        Consumer<StoryContext> action = ACTIONS.get(id);
        if (action == null) {
            reportMissing("action", id);
            return;
        }
        action.accept(context);
    }

    private static void reportMissing(String kind, ResourceLocation id) {
        if (REPORTED_MISSING.add(id)) {
            Storybound.LOGGER.warn("Storybound: no script {} registered for {}", kind, id);
        }
    }
}
