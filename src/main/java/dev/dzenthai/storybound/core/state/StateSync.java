package dev.dzenthai.storybound.core.state;

import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import dev.dzenthai.storybound.network.SyncStoryValuesPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** Sends values of flags declared with {@code "sync": true} to clients. Clients never see the rest. */
public final class StateSync {
    private StateSync() {}

    /** Full snapshot: on login, after /reload and when the party changes. */
    public static void sendFull(ServerPlayer player) {
        Map<ResourceLocation, Optional<StoryValue>> values = new LinkedHashMap<>();
        CoreDefinitions.FLAGS.serverStore().all().forEach((key, declaration) -> {
            if (declaration.sync()) {
                StoryState.getStored(player, declaration.scope(), key).ifPresent(value -> values.put(key, Optional.of(value)));
            }
        });
        StoryboundNetwork.sendToPlayer(player, new SyncStoryValuesPayload(true, values));
    }

    static void onChanged(MinecraftServer server, StoryScope scope, @Nullable UUID owner, ResourceLocation key, Optional<StoryValue> value) {
        FlagDeclaration declaration = StoryState.declaration(key);
        if (declaration == null || !declaration.sync()) {
            return;
        }
        SyncStoryValuesPayload payload = new SyncStoryValuesPayload(false, Map.of(key, value));
        for (ServerPlayer player : StoryState.onlinePlayersOf(server, scope, owner)) {
            StoryboundNetwork.sendToPlayer(player, payload);
        }
    }
}
