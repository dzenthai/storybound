package dev.dzenthai.storybound.module.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.state.StoryMigrations;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class QuestRegistries {
    /** Clients get titles, texts, stages and objective counts; triggers, conditions and actions stay on the server. */
    public static final DefinitionType<QuestDefinition> QUESTS = DefinitionTypes.register(
            DefinitionType.syncedView(Storybound.id("quest"), "storybound/quests", QuestDefinition.CODEC, QuestDefinition::clientView));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Storybound.MOD_ID);

    /** The quests a player pins on the HUD, the main one first; personal even for party quests. */
    public static final Supplier<AttachmentType<TrackedQuest>> TRACKED = ATTACHMENT_TYPES.register("tracked_quest",
            () -> AttachmentType.builder(() -> TrackedQuest.NONE).serialize(TrackedQuest.CODEC).copyOnDeath().build());

    /** Rewards waiting to be claimed in the Journal; personal, kept through death. */
    public static final Supplier<AttachmentType<QuestRewardData>> REWARDS = ATTACHMENT_TYPES.register("quest_rewards",
            () -> AttachmentType.builder(() -> QuestRewardData.EMPTY).serialize(QuestRewardData.CODEC).copyOnDeath().build());

    private QuestRegistries() {}

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
        QuestLogic.REGISTER_CONDITIONS.register(modBus);
        QuestLogic.REGISTER_ACTIONS.register(modBus);
        QuestLogic.REGISTER_TRIGGERS.register(modBus);
        StoryMigrations.registerPlayer(QuestRegistries::migrate);
        StoryMigrations.registerServer((server, renames) -> QuestRewardsOwed.get(server).migrate(renames));
    }

    /** Renamed quests in the player's pins and waiting rewards. */
    private static int migrate(ServerPlayer player, StoryMigrations.Renames renames) {
        int moved = 0;
        List<ResourceLocation> pinned = player.getData(TRACKED).quests();
        List<ResourceLocation> renamed = StoryMigrations.rename(pinned, StoryMigrations.Kind.QUESTS, renames);
        if (!renamed.equals(pinned)) {
            player.setData(TRACKED, new TrackedQuest(renamed));
            moved++;
        }
        QuestRewardData rewards = player.getData(REWARDS);
        List<QuestRewardData.Pending> next = rewards.pending().stream()
                .map(waiting -> new QuestRewardData.Pending(renames.id(StoryMigrations.Kind.QUESTS, waiting.quest()), waiting.loot())).toList();
        List<QuestRewardData.Received> received = rewards.received().stream()
                .map(given -> new QuestRewardData.Received(renames.id(StoryMigrations.Kind.QUESTS, given.quest()), given.items(), given.xp())).toList();
        if (!next.stream().map(QuestRewardData.Pending::quest).toList().equals(rewards.pending().stream().map(QuestRewardData.Pending::quest).toList())
                || !received.stream().map(QuestRewardData.Received::quest).toList().equals(rewards.received().stream().map(QuestRewardData.Received::quest).toList())) {
            player.setData(REWARDS, new QuestRewardData(next, received));
            moved++;
        }
        return moved;
    }

    /** @param quests pinned, the main one first (saves from before several could be pinned keep theirs as {@code quest}) */
    public record TrackedQuest(List<ResourceLocation> quests) {
        public static final TrackedQuest NONE = new TrackedQuest(List.of());
        public static final Codec<TrackedQuest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(TrackedQuest::quests),
                ResourceLocation.CODEC.optionalFieldOf("quest").forGetter(tracked -> Optional.<ResourceLocation>empty())
        ).apply(instance, (quests, old) -> new TrackedQuest(old.map(List::of).orElse(quests))));

        public TrackedQuest {
            quests = List.copyOf(quests);
        }
    }
}
