package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.core.state.QuestProgress;
import io.netty.buffer.ByteBuf;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Every known quest of the receiving player (from whatever scope each quest uses) and the tracked one. */
public record QuestSyncPayload(Map<ResourceLocation, QuestProgress> quests, Optional<ResourceLocation> tracked) implements CustomPacketPayload {
    public static final Type<QuestSyncPayload> TYPE = new Type<>(Storybound.id("sync_quests"));
    public static final StreamCodec<ByteBuf, QuestSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.<ByteBuf, ResourceLocation, QuestProgress, Map<ResourceLocation, QuestProgress>>map(
                    LinkedHashMap::new, ResourceLocation.STREAM_CODEC, QuestProgress.STREAM_CODEC), QuestSyncPayload::quests,
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), QuestSyncPayload::tracked,
            QuestSyncPayload::new);

    @Override
    public Type<QuestSyncPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(QuestSyncPayload payload, IPayloadContext context) {
        ClientQuests.apply(payload.quests(), payload.tracked());
    }
}
