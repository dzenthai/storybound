package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** A fade of the screen (see {@code ScreenFade}): its rise, hold and clearing in seconds, and its colour. */
public record FadePayload(float in, float stay, float out, int color) implements CustomPacketPayload {
    public static final Type<FadePayload> TYPE = new Type<>(Storybound.id("fade"));
    public static final StreamCodec<ByteBuf, FadePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, FadePayload::in,
            ByteBufCodecs.FLOAT, FadePayload::stay,
            ByteBufCodecs.FLOAT, FadePayload::out,
            ByteBufCodecs.INT, FadePayload::color,
            FadePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(FadePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.dzenthai.storybound.client.ScreenFadeOverlay.start(payload.in(), payload.stay(), payload.out(), payload.color()));
    }
}
