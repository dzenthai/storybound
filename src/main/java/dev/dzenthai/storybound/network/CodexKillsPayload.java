package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientCodex;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * How many of each Codex entry's creatures the player killed, for "Killed: N".
 *
 * @param all every count (replacing what the client had), or only the ones that changed
 */
public record CodexKillsPayload(Map<ResourceLocation, Integer> kills, boolean all) implements CustomPacketPayload {
    public static final Type<CodexKillsPayload> TYPE = new Type<>(Storybound.id("codex_kills"));
    public static final StreamCodec<ByteBuf, CodexKillsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.<ByteBuf, ResourceLocation, Integer, Map<ResourceLocation, Integer>>map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT),
            CodexKillsPayload::kills,
            ByteBufCodecs.BOOL, CodexKillsPayload::all,
            CodexKillsPayload::new);

    @Override
    public Type<CodexKillsPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(CodexKillsPayload payload, IPayloadContext context) {
        ClientCodex.applyKills(payload.kills(), payload.all());
    }
}
