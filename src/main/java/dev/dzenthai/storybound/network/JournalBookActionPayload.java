package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.journal.JournalBooks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client intent from the Journal: read a kept signed book ({@code remove} false), or take it out. */
public record JournalBookActionPayload(String key, boolean remove) implements CustomPacketPayload {
    public static final Type<JournalBookActionPayload> TYPE = new Type<>(Storybound.id("journal_book_action"));
    public static final StreamCodec<ByteBuf, JournalBookActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, JournalBookActionPayload::key,
            ByteBufCodecs.BOOL, JournalBookActionPayload::remove,
            JournalBookActionPayload::new);

    @Override
    public Type<JournalBookActionPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(JournalBookActionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            JournalBooks.act(player, payload.key(), payload.remove());
        }
    }
}
