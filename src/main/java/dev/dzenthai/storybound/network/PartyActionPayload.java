package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.state.PartyManager;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client intent from the party tab (November 19, 2026): one of {@link PartyManager#act}'s actions, with the player it
 * concerns for invite and kick, and the name typed for create (empty for none). The server checks it as it checks the
 * commands; a refusal comes back in the chat.
 */
public record PartyActionPayload(String action, Optional<UUID> target, String name) implements CustomPacketPayload {
    public static final Type<PartyActionPayload> TYPE = new Type<>(Storybound.id("party_action"));
    public static final StreamCodec<ByteBuf, PartyActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PartyActionPayload::action,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), PartyActionPayload::target,
            ByteBufCodecs.stringUtf8(64), PartyActionPayload::name,
            PartyActionPayload::new);

    @Override
    public Type<PartyActionPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(PartyActionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            try {
                PartyManager.act(player, payload.action(), payload.target().orElse(null), payload.name());
            } catch (StoryException e) {
                player.sendSystemMessage(e.component().copy().withStyle(ChatFormatting.RED));
            }
        }
    }
}
