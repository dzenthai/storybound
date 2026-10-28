package dev.dzenthai.storybound.core.event;

import dev.dzenthai.storybound.core.state.QuestProgress;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * A player joined a party. With the party-scoped quests both sides had before (the newcomer's own, the party's), so
     * what the join changed for whom can be told: the party goes on from the further progress of each quest.
     */
    public static final class Joined extends PartyEvent {
        private final Map<ResourceLocation, QuestProgress> broughtQuests;
        private final Map<ResourceLocation, QuestProgress> partyQuestsBefore;

        public Joined(MinecraftServer server, UUID partyId, UUID player, Map<ResourceLocation, QuestProgress> broughtQuests,
                      Map<ResourceLocation, QuestProgress> partyQuestsBefore) {
            super(server, partyId, player);
            this.broughtQuests = Map.copyOf(broughtQuests);
            this.partyQuestsBefore = Map.copyOf(partyQuestsBefore);
        }

        /** The newcomer's own party-scoped quests before joining. */
        public Map<ResourceLocation, QuestProgress> getBroughtQuests() {
            return broughtQuests;
        }

        /** The party's quests before the newcomer joined. */
        public Map<ResourceLocation, QuestProgress> getPartyQuestsBefore() {
            return partyQuestsBefore;
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
