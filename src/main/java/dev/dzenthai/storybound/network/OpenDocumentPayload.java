package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientPayloads;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Opens the reading screen; the server has already marked the document as read. */
public record OpenDocumentPayload(ResourceLocation document) implements CustomPacketPayload {
    public static final Type<OpenDocumentPayload> TYPE = new Type<>(Storybound.id("open_document"));
    public static final StreamCodec<ByteBuf, OpenDocumentPayload> STREAM_CODEC =
            ResourceLocation.STREAM_CODEC.map(OpenDocumentPayload::new, OpenDocumentPayload::document);

    @Override
    public Type<OpenDocumentPayload> type() {
        return TYPE;
    }

    // ClientPayloads holds the client-only code, so this class stays loadable on a dedicated server.
    public static void handleOnClient(OpenDocumentPayload payload, IPayloadContext context) {
        ClientPayloads.openDocument(payload.document());
    }
}
