package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.event.DialogueEvent;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.quest.QuestLogic;
import dev.dzenthai.storybound.module.quest.Quests;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code Storybound} global in server scripts:
 * <pre>
 * Storybound.flags(player).set('mypack:met_ciri', true)
 * Storybound.flags(player).get('mypack:gold')
 * Storybound.triggers.fire('mypack:bell_rung', player)
 * Storybound.quests(player).state('mypack:wolf_hunt')
 * Storybound.dialogues(player).start('mypack:elder', npc)
 * </pre>
 */
public class StoryboundBinding {
    private final Triggers triggers = new Triggers();

    public Flags flags(ServerPlayer player) {
        return new Flags(player);
    }

    public QuestsView quests(ServerPlayer player) {
        return new QuestsView(player);
    }

    public DialoguesView dialogues(ServerPlayer player) {
        return new DialoguesView(player);
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

    /** Quest progress of one player, read from each quest's own scope. Changes run the quest's actions and triggers. */
    public static class QuestsView {
        private final ServerPlayer player;

        QuestsView(ServerPlayer player) {
            this.player = player;
        }

        /** {@code unknown}, {@code active}, {@code completed} or {@code failed}. */
        public String state(ResourceLocation quest) {
            return QuestLogic.State.of(Quests.progress(player, quest).map(QuestProgress::status)).getSerializedName();
        }

        public @Nullable String stage(ResourceLocation quest) {
            return Quests.progress(player, quest).flatMap(QuestProgress::stage).orElse(null);
        }

        public boolean start(ResourceLocation quest) {
            return Quests.start(player, quest);
        }

        public boolean complete(ResourceLocation quest) {
            return Quests.setStatus(player, quest, QuestStatus.COMPLETED);
        }

        public boolean fail(ResourceLocation quest) {
            return Quests.setStatus(player, quest, QuestStatus.FAILED);
        }

        public boolean reset(ResourceLocation quest) {
            return Quests.setStatus(player, quest, null);
        }

        public boolean setStage(ResourceLocation quest, String stage) {
            return Quests.setStage(player, quest, stage);
        }

        public void track(@Nullable ResourceLocation quest) {
            Quests.track(player, quest);
        }
    }

    /** The player's dialogue. Starting fails (returns false) while they are already talking. */
    public static class DialoguesView {
        private final ServerPlayer player;

        DialoguesView(ServerPlayer player) {
            this.player = player;
        }

        public boolean start(ResourceLocation dialogue) {
            return Dialogues.start(player, dialogue, null);
        }

        /** {@code speaker} becomes the dialogue's {@code bound} participant. */
        public boolean start(ResourceLocation dialogue, @Nullable Entity speaker) {
            return Dialogues.start(player, dialogue, speaker);
        }

        /** Ends it as finished: {@code dialogue_ended} fires. */
        public boolean end() {
            return Dialogues.end(player);
        }

        /** Interrupts it: {@code on_abort} runs. */
        public boolean stop() {
            return Dialogues.interrupt(player, DialogueEvent.EndReason.STOPPED);
        }

        /** The dialogue the player is in (inside a call: the called one), or null. */
        public @Nullable ResourceLocation current() {
            return Dialogues.state(player).map(Dialogues.State::dialogue).orElse(null);
        }
    }
}
