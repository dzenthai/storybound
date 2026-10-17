package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client intent: the player pressed the talk key while looking at this entity. The server checks the entity is
 * within reach before anything happens.
 */
public record TalkPayload(int entityId) implements CustomPacketPayload {
    public static final Type<TalkPayload> TYPE = new Type<>(Storybound.id("talk"));
    public static final StreamCodec<ByteBuf, TalkPayload> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(TalkPayload::new, TalkPayload::entityId);

    @Override
    public Type<TalkPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TalkPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            NpcBindings.talkRequested(player, payload.entityId());
        }
    }
}
