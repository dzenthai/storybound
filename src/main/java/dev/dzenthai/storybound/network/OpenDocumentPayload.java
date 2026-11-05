package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientPayloads;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Opens the reading screen; the server has already marked the document as read. {@code lectern} is where the book
 * lies when it was opened from a lectern, for the "Take Book" button (November 4, 2026).
 */
public record OpenDocumentPayload(ResourceLocation document, Optional<BlockPos> lectern) implements CustomPacketPayload {
    public static final Type<OpenDocumentPayload> TYPE = new Type<>(Storybound.id("open_document"));
    public static final StreamCodec<ByteBuf, OpenDocumentPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, OpenDocumentPayload::document,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), OpenDocumentPayload::lectern,
            OpenDocumentPayload::new);

    public OpenDocumentPayload(ResourceLocation document) {
        this(document, Optional.empty());
    }

    @Override
    public Type<OpenDocumentPayload> type() {
        return TYPE;
    }

    // ClientPayloads holds the client-only code, so this class stays loadable on a dedicated server.
    public static void handleOnClient(OpenDocumentPayload payload, IPayloadContext context) {
        ClientPayloads.openDocument(payload.document(), payload.lectern().orElse(null));
    }
}
