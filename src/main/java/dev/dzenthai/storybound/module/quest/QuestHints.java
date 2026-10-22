package dev.dzenthai.storybound.module.quest;

import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.network.QuestHintsPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Which objective hints show for a player: those whose conditions hold and, with {@code after}, whose objective has
 * not moved for that long. Looked at every second with the quests' other checks; a player gets the list when it
 * changes. Since when an objective has not moved is kept in memory: after a restart the waiting starts again.
 */
public final class QuestHints {
    /** Per player: per objective ({@link #key}), its count when last seen and since when (overworld game time). */
    private static final Map<UUID, Map<String, long[]>> PROGRESS = new HashMap<>();
    /** Per player: the hints last sent. */
    private static final Map<UUID, Set<String>> SHOWN = new HashMap<>();

    private QuestHints() {}

    /** A hint's key, as the client asks for it. */
    public static String key(Object quest, String stage, String objective, int hint) {
        return quest + "#" + stage + "#" + objective + "#" + hint;
    }

    /** Public for tests. */
    public static void check(ServerPlayer player) {
        if (!Quests.isEnabled()) {
            return;
        }
        long now = Quests.now(player);
        StoryContext context = StoryContext.of(player);
        Map<String, long[]> progress = PROGRESS.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        Set<String> seen = new LinkedHashSet<>();
        Set<String> shown = new LinkedHashSet<>();
        QuestRegistries.QUESTS.serverStore().all().forEach((quest, definition) -> {
            QuestProgress current = StoryState.getQuest(player, definition.scope(), quest).orElse(null);
            QuestDefinition.Stage stage = current != null && current.status() == QuestStatus.ACTIVE
                    ? current.stage().flatMap(definition::stage).orElse(null) : null;
            if (stage == null) {
                return;
            }
            for (int i = 0; i < stage.objectives().size(); i++) {
                QuestDefinition.Objective objective = stage.objectives().get(i);
                if (objective.hints().isEmpty()) {
                    continue;
                }
                String objectiveId = stage.objectiveId(i);
                int count = current.count(objectiveId);
                String key = key(quest, stage.id(), objectiveId, -1);
                seen.add(key);
                long[] last = progress.get(key);
                if (last == null || last[0] != count) {
                    last = new long[]{count, now};
                    progress.put(key, last);
                }
                if (count >= objective.count()) {
                    continue;
                }
                for (int h = 0; h < objective.hints().size(); h++) {
                    QuestDefinition.Hint hint = objective.hints().get(h);
                    boolean waited = hint.after().map(seconds -> now - progress.get(key)[1] >= Math.round(seconds * 20)).orElse(true);
                    if (waited && hint.conditions().test(context)) {
                        shown.add(key(quest, stage.id(), objectiveId, h));
                    }
                }
            }
        });
        // Objectives no longer under way are forgotten, so coming back to one waits anew.
        progress.keySet().retainAll(seen);
        Set<String> before = SHOWN.getOrDefault(player.getUUID(), Set.of());
        if (!shown.equals(before)) {
            for (String hint : shown) {
                if (!before.contains(hint)) {
                    StoryTrace.event(player, "hint", "hint", hint, "shown", "true");
                }
            }
            SHOWN.put(player.getUUID(), shown);
            StoryboundNetwork.sendToPlayer(player, new QuestHintsPayload(shown));
        }
    }

    /** The hints shown to the player now (as last sent). */
    public static Set<String> shown(ServerPlayer player) {
        return Set.copyOf(SHOWN.getOrDefault(player.getUUID(), Set.of()));
    }

    static void forget(UUID player) {
        PROGRESS.remove(player);
        SHOWN.remove(player);
    }

    static void reset() {
        PROGRESS.clear();
        SHOWN.clear();
    }
}
