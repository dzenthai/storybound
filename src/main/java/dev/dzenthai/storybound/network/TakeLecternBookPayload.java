package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.document.DocumentEvents;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client intent: take the book being read off its lectern ("Take Book" under it, November 4, 2026). The server checks
 * that this document still lies on that lectern, that the player is near it and may build, as vanilla's lectern does.
 */
public record TakeLecternBookPayload(BlockPos lectern, ResourceLocation document) implements CustomPacketPayload {
    public static final Type<TakeLecternBookPayload> TYPE = new Type<>(Storybound.id("take_lectern_book"));
    public static final StreamCodec<ByteBuf, TakeLecternBookPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TakeLecternBookPayload::lectern,
            ResourceLocation.STREAM_CODEC, TakeLecternBookPayload::document,
            TakeLecternBookPayload::new);

    @Override
    public Type<TakeLecternBookPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TakeLecternBookPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            DocumentEvents.takeFromLectern(player, payload.lectern(), payload.document());
        }
    }
}
