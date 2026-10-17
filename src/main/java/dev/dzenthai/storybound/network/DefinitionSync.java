package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.text.ConfigTexts;
import net.minecraft.server.level.ServerPlayer;

public final class DefinitionSync {
    private DefinitionSync() {}

    public static void sendTo(ServerPlayer player) {
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            if (type.isSynced()) {
                StoryboundNetwork.sendToPlayer(player, SyncDefinitionsPayload.of(type));
            }
        }
        StoryboundNetwork.sendToPlayer(player, new ConfigTextsPayload(ConfigTexts.current()));
    }
}
