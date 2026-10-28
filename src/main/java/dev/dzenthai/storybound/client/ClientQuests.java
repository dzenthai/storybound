package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.compat.jei.JeiQuests;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.module.quest.QuestHints;
import dev.dzenthai.storybound.module.quest.QuestRewardData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/** The local player's quests (from whatever scope each quest uses), pinned quests and waiting rewards, as last sent by the server. */
public final class ClientQuests {
    private static volatile Map<ResourceLocation, QuestProgress> quests = Map.of();
    private static volatile List<ResourceLocation> pinned = List.of();
    private static volatile List<QuestRewardData.Pending> rewards = List.of();
    private static volatile List<QuestRewardData.Received> received = List.of();
    private static volatile Set<String> hints = Set.of();

    /** Incremented on every change (on the main thread); open views compare it to know when to refresh. */
    private static volatile int version;

    private ClientQuests() {}

    public static int version() {
        return version;
    }

    public static void apply(Map<ResourceLocation, QuestProgress> quests, List<ResourceLocation> pinned, List<QuestRewardData.Pending> rewards,
                             List<QuestRewardData.Received> received) {
        ClientQuests.quests = Map.copyOf(quests);
        ClientQuests.pinned = List.copyOf(pinned);
        ClientQuests.rewards = List.copyOf(rewards);
        ClientQuests.received = List.copyOf(received);
        version++;
        if (ModList.get().isLoaded("jei")) {
            JeiQuests.refresh();
        }
    }

    public static Map<ResourceLocation, QuestProgress> all() {
        return quests;
    }

    public static Optional<QuestProgress> get(ResourceLocation quest) {
        return Optional.ofNullable(quests.get(quest));
    }

    /** The main pinned quest (the first). */
    public static Optional<ResourceLocation> tracked() {
        List<ResourceLocation> current = pinned;
        return current.isEmpty() ? Optional.empty() : Optional.of(current.getFirst());
    }

    /** The quests pinned on the HUD, the main one first. */
    public static List<ResourceLocation> pinned() {
        return pinned;
    }

    public static boolean isPinned(ResourceLocation quest) {
        return pinned.contains(quest);
    }

    /** The quest's first waiting rewards, with the loot rolled for them. */
    public static Optional<QuestRewardData.Pending> waitingReward(ResourceLocation quest) {
        return rewards.stream().filter(pending -> pending.quest().equals(quest)).findFirst();
    }

    /** How many times the quest's rewards wait (a repeatable quest may wait more than once). */
    public static long waitingRewards(ResourceLocation quest) {
        return rewards.stream().filter(pending -> pending.quest().equals(quest)).count();
    }

    /** What the quest gave the last time its rewards were given. */
    public static Optional<QuestRewardData.Received> received(ResourceLocation quest) {
        return received.stream().filter(given -> given.quest().equals(quest)).findFirst();
    }

    /** The objective hints that show now, as the server sent them. */
    public static void applyHints(Set<String> shown) {
        hints = Set.copyOf(shown);
        version++;
    }

    /** Whether the hint shows now (see {@code QuestHints.key}). */
    public static boolean hintShown(ResourceLocation quest, String stage, String objective, int hint) {
        return hints.contains(QuestHints.key(quest, stage, objective, hint));
    }

    public static void clear() {
        hints = Set.of();
        quests = Map.of();
        pinned = List.of();
        rewards = List.of();
        received = List.of();
        version++;
    }
}
