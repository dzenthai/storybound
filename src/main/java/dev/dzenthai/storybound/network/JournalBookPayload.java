package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientJournal;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** A kept signed book's pages, asked for to read it from the Journal (November 20, 2026); the client opens it. */
public record JournalBookPayload(String title, String author, List<Component> pages) implements CustomPacketPayload {
    public static final Type<JournalBookPayload> TYPE = new Type<>(Storybound.id("journal_book"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalBookPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, JournalBookPayload::title,
            ByteBufCodecs.STRING_UTF8, JournalBookPayload::author,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), JournalBookPayload::pages,
            JournalBookPayload::new);

    @Override
    public Type<JournalBookPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(JournalBookPayload payload, IPayloadContext context) {
        ClientJournal.openBook(payload.pages());
    }
}
