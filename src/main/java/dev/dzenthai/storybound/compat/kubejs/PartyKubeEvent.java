package dev.dzenthai.storybound.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** {@code partyJoined} and {@code partyLeft}; the player may be offline when kicked. */
public class PartyKubeEvent implements KubeEvent {
    private final MinecraftServer server;
    private final UUID partyId;
    private final UUID playerId;
    private final @Nullable String reason;

    PartyKubeEvent(MinecraftServer server, UUID partyId, UUID playerId, @Nullable String reason) {
        this.server = server;
        this.partyId = partyId;
        this.playerId = playerId;
        this.reason = reason;
    }

    public @Nullable ServerPlayer getPlayer() {
        return server.getPlayerList().getPlayer(playerId);
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public UUID getPartyId() {
        return partyId;
    }

    /** For {@code partyLeft}: {@code left}, {@code kicked} or {@code disbanded}; null for {@code partyJoined}. */
    public @Nullable String getReason() {
        return reason;
    }

    public MinecraftServer getServer() {
        return server;
    }
}
