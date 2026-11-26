package dev.dzenthai.storybound.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class StoryboundNetwork {
    /** Bumped on any incompatible packet change; a client with another version cannot connect. */
    public static final String PROTOCOL_VERSION = "39";

    private StoryboundNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        DevProjectPayloads.register(registrar);
        registrar.playToClient(SyncDefinitionsPayload.TYPE, SyncDefinitionsPayload.STREAM_CODEC, SyncDefinitionsPayload::handleOnClient);
        registrar.playToClient(SyncStoryValuesPayload.TYPE, SyncStoryValuesPayload.STREAM_CODEC, SyncStoryValuesPayload::handleOnClient);
        registrar.playToClient(JournalSyncPayload.TYPE, JournalSyncPayload.STREAM_CODEC, JournalSyncPayload::handleOnClient);
        registrar.playToClient(JournalBookPayload.TYPE, JournalBookPayload.STREAM_CODEC, JournalBookPayload::handleOnClient);
        registrar.playToClient(ReputationPayload.TYPE, ReputationPayload.STREAM_CODEC, ReputationPayload::handleOnClient);
        registrar.playToClient(MusicPayload.TYPE, MusicPayload.STREAM_CODEC, MusicPayload::handleOnClient);
        registrar.playToClient(FadePayload.TYPE, FadePayload.STREAM_CODEC, FadePayload::handleOnClient);
        registrar.playToClient(ScenePayloads.Play.TYPE, ScenePayloads.Play.STREAM_CODEC, ScenePayloads.Play::handleOnClient);
        registrar.playToClient(ScenePayloads.Line.TYPE, ScenePayloads.Line.STREAM_CODEC, ScenePayloads.Line::handleOnClient);
        registrar.playToClient(ScenePayloads.End.TYPE, ScenePayloads.End.STREAM_CODEC, ScenePayloads.End::handleOnClient);
        registrar.playToClient(ScenePayloads.Caption.TYPE, ScenePayloads.Caption.STREAM_CODEC, ScenePayloads.Caption::handleOnClient);
        registrar.playToClient(ScenePayloads.Shake.TYPE, ScenePayloads.Shake.STREAM_CODEC, ScenePayloads.Shake::handleOnClient);
        registrar.playToClient(ScenePayloads.Pause.TYPE, ScenePayloads.Pause.STREAM_CODEC, ScenePayloads.Pause::handleOnClient);
        registrar.playToClient(ScenePayloads.Resume.TYPE, ScenePayloads.Resume.STREAM_CODEC, ScenePayloads.Resume::handleOnClient);
        registrar.playToServer(ScenePayloads.Skip.TYPE, ScenePayloads.Skip.STREAM_CODEC, ScenePayloads.Skip::handleOnServer);
        registrar.playToServer(ScenePayloads.AnchorQuery.TYPE, ScenePayloads.AnchorQuery.STREAM_CODEC, ScenePayloads.AnchorQuery::handleOnServer);
        registrar.playToClient(ScenePayloads.AnchorAnswer.TYPE, ScenePayloads.AnchorAnswer.STREAM_CODEC, ScenePayloads.AnchorAnswer::handleOnClient);
        registrar.playToServer(JournalBookActionPayload.TYPE, JournalBookActionPayload.STREAM_CODEC, JournalBookActionPayload::handleOnServer);
        registrar.playToClient(OpenDocumentPayload.TYPE, OpenDocumentPayload.STREAM_CODEC, OpenDocumentPayload::handleOnClient);
        registrar.playToClient(ModulesPayload.TYPE, ModulesPayload.STREAM_CODEC, ModulesPayload::handleOnClient);
        registrar.playToClient(QuestSyncPayload.TYPE, QuestSyncPayload.STREAM_CODEC, QuestSyncPayload::handleOnClient);
        registrar.playToClient(QuestHintsPayload.TYPE, QuestHintsPayload.STREAM_CODEC, QuestHintsPayload::handleOnClient);
        registrar.playToClient(ObservationPayload.TYPE, ObservationPayload.STREAM_CODEC, ObservationPayload::handleOnClient);
        registrar.playToClient(DebugPayload.TYPE, DebugPayload.STREAM_CODEC, DebugPayload::handleOnClient);
        registrar.playToClient(NotifyPayload.TYPE, NotifyPayload.STREAM_CODEC, NotifyPayload::handleOnClient);
        registrar.playToClient(DialogueLinePayload.TYPE, DialogueLinePayload.STREAM_CODEC, DialogueLinePayload::handleOnClient);
        registrar.playToClient(DialogueChoicesPayload.TYPE, DialogueChoicesPayload.STREAM_CODEC, DialogueChoicesPayload::handleOnClient);
        registrar.playToClient(DialogueClosePayload.TYPE, DialogueClosePayload.STREAM_CODEC, DialogueClosePayload::handleOnClient);
        registrar.playToClient(SpeechBubblePayload.TYPE, SpeechBubblePayload.STREAM_CODEC, SpeechBubblePayload::handleOnClient);
        registrar.playToClient(CodexSyncPayload.TYPE, CodexSyncPayload.STREAM_CODEC, CodexSyncPayload::handleOnClient);
        registrar.playToClient(CodexKillsPayload.TYPE, CodexKillsPayload.STREAM_CODEC, CodexKillsPayload::handleOnClient);
        registrar.playToClient(BossSyncPayload.TYPE, BossSyncPayload.STREAM_CODEC, BossSyncPayload::handleOnClient);
        registrar.playToClient(WaypointSyncPayload.TYPE, WaypointSyncPayload.STREAM_CODEC, WaypointSyncPayload::handleOnClient);
        registrar.playToServer(TrackQuestPayload.TYPE, TrackQuestPayload.STREAM_CODEC, TrackQuestPayload::handleOnServer);
        registrar.playToServer(ClaimRewardPayload.TYPE, ClaimRewardPayload.STREAM_CODEC, ClaimRewardPayload::handleOnServer);
        registrar.playToServer(TakeLecternBookPayload.TYPE, TakeLecternBookPayload.STREAM_CODEC, TakeLecternBookPayload::handleOnServer);
        registrar.playToClient(PartySyncPayload.TYPE, PartySyncPayload.STREAM_CODEC, PartySyncPayload::handleOnClient);
        registrar.playToServer(PartyActionPayload.TYPE, PartyActionPayload.STREAM_CODEC, PartyActionPayload::handleOnServer);
        registrar.playToServer(DialogueReplyPayload.TYPE, DialogueReplyPayload.STREAM_CODEC, DialogueReplyPayload::handleOnServer);
        registrar.playToServer(TalkPayload.TYPE, TalkPayload.STREAM_CODEC, TalkPayload::handleOnServer);
        registrar.playToServer(TalkQueryPayload.TYPE, TalkQueryPayload.STREAM_CODEC, TalkQueryPayload::handleOnServer);
        registrar.playToClient(TalkHintPayload.TYPE, TalkHintPayload.STREAM_CODEC, TalkHintPayload::handleOnClient);
        registrar.playToClient(ConfigTextsPayload.TYPE, ConfigTextsPayload.STREAM_CODEC, ConfigTextsPayload::handleOnClient);
        registrar.playToClient(DevModePayloads.Snapshot.TYPE, DevModePayloads.Snapshot.STREAM_CODEC, DevModePayloads.Snapshot::handleOnClient);
        registrar.playToClient(DevModePayloads.ProjectOptions.TYPE, DevModePayloads.ProjectOptions.STREAM_CODEC, DevModePayloads.ProjectOptions::handleOnClient);
        registrar.playToClient(DevModePayloads.Progress.TYPE, DevModePayloads.Progress.STREAM_CODEC, DevModePayloads.Progress::handleOnClient);
        registrar.playToClient(DevModePayloads.Scripts.TYPE, DevModePayloads.Scripts.STREAM_CODEC, DevModePayloads.Scripts::handleOnClient);
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
