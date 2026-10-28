package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.event.DialogueEvent;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.module.boss.Bosses;
import dev.dzenthai.storybound.module.codex.Codex;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.quest.QuestLogic;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.module.waypoint.AreaLook;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import dev.dzenthai.storybound.module.waypoint.WaypointTarget;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code Storybound} global in server scripts:
 * <pre>
 * Storybound.flags(player).set('mypack:met_ciri', true)
 * Storybound.flags(player).get('mypack:gold')
 * Storybound.triggers.fire('mypack:bell_rung', player)
 * Storybound.quests(player).state('mypack:wolf_hunt')
 * Storybound.dialogues(player).start('mypack:elder', npc)
 * Storybound.codex(player).unlock('mypack:drowner', 'weakness')
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

    public CodexView codex(ServerPlayer player) {
        return new CodexView(player);
    }

    public BossesView bosses(ServerPlayer player) {
        return new BossesView(player);
    }

    public WaypointsView waypoints(ServerPlayer player) {
        return new WaypointsView(player);
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

    /** The player's Codex. Unlocking returns false for an unknown entry or section, or a disabled module. */
    public static class CodexView {
        private final ServerPlayer player;

        CodexView(ServerPlayer player) {
            this.player = player;
        }

        public boolean unlock(ResourceLocation entry) {
            return Codex.unlock(player, entry, null);
        }

        public boolean unlock(ResourceLocation entry, String section) {
            return Codex.unlock(player, entry, section);
        }

        public boolean lock(ResourceLocation entry) {
            return Codex.lock(player, entry, null);
        }

        /** Any section of the entry is open. */
        public boolean isUnlocked(ResourceLocation entry) {
            return !Codex.data(player).sections(entry).isEmpty();
        }

        public boolean isUnlocked(ResourceLocation entry, String section) {
            return Codex.data(player).isUnlocked(entry, section);
        }
    }

    /** The player's Bosses records. */
    public static class BossesView {
        private final ServerPlayer player;

        BossesView(ServerPlayer player) {
            this.player = player;
        }

        public int victories(ResourceLocation boss) {
            return Bosses.data(player).get(boss).victories();
        }

        public boolean isEncountered(ResourceLocation boss) {
            return Bosses.data(player).get(boss).encountered();
        }

        /** {@code lore}, {@code tactics} or {@code rewards}. */
        public boolean unlock(ResourceLocation boss, String section) {
            return Bosses.unlock(player, boss, section);
        }

        public boolean reset(ResourceLocation boss) {
            return Bosses.reset(player, boss);
        }
    }

    /**
     * Waypoints added for the player, their party or everyone ({@code visibility}: player, party, world; default player).
     * The name is a translation key.
     */
    public static class WaypointsView {
        private final ServerPlayer player;

        WaypointsView(ServerPlayer player) {
            this.player = player;
        }

        public boolean add(ResourceLocation id, String nameKey, double x, double y, double z) {
            return add(id, nameKey, x, y, z, "player");
        }

        public boolean add(ResourceLocation id, String nameKey, double x, double y, double z, String visibility) {
            return Waypoints.add(player, id, StoryText.key(nameKey), new WaypointTarget.Fixed(new Vec3(x, y, z), player.level().dimension()),
                    WaypointDefinition.DEFAULT_COLOR, 0f, AreaLook.DEFAULT, ScriptValues.scope(visibility), WaypointDefinition.DEFAULT_STYLE);
        }

        /** A waypoint that follows the entity (an NPC). */
        public boolean follow(ResourceLocation id, String nameKey, Entity entity, String visibility) {
            return Waypoints.add(player, id, StoryText.key(nameKey), new WaypointTarget.Follow(entity.getUUID()),
                    WaypointDefinition.DEFAULT_COLOR, 0f, AreaLook.DEFAULT, ScriptValues.scope(visibility), WaypointDefinition.DEFAULT_STYLE);
        }

        public boolean structure(ResourceLocation id, String nameKey, String structure) {
            return structure(id, nameKey, structure, "player");
        }

        /**
         * A waypoint at the nearest structure ({@code "minecraft:village_plains"} or a tag, {@code "#minecraft:village"}),
         * searched in the background near the player within 100 chunks, and again as they travel; it shows once found.
         * Throws for an unknown structure.
         */
        public boolean structure(ResourceLocation id, String nameKey, String structure, String visibility) {
            StructureRef ref = StructureRef.of(structure).filter(parsed -> parsed.exists(player.serverLevel()))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown structure or #tag: " + structure));
            WaypointTarget target = new WaypointTarget.Structure(ref, player.serverLevel().dimension(), StructureRef.DEFAULT_SEARCH_RADIUS,
                    Optional.empty());
            return Waypoints.add(player, id, StoryText.key(nameKey), target, WaypointDefinition.DEFAULT_COLOR, 0f, AreaLook.DEFAULT,
                    ScriptValues.scope(visibility), WaypointDefinition.DEFAULT_STYLE);
        }

        public boolean remove(ResourceLocation id) {
            return remove(id, "player");
        }

        public boolean remove(ResourceLocation id, String visibility) {
            StoryScope scope = ScriptValues.scope(visibility);
            return Waypoints.remove(player.server, id, scope, StoryState.owner(player, scope));
        }
    }
}
