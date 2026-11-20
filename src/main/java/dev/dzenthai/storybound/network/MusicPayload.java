package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The story's track the player should hear now; empty for vanilla's own music (see {@code StoryMusic}). */
public record MusicPayload(Optional<ResourceLocation> track) implements CustomPacketPayload {
    public static final Type<MusicPayload> TYPE = new Type<>(Storybound.id("music"));
    public static final StreamCodec<ByteBuf, MusicPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), MusicPayload::track, MusicPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(MusicPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> dev.dzenthai.storybound.client.sound.ClientStoryMusic.play(payload.track()));
    }
}
