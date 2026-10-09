package dev.dzenthai.storybound.core.event;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

/** Party events on {@code NeoForge.EVENT_BUS}; the basis of the {@code party_joined} / {@code party_left} triggers. */
public abstract class PartyEvent extends Event {
    private final MinecraftServer server;
    private final UUID partyId;
    private final UUID player;

    protected PartyEvent(MinecraftServer server, UUID partyId, UUID player) {
        this.server = server;
        this.partyId = partyId;
        this.player = player;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public UUID getPlayer() {
        return player;
    }

    /** A player created a party and became its leader. */
    public static final class Created extends PartyEvent {
        public Created(MinecraftServer server, UUID partyId, UUID leader) {
            super(server, partyId, leader);
        }
    }

    public static final class Joined extends PartyEvent {
        public Joined(MinecraftServer server, UUID partyId, UUID player) {
            super(server, partyId, player);
        }
    }

    public static final class Left extends PartyEvent {
        private final Reason reason;

        public Left(MinecraftServer server, UUID partyId, UUID player, Reason reason) {
            super(server, partyId, player);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }

        public enum Reason {
            LEFT,
            KICKED,
            DISBANDED
        }
    }
}
