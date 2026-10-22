package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.quest.QuestRewards;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client intent: claim the quest's waiting rewards with the item chosen (-1: nothing to choose). The server checks
 * everything again; a refusal (nothing waiting, nothing chosen, no room) is shown on the action bar.
 */
public record ClaimRewardPayload(ResourceLocation quest, int choice) implements CustomPacketPayload {
    public static final Type<ClaimRewardPayload> TYPE = new Type<>(Storybound.id("claim_reward"));
    public static final StreamCodec<ByteBuf, ClaimRewardPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, ClaimRewardPayload::quest,
            ByteBufCodecs.VAR_INT, ClaimRewardPayload::choice,
            ClaimRewardPayload::new);

    @Override
    public Type<ClaimRewardPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(ClaimRewardPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            Component refused = QuestRewards.claim(player, payload.quest(), payload.choice());
            if (refused != null) {
                player.displayClientMessage(refused.copy().withStyle(ChatFormatting.RED), true);
            }
        }
    }
}
