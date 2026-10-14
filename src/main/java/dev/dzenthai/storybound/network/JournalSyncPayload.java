package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientJournal;
import dev.dzenthai.storybound.module.journal.JournalNote;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The whole Journal of the receiving player; small enough to resend on every change. */
public record JournalSyncPayload(boolean enabled, List<ResourceLocation> documents, List<JournalNote> notes) implements CustomPacketPayload {
    public static final Type<JournalSyncPayload> TYPE = new Type<>(Storybound.id("sync_journal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, JournalSyncPayload::enabled,
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), JournalSyncPayload::documents,
            JournalNote.STREAM_CODEC.apply(ByteBufCodecs.list()), JournalSyncPayload::notes,
            JournalSyncPayload::new);

    @Override
    public Type<JournalSyncPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(JournalSyncPayload payload, IPayloadContext context) {
        ClientJournal.apply(payload.enabled(), payload.documents(), payload.notes());
    }
}
