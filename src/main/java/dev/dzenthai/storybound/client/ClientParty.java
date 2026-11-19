package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.network.PartySyncPayload;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/** What the server last said about the player's party, for the party tab (November 19, 2026). */
public final class ClientParty {
    private static volatile PartySyncPayload state = new PartySyncPayload(Optional.empty(), Optional.empty(), List.of());
    /** When the state came, to tell when its invite runs out. */
    private static volatile long receivedAt;
    /** Incremented on every change (on the main thread); open views compare it to know when to refresh. */
    private static volatile int version;

    private ClientParty() {}

    public static void apply(PartySyncPayload payload) {
        state = payload;
        receivedAt = System.currentTimeMillis();
        version++;
    }

    public static int version() {
        return version;
    }

    public static Optional<PartySyncPayload.Info> party() {
        return state.party();
    }

    /** The invite waiting for the player, while it holds. */
    public static Optional<PartySyncPayload.Invite> invite() {
        return state.invite().filter(invite -> System.currentTimeMillis() < receivedAt + invite.remainingMillis());
    }

    /** Whole minutes the invite still holds, at least one. */
    public static long inviteMinutesLeft() {
        return invite().map(invite -> Math.max(1, (receivedAt + invite.remainingMillis() - System.currentTimeMillis() + 59_999) / 60_000)).orElse(0L);
    }

    public static List<PartySyncPayload.Player> players() {
        return state.players();
    }

    /** A party's name: its own, or "<leader>'s party" as the server says it. */
    public static Component partyName(String name, String leaderName) {
        return name.isEmpty() ? Component.translatable("storybound.party.default_name", leaderName) : Component.literal(name);
    }

    /** The party tab is there only where others can join: on a server, or in a world opened to LAN. */
    public static boolean openToOthers() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        IntegratedServer local = minecraft.getSingleplayerServer();
        return local == null || local.isPublished();
    }

    public static void clear() {
        state = new PartySyncPayload(Optional.empty(), Optional.empty(), List.of());
        version++;
    }
}
