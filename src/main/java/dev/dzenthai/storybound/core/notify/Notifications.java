package dev.dzenthai.storybound.core.notify;

import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.network.NotifyPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** Toasts on the player's screen: quest updates and the {@code notify} action. */
public final class Notifications {
    private Notifications() {}

    public static void send(ServerPlayer player, NotifyKind kind, ResolvedText title, Optional<ResolvedText> text) {
        StoryboundNetwork.sendToPlayer(player, new NotifyPayload(kind, title, text));
    }
}
