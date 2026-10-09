package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code Storybound} global in server scripts:
 * <pre>
 * Storybound.flags(player).set('mypack:met_ciri', true)
 * Storybound.flags(player).get('mypack:gold')
 * Storybound.triggers.fire('mypack:bell_rung', player)
 * </pre>
 */
public class StoryboundBinding {
    private final Triggers triggers = new Triggers();

    public Flags flags(ServerPlayer player) {
        return new Flags(player);
    }

    public Triggers getTriggers() {
        return triggers;
    }

    /** Values of one player; the scope defaults to the flag's declared scope. Errors become script exceptions. */
    public static class Flags {
        private final ServerPlayer player;

        Flags(ServerPlayer player) {
            this.player = player;
        }

        public @Nullable Object get(ResourceLocation key) {
            return get(key, StoryState.declaredScope(key));
        }

        public @Nullable Object get(ResourceLocation key, String scope) {
            return get(key, ScriptValues.scope(scope));
        }

        public void set(ResourceLocation key, Object value) {
            set(key, value, StoryState.declaredScope(key));
        }

        public void set(ResourceLocation key, Object value, String scope) {
            set(key, value, ScriptValues.scope(scope));
        }

        public void clear(ResourceLocation key) {
            clear(key, StoryState.declaredScope(key));
        }

        public void clear(ResourceLocation key, String scope) {
            clear(key, ScriptValues.scope(scope));
        }

        public int add(ResourceLocation key, int amount) {
            return add(key, amount, StoryState.declaredScope(key));
        }

        public int add(ResourceLocation key, int amount, String scope) {
            return add(key, amount, ScriptValues.scope(scope));
        }

        private @Nullable Object get(ResourceLocation key, StoryScope scope) {
            return ScriptValues.toScript(StoryState.get(player, scope, key).orElse(null));
        }

        private void set(ResourceLocation key, Object value, StoryScope scope) {
            try {
                StoryState.set(player, scope, key, ScriptValues.fromScript(value));
            } catch (StoryException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }

        private void clear(ResourceLocation key, StoryScope scope) {
            try {
                StoryState.clear(player, scope, key);
            } catch (StoryException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }

        private int add(ResourceLocation key, int amount, StoryScope scope) {
            try {
                return StoryState.add(player, scope, key, amount);
            } catch (StoryException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
    }

    public static class Triggers {
        /** Fires a {@code custom} trigger: story events with that id run, {@code customTrigger} listeners are notified. */
        public void fire(ResourceLocation id, ServerPlayer player) {
            TriggerDispatcher.fireCustom(player, id);
        }
    }
}
