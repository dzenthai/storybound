package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.text.ClientConfigTexts;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The story folder's translations and long texts, on login and after every data load. */
public record ConfigTextsPayload(ConfigTexts.Texts texts) implements CustomPacketPayload {
    public static final Type<ConfigTextsPayload> TYPE = new Type<>(Storybound.id("config_texts"));
    public static final StreamCodec<ByteBuf, ConfigTextsPayload> STREAM_CODEC = ConfigTexts.Texts.STREAM_CODEC.map(ConfigTextsPayload::new, ConfigTextsPayload::texts);

    @Override
    public Type<ConfigTextsPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(ConfigTextsPayload payload, IPayloadContext context) {
        ClientConfigTexts.set(payload.texts());
    }
}
