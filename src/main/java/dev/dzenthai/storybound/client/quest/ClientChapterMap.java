package dev.dzenthai.storybound.client.quest;

import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.module.quest.ChapterMap;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** The chapter map the server sent, and where each quest stands for this player. */
public final class ClientChapterMap {
    /** How a quest shows on the map. */
    public enum State { COMPLETED, ACTIVE, FAILED, AVAILABLE, LOCKED, HIDDEN }

    private static volatile ChapterMap map = ChapterMap.EMPTY;

    private ClientChapterMap() {}

    public static ChapterMap get() {
        return map;
    }

    public static void set(ChapterMap received) {
        map = received;
    }

    public static void clear() {
        map = ChapterMap.EMPTY;
    }

    /**
     * Started quests by their status; the others are available when every quest before them is completed (or none is
     * before them), else locked, and hidden while unknown when they are {@code invisible}.
     */
    public static State state(ResourceLocation quest) {
        Optional<QuestProgress> progress = ClientQuests.get(quest);
        if (progress.isPresent()) {
            return switch (progress.get().status()) {
                case COMPLETED -> State.COMPLETED;
                case FAILED -> State.FAILED;
                case ACTIVE -> State.ACTIVE;
            };
        }
        QuestDefinition definition = QuestRegistries.QUESTS.clientStore().getOrNull(quest);
        if (definition == null || definition.visibility() == QuestDefinition.Visibility.INVISIBLE) {
            return State.HIDDEN;
        }
        boolean open = map.before(quest).stream()
                .allMatch(before -> ClientQuests.get(before).map(done -> done.status() == QuestStatus.COMPLETED).orElse(false));
        return open ? State.AVAILABLE : State.LOCKED;
    }

    /** A {@code secret} quest still locked: shown as "???". */
    public static boolean secret(ResourceLocation quest, State state) {
        QuestDefinition definition = QuestRegistries.QUESTS.clientStore().getOrNull(quest);
        return state == State.LOCKED && definition != null && definition.visibility() == QuestDefinition.Visibility.SECRET;
    }
}
