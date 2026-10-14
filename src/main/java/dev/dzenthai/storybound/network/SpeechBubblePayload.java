package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dialogue.SpeechBubbles;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A line someone else's dialogue put above an entity's head; nearby players see it.
 *
 * @param replyTo the player whose chosen option this line answers, or -1; the viewer's client shows
 *                animated dots first, timed by that player's bubble
 * @param think   a {@link ThinkTime} code for that pause
 */
public record SpeechBubblePayload(int entityId, ResolvedText text, int replyTo, int think) implements CustomPacketPayload {
    public static final Type<SpeechBubblePayload> TYPE = new Type<>(Storybound.id("speech_bubble"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpeechBubblePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpeechBubblePayload::entityId,
            ResolvedText.STREAM_CODEC, SpeechBubblePayload::text,
            ByteBufCodecs.VAR_INT, SpeechBubblePayload::replyTo,
            ByteBufCodecs.VAR_INT, SpeechBubblePayload::think,
            SpeechBubblePayload::new);

    @Override
    public Type<SpeechBubblePayload> type() {
        return TYPE;
    }

    public static void handleOnClient(SpeechBubblePayload payload, IPayloadContext context) {
        SpeechBubbles.add(payload.entityId(), payload.text(), payload.replyTo(), payload.think());
    }
}
