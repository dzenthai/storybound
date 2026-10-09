package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientStoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.StoryValues;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Values of {@code sync} flags. {@code full} replaces the whole client state,
 * otherwise it is a delta where an empty value means the flag was cleared.
 */
public record SyncStoryValuesPayload(boolean full, Map<ResourceLocation, Optional<StoryValue>> values) implements CustomPacketPayload {
    public static final Type<SyncStoryValuesPayload> TYPE = new Type<>(Storybound.id("sync_story_values"));
    public static final StreamCodec<ByteBuf, SyncStoryValuesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SyncStoryValuesPayload::full,
            ByteBufCodecs.<ByteBuf, ResourceLocation, Optional<StoryValue>, Map<ResourceLocation, Optional<StoryValue>>>map(
                    HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.optional(StoryValues.STREAM_CODEC)),
            SyncStoryValuesPayload::values,
            SyncStoryValuesPayload::new);

    @Override
    public Type<SyncStoryValuesPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(SyncStoryValuesPayload payload, IPayloadContext context) {
        ClientStoryState.apply(payload.full(), payload.values());
    }
}
