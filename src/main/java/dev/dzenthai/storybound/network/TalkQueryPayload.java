package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client question for the talk hint: would the talk key on the entity under the crosshair do anything? */
public record TalkQueryPayload(int entityId) implements CustomPacketPayload {
    public static final Type<TalkQueryPayload> TYPE = new Type<>(Storybound.id("talk_query"));
    public static final StreamCodec<ByteBuf, TalkQueryPayload> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(TalkQueryPayload::new, TalkQueryPayload::entityId);

    @Override
    public Type<TalkQueryPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TalkQueryPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            StoryboundNetwork.sendToPlayer(player, new TalkHintPayload(payload.entityId(), NpcBindings.canTalk(player, payload.entityId())));
        }
    }
}
