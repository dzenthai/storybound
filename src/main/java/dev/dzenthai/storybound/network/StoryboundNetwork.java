package dev.dzenthai.storybound.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class StoryboundNetwork {
    /** Bumped on any incompatible packet change; a client with another version cannot connect. */
    public static final String PROTOCOL_VERSION = "3";

    private StoryboundNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(SyncDefinitionsPayload.TYPE, SyncDefinitionsPayload.STREAM_CODEC, SyncDefinitionsPayload::handleOnClient);
        registrar.playToClient(SyncStoryValuesPayload.TYPE, SyncStoryValuesPayload.STREAM_CODEC, SyncStoryValuesPayload::handleOnClient);
        registrar.playToClient(JournalSyncPayload.TYPE, JournalSyncPayload.STREAM_CODEC, JournalSyncPayload::handleOnClient);
        registrar.playToClient(OpenDocumentPayload.TYPE, OpenDocumentPayload.STREAM_CODEC, OpenDocumentPayload::handleOnClient);
        registrar.playToClient(ModulesPayload.TYPE, ModulesPayload.STREAM_CODEC, ModulesPayload::handleOnClient);
        registrar.playToClient(QuestSyncPayload.TYPE, QuestSyncPayload.STREAM_CODEC, QuestSyncPayload::handleOnClient);
        registrar.playToClient(NotifyPayload.TYPE, NotifyPayload.STREAM_CODEC, NotifyPayload::handleOnClient);
        registrar.playToClient(DialogueLinePayload.TYPE, DialogueLinePayload.STREAM_CODEC, DialogueLinePayload::handleOnClient);
        registrar.playToClient(DialogueChoicesPayload.TYPE, DialogueChoicesPayload.STREAM_CODEC, DialogueChoicesPayload::handleOnClient);
        registrar.playToClient(DialogueClosePayload.TYPE, DialogueClosePayload.STREAM_CODEC, DialogueClosePayload::handleOnClient);
        registrar.playToClient(SpeechBubblePayload.TYPE, SpeechBubblePayload.STREAM_CODEC, SpeechBubblePayload::handleOnClient);
        registrar.playToClient(CodexSyncPayload.TYPE, CodexSyncPayload.STREAM_CODEC, CodexSyncPayload::handleOnClient);
        registrar.playToClient(BossSyncPayload.TYPE, BossSyncPayload.STREAM_CODEC, BossSyncPayload::handleOnClient);
        registrar.playToClient(WaypointSyncPayload.TYPE, WaypointSyncPayload.STREAM_CODEC, WaypointSyncPayload::handleOnClient);
        registrar.playToServer(TrackQuestPayload.TYPE, TrackQuestPayload.STREAM_CODEC, TrackQuestPayload::handleOnServer);
        registrar.playToServer(DialogueReplyPayload.TYPE, DialogueReplyPayload.STREAM_CODEC, DialogueReplyPayload::handleOnServer);
        registrar.playToServer(TalkPayload.TYPE, TalkPayload.STREAM_CODEC, TalkPayload::handleOnServer);
        registrar.playToServer(TalkQueryPayload.TYPE, TalkQueryPayload.STREAM_CODEC, TalkQueryPayload::handleOnServer);
        registrar.playToClient(TalkHintPayload.TYPE, TalkHintPayload.STREAM_CODEC, TalkHintPayload::handleOnClient);
        registrar.playToClient(ConfigTextsPayload.TYPE, ConfigTextsPayload.STREAM_CODEC, ConfigTextsPayload::handleOnClient);
        registrar.playToServer(DevModePayloads.RequestSnapshot.TYPE, DevModePayloads.RequestSnapshot.STREAM_CODEC, DevModePayloads.RequestSnapshot::handleOnServer);
        registrar.playToClient(DevModePayloads.Snapshot.TYPE, DevModePayloads.Snapshot.STREAM_CODEC, DevModePayloads.Snapshot::handleOnClient);
        registrar.playToServer(DevModePayloads.SaveDefinition.TYPE, DevModePayloads.SaveDefinition.STREAM_CODEC, DevModePayloads.SaveDefinition::handleOnServer);
        registrar.playToServer(DevModePayloads.DeleteDefinition.TYPE, DevModePayloads.DeleteDefinition.STREAM_CODEC, DevModePayloads.DeleteDefinition::handleOnServer);
        registrar.playToServer(DevModePayloads.QuickAction.TYPE, DevModePayloads.QuickAction.STREAM_CODEC, DevModePayloads.QuickAction::handleOnServer);
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
