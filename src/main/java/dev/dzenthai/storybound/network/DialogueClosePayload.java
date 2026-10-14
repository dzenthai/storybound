package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dialogue.ClientDialogue;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The session ended (finished or interrupted); the client closes its screen or subtitles. */
public record DialogueClosePayload(int session) implements CustomPacketPayload {
    public static final Type<DialogueClosePayload> TYPE = new Type<>(Storybound.id("dialogue_close"));
    public static final StreamCodec<ByteBuf, DialogueClosePayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(DialogueClosePayload::new, DialogueClosePayload::session);

    @Override
    public Type<DialogueClosePayload> type() {
        return TYPE;
    }

    public static void handleOnClient(DialogueClosePayload payload, IPayloadContext context) {
        ClientDialogue.onClose(payload.session());
    }
}
