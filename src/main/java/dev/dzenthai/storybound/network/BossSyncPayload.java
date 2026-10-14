package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientBosses;
import dev.dzenthai.storybound.module.boss.BossData;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The bosses the player has met, with open sections and statistics; replaces what the client had. */
public record BossSyncPayload(List<BossData.Record> records) implements CustomPacketPayload {
    public static final Type<BossSyncPayload> TYPE = new Type<>(Storybound.id("sync_bosses"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BossSyncPayload> STREAM_CODEC =
            BossData.Record.STREAM_CODEC.apply(ByteBufCodecs.list()).map(BossSyncPayload::new, BossSyncPayload::records);

    @Override
    public Type<BossSyncPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(BossSyncPayload payload, IPayloadContext context) {
        ClientBosses.apply(payload.records());
    }
}
