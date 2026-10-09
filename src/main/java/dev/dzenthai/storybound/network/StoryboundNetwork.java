package dev.dzenthai.storybound.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class StoryboundNetwork {
    /** Bumped on any incompatible packet change; a client with another version cannot connect. */
    public static final String PROTOCOL_VERSION = "1";

    private StoryboundNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(SyncDefinitionsPayload.TYPE, SyncDefinitionsPayload.STREAM_CODEC, SyncDefinitionsPayload::handleOnClient);
        registrar.playToClient(SyncStoryValuesPayload.TYPE, SyncStoryValuesPayload.STREAM_CODEC, SyncStoryValuesPayload::handleOnClient);
    }

    /**
     * Fake players from other mods and GameTest mock players never negotiate our channels,
     * and NeoForge throws when sending to such a connection, so those players are skipped.
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
