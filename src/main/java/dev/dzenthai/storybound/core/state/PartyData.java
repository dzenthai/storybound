package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Parties and their {@code party} scopes. A player without a party lives in a "solo party" whose id
 * equals their UUID, so the {@code party} scope is always defined. Permission checks and player
 * messages live in {@link PartyManager}; this class holds only state and progress-copy rules.
 */
public final class PartyData extends SavedData {
    private static final String NAME = "storybound_parties";
    private static final Factory<PartyData> FACTORY = new Factory<>(PartyData::new, PartyData::load, null);
    private static final Codec<Snapshot> SNAPSHOT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Party.CODEC.listOf().fieldOf("parties").forGetter(Snapshot::parties),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, StoryContainer.CODEC).fieldOf("containers").forGetter(Snapshot::containers)
    ).apply(instance, Snapshot::new));

    private final Map<UUID, Party> parties = new LinkedHashMap<>();
    private final Map<UUID, UUID> partyByMember = new HashMap<>();
    private final Map<UUID, StoryContainer> containers = new HashMap<>();
    // Invites are not persisted: after a server restart they have to be sent again.
    private final Map<UUID, Invite> invites = new HashMap<>();

    PartyData() {}

    public static PartyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public Optional<Party> partyOf(UUID player) {
        return Optional.ofNullable(partyByMember.get(player)).map(parties::get);
    }

    public Optional<Party> party(UUID partyId) {
        return Optional.ofNullable(parties.get(partyId));
    }

    /** Owner of the player's {@code party} scope: their party id or their own UUID. */
    public UUID ownerOf(UUID player) {
        return partyByMember.getOrDefault(player, player);
    }

    StoryContainer container(UUID owner) {
        return containers.computeIfAbsent(owner, key -> track(new StoryContainer()));
    }

    /** Every saved party scope (and solo players' party scope), for migrations. */
    Collection<StoryContainer> containers() {
        return List.copyOf(containers.values());
    }

    /** The leader brings their solo progress into the new party. */
    Party create(UUID leader, @Nullable String name) {
        requireNotInParty(leader);
        Party party = new Party(UUID.randomUUID(), leader, List.of(leader), name);
        parties.put(party.id(), party);
        partyByMember.put(leader, party.id());
        container(party.id()).copyFrom(container(leader));
        setDirty();
        return party;
    }

    /**
     * The newcomer brings in their own party scope: of each quest the further progress (the party goes on from the
     * furthest point; who was behind skips ahead, without the rewards of what they skipped), and the flags the party
     * has not set (the party's own stay). Their own stays where it was, out of sight while they are in the party
     * (October 28, 2026; before, all of it was frozen, and a quest half done alone started from nothing; the same day,
     * at first only what the party lacked came, so a newcomer who had finished a quest the party had just begun went
     * back to its beginning).
     */
    void join(UUID partyId, UUID player) {
        requireNotInParty(player);
        Party party = parties.get(partyId);
        if (party == null) {
            throw new IllegalStateException("Unknown party " + partyId);
        }
        container(partyId).bringIn(container(player));
        party.addMember(player);
        partyByMember.put(player, partyId);
        setDirty();
    }

    /**
     * With {@code copyState} the party's progress is laid over the leaver's own (what the party did wins, what it never
     * touched stays as the leaver had it), otherwise they return to their own as it was. The last member leaving
     * disbands the party.
     */
    LeaveResult leave(UUID player, boolean copyState) {
        Party party = partyOf(player).orElseThrow(() -> new IllegalStateException(player + " is not in a party"));
        if (copyState) {
            container(player).layOver(container(party.id()));
        }
        UUID oldLeader = party.leader();
        party.removeMember(player);
        partyByMember.remove(player);
        boolean disbanded = party.members().isEmpty();
        if (disbanded) {
            remove(party);
        }
        setDirty();
        UUID newLeader = !disbanded && !oldLeader.equals(party.leader()) ? party.leader() : null;
        return new LeaveResult(party, disbanded, newLeader);
    }

    /** Every member gets a copy of the progress, as when leaving. Returns the former members. */
    List<UUID> disband(UUID partyId, boolean copyState) {
        Party party = parties.get(partyId);
        if (party == null) {
            return List.of();
        }
        List<UUID> members = List.copyOf(party.members());
        for (UUID member : members) {
            if (copyState) {
                container(member).layOver(container(partyId));
            }
            partyByMember.remove(member);
        }
        remove(party);
        setDirty();
        return members;
    }

    void addInvite(UUID target, UUID partyId, long expiresAtMillis) {
        invites.put(target, new Invite(partyId, expiresAtMillis));
    }

    Optional<UUID> takeInvite(UUID target, long nowMillis) {
        Invite invite = invites.remove(target);
        return invite != null && invite.expiresAtMillis() > nowMillis ? Optional.of(invite.partyId()) : Optional.empty();
    }

    private void remove(Party party) {
        parties.remove(party.id());
        containers.remove(party.id());
        invites.values().removeIf(invite -> invite.partyId().equals(party.id()));
    }

    private void requireNotInParty(UUID player) {
        if (partyByMember.containsKey(player)) {
            throw new IllegalStateException(player + " is already in a party");
        }
    }

    private StoryContainer track(StoryContainer container) {
        container.setOnChange(this::setDirty);
        return container;
    }

    static PartyData load(CompoundTag tag, HolderLookup.Provider registries) {
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        PartyData data = new PartyData();
        SNAPSHOT_CODEC.parse(ops, tag.get("data"))
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to load parties: {}", error))
                .ifPresent(snapshot -> {
                    for (Party party : snapshot.parties()) {
                        data.parties.put(party.id(), party);
                        party.members().forEach(member -> data.partyByMember.put(member, party.id()));
                    }
                    snapshot.containers().forEach((owner, container) -> data.containers.put(owner, data.track(container)));
                });
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Map<UUID, StoryContainer> nonEmpty = new HashMap<>();
        containers.forEach((owner, container) -> {
            if (!container.isEmpty()) {
                nonEmpty.put(owner, container);
            }
        });
        SNAPSHOT_CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), new Snapshot(List.copyOf(parties.values()), nonEmpty))
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to save parties: {}", error))
                .ifPresent(data -> tag.put("data", data));
        return tag;
    }

    /** {@code newLeader} is non-null if the leader left and the party still exists. */
    record LeaveResult(Party party, boolean disbanded, @Nullable UUID newLeader) {}

    private record Invite(UUID partyId, long expiresAtMillis) {}

    private record Snapshot(List<Party> parties, Map<UUID, StoryContainer> containers) {}
}
