package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.quest.Quests;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client intent: track this quest (empty: track nothing). The server checks the quest is active for the player. */
public record TrackQuestPayload(Optional<ResourceLocation> quest) implements CustomPacketPayload {
    public static final Type<TrackQuestPayload> TYPE = new Type<>(Storybound.id("track_quest"));
    public static final StreamCodec<ByteBuf, TrackQuestPayload> STREAM_CODEC =
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).map(TrackQuestPayload::new, TrackQuestPayload::quest);

    @Override
    public Type<TrackQuestPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TrackQuestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            Quests.trackRequested(player, payload.quest());
        }
    }
}
