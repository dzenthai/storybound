package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import io.netty.handler.codec.DecoderException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** All synced definitions of one type; fully replaces the client store. */
public record SyncDefinitionsPayload(Content<?> content) implements CustomPacketPayload {
    public static final Type<SyncDefinitionsPayload> TYPE = new Type<>(Storybound.id("sync_definitions"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncDefinitionsPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> write(buf, payload.content()), SyncDefinitionsPayload::read);

    public static <T> SyncDefinitionsPayload of(DefinitionType<T> type) {
        return new SyncDefinitionsPayload(new Content<>(type, type.valuesToSync()));
    }

    @Override
    public Type<SyncDefinitionsPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(SyncDefinitionsPayload payload, IPayloadContext context) {
        payload.content().applyToClient();
    }

    private static <T> void write(RegistryFriendlyByteBuf buf, Content<T> content) {
        ResourceLocation.STREAM_CODEC.encode(buf, content.type().id());
        buf.writeVarInt(content.values().size());
        content.values().forEach((id, value) -> {
            ResourceLocation.STREAM_CODEC.encode(buf, id);
            content.type().streamCodec().encode(buf, value);
        });
    }

    private static SyncDefinitionsPayload read(RegistryFriendlyByteBuf buf) {
        ResourceLocation typeId = ResourceLocation.STREAM_CODEC.decode(buf);
        DefinitionType<?> type = DefinitionTypes.get(typeId);
        if (type == null) {
            throw new DecoderException("Unknown Storybound definition type: " + typeId);
        }
        return new SyncDefinitionsPayload(readContent(buf, type));
    }

    private static <T> Content<T> readContent(RegistryFriendlyByteBuf buf, DefinitionType<T> type) {
        int size = buf.readVarInt();
        Map<ResourceLocation, T> values = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            values.put(ResourceLocation.STREAM_CODEC.decode(buf), type.streamCodec().decode(buf));
        }
        return new Content<>(type, values);
    }

    public record Content<T>(DefinitionType<T> type, Map<ResourceLocation, T> values) {
        void applyToClient() {
            type.clientStore().replace(values);
            Storybound.LOGGER.debug("Storybound: received {} definitions of {}", values.size(), type.id());
        }
    }
}
