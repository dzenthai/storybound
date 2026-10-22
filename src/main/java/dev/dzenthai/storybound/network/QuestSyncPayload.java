package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.module.quest.DifficultyScale;
import dev.dzenthai.storybound.module.quest.QuestRewardData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Every known quest of the receiving player (from whatever scope each quest uses), the pinned ones (the main one
 * first), the difficulty scale and the rewards waiting to be claimed.
 */
public record QuestSyncPayload(Map<ResourceLocation, QuestProgress> quests, List<ResourceLocation> pinned, DifficultyScale difficulty,
                               List<QuestRewardData.Pending> rewards) implements CustomPacketPayload {
    public static final Type<QuestSyncPayload> TYPE = new Type<>(Storybound.id("sync_quests"));
    public static final StreamCodec<RegistryFriendlyByteBuf, QuestSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.<RegistryFriendlyByteBuf, ResourceLocation, QuestProgress, Map<ResourceLocation, QuestProgress>>map(
                    LinkedHashMap::new, ResourceLocation.STREAM_CODEC, QuestProgress.STREAM_CODEC), QuestSyncPayload::quests,
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), QuestSyncPayload::pinned,
            DifficultyScale.STREAM_CODEC, QuestSyncPayload::difficulty,
            QuestRewardData.LIST_STREAM_CODEC, QuestSyncPayload::rewards,
            QuestSyncPayload::new);

    @Override
    public Type<QuestSyncPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(QuestSyncPayload payload, IPayloadContext context) {
        DifficultyScale.set(payload.difficulty());
        ClientQuests.apply(payload.quests(), payload.pinned(), payload.rewards());
    }
}
