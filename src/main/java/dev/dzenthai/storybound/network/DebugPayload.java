package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.DebugOverlay;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The {@code /storybound debug} panel: whose story it shows, their state (dialogue, active quests) and the last things
 * the logic did for them; an empty {@code player} hides the panel.
 */
public record DebugPayload(String player, List<String> state, List<String> recent) implements CustomPacketPayload {
    public static final DebugPayload OFF = new DebugPayload("", List.of(), List.of());
    public static final Type<DebugPayload> TYPE = new Type<>(Storybound.id("debug"));
    public static final StreamCodec<ByteBuf, DebugPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DebugPayload::player,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DebugPayload::state,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DebugPayload::recent,
            DebugPayload::new);

    @Override
    public Type<DebugPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(DebugPayload payload, IPayloadContext context) {
        DebugOverlay.set(payload);
    }
}
