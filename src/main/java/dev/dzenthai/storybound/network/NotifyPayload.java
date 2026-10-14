package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientPayloads;
import dev.dzenthai.storybound.core.notify.NotifyKind;
import dev.dzenthai.storybound.core.text.ResolvedText;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Shows a toast. */
public record NotifyPayload(NotifyKind kind, ResolvedText title, Optional<ResolvedText> text) implements CustomPacketPayload {
    public static final Type<NotifyPayload> TYPE = new Type<>(Storybound.id("notify"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NotifyPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(index -> NotifyKind.values()[index], NotifyKind::ordinal), NotifyPayload::kind,
            ResolvedText.STREAM_CODEC, NotifyPayload::title,
            ByteBufCodecs.optional(ResolvedText.STREAM_CODEC), NotifyPayload::text,
            NotifyPayload::new);

    @Override
    public Type<NotifyPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(NotifyPayload payload, IPayloadContext context) {
        ClientPayloads.notify(payload.kind(), payload.title(), payload.text());
    }
}
