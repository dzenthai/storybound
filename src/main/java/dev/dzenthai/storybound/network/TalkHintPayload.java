package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dialogue.TalkHint;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The answer to {@link TalkQueryPayload}: whether the client should show "[H] Talk" for that entity. */
public record TalkHintPayload(int entityId, boolean talkable) implements CustomPacketPayload {
    public static final Type<TalkHintPayload> TYPE = new Type<>(Storybound.id("talk_hint"));
    public static final StreamCodec<ByteBuf, TalkHintPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TalkHintPayload::entityId,
            ByteBufCodecs.BOOL, TalkHintPayload::talkable,
            TalkHintPayload::new);

    @Override
    public Type<TalkHintPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(TalkHintPayload payload, IPayloadContext context) {
        TalkHint.answer(payload.entityId(), payload.talkable());
    }
}
