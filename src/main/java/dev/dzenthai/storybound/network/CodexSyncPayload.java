package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientCodex;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The player's open Codex sections per entry; replaces what the client had. */
public record CodexSyncPayload(Map<ResourceLocation, List<String>> unlocked) implements CustomPacketPayload {
    public static final Type<CodexSyncPayload> TYPE = new Type<>(Storybound.id("sync_codex"));
    public static final StreamCodec<ByteBuf, CodexSyncPayload> STREAM_CODEC =
            ByteBufCodecs.map(HashMap<ResourceLocation, List<String>>::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()))
                    .map(CodexSyncPayload::new, payload -> new HashMap<>(payload.unlocked()));

    @Override
    public Type<CodexSyncPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(CodexSyncPayload payload, IPayloadContext context) {
        ClientCodex.apply(payload.unlocked());
    }
}
