package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.core.state.QuestProgress;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** The local player's quests (from whatever scope each quest uses) as last sent by the server. */
public final class ClientQuests {
    private static volatile Map<ResourceLocation, QuestProgress> quests = Map.of();
    private static volatile Optional<ResourceLocation> tracked = Optional.empty();

    /** Incremented on every change (on the main thread); open views compare it to know when to refresh. */
    private static volatile int version;

    private ClientQuests() {}

    public static int version() {
        return version;
    }

    public static void apply(Map<ResourceLocation, QuestProgress> quests, Optional<ResourceLocation> tracked) {
        ClientQuests.quests = Map.copyOf(quests);
        ClientQuests.tracked = tracked;
        version++;
    }

    public static Map<ResourceLocation, QuestProgress> all() {
        return quests;
    }

    public static Optional<QuestProgress> get(ResourceLocation quest) {
        return Optional.ofNullable(quests.get(quest));
    }

    public static Optional<ResourceLocation> tracked() {
        return tracked;
    }

    public static void clear() {
        quests = Map.of();
        tracked = Optional.empty();
        version++;
    }
}
