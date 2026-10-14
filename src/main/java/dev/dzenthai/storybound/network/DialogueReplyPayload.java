package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client intent in a dialogue: {@link #ADVANCE} past a line, {@link #LEAVE} (ESC), or the index of a shown option.
 * The server drops it unless the session and step are the current ones and the option is still available.
 */
public record DialogueReplyPayload(int session, int step, int choice) implements CustomPacketPayload {
    public static final int ADVANCE = -1;
    public static final int LEAVE = -2;

    public static final Type<DialogueReplyPayload> TYPE = new Type<>(Storybound.id("dialogue_reply"));
    public static final StreamCodec<ByteBuf, DialogueReplyPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DialogueReplyPayload::session,
            ByteBufCodecs.VAR_INT, DialogueReplyPayload::step,
            ByteBufCodecs.VAR_INT, DialogueReplyPayload::choice,
            DialogueReplyPayload::new);

    @Override
    public Type<DialogueReplyPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(DialogueReplyPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        switch (payload.choice()) {
            case ADVANCE -> Dialogues.advance(player, payload.session(), payload.step());
            case LEAVE -> Dialogues.leave(player, payload.session());
            default -> Dialogues.choose(player, payload.session(), payload.step(), payload.choice());
        }
    }
}
