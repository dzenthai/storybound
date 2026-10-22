package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.quest.Quests;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client intent: pin this quest on the HUD, or unpin it. The server checks the quest is active for the player. */
public record TrackQuestPayload(ResourceLocation quest, boolean pin) implements CustomPacketPayload {
    public static final Type<TrackQuestPayload> TYPE = new Type<>(Storybound.id("track_quest"));
    public static final StreamCodec<ByteBuf, TrackQuestPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, TrackQuestPayload::quest,
            ByteBufCodecs.BOOL, TrackQuestPayload::pin,
            TrackQuestPayload::new);

    @Override
    public Type<TrackQuestPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TrackQuestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            Quests.trackRequested(player, payload.quest(), payload.pin());
        }
    }
}
