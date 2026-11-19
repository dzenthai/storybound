package dev.dzenthai.storybound.core.state;

import dev.dzenthai.storybound.network.PartySyncPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps every player's party tab current (November 19, 2026): what it shows depends on everyone (who is online, in
 * which party, invited by whom), so each change sends each player online their own view, as a whole. Parties are a
 * few players each; there is nothing to save by sending less.
 */
public final class PartySync {
    private PartySync() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                sendAll(player.server, null);
            }
        });
        // The one leaving is still in the player list while the event runs: counted as gone.
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                sendAll(player.server, player.getUUID());
            }
        });
    }

    public static void sendAll(MinecraftServer server) {
        sendAll(server, null);
    }

    private static void sendAll(MinecraftServer server, @Nullable UUID gone) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.getUUID().equals(gone)) {
                StoryboundNetwork.sendToPlayer(player, snapshot(player, gone));
            }
        }
    }

    /** What the party tab of {@code player} shows; {@code gone} is a player leaving the server, counted as offline. */
    public static PartySyncPayload snapshot(ServerPlayer player, @Nullable UUID gone) {
        MinecraftServer server = player.server;
        PartyData data = PartyData.get(server);
        long now = System.currentTimeMillis();
        Optional<Party> own = data.partyOf(player.getUUID());
        Optional<PartySyncPayload.Info> info = own.map(party -> new PartySyncPayload.Info(party.id(), party.name().orElse(""), party.leader(),
                party.members().stream().map(member -> new PartySyncPayload.Member(member, PartyManager.nameOf(server, member),
                        online(server, member, gone))).toList()));
        // An invite matters only to one who is in no party: accepting it needs that.
        Optional<PartySyncPayload.Invite> invite = own.isPresent() ? Optional.empty()
                : data.pendingInvite(player.getUUID(), now).flatMap(pending -> data.party(pending.partyId()).map(party ->
                new PartySyncPayload.Invite(party.name().orElse(""), PartyManager.nameOf(server, party.leader()), pending.expiresAtMillis() - now)));
        List<PartySyncPayload.Player> players = new ArrayList<>();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            UUID id = other.getUUID();
            if (id.equals(player.getUUID()) || id.equals(gone) || own.map(party -> party.members().contains(id)).orElse(false)) {
                continue;
            }
            boolean invited = own.isPresent() && data.pendingInvite(id, now).map(pending -> pending.partyId().equals(own.get().id())).orElse(false);
            players.add(new PartySyncPayload.Player(id, other.getGameProfile().getName(), data.partyOf(id).isPresent(), invited));
        }
        players.sort(Comparator.comparing(PartySyncPayload.Player::name, String.CASE_INSENSITIVE_ORDER));
        return new PartySyncPayload(info, invite, players);
    }

    private static boolean online(MinecraftServer server, UUID player, @Nullable UUID gone) {
        return !player.equals(gone) && server.getPlayerList().getPlayer(player) != null;
    }
}
