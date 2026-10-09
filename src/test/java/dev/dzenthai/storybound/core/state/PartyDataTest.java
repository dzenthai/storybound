package dev.dzenthai.storybound.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PartyDataTest {
    private static final ResourceLocation QUEST_STAGE = ResourceLocation.fromNamespaceAndPath("example", "quest_stage");
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    private final PartyData data = new PartyData();

    private Optional<StoryValue> stage(UUID player) {
        return data.container(data.ownerOf(player)).get(QUEST_STAGE);
    }

    private void setSolo(UUID player, int value) {
        data.container(player).put(QUEST_STAGE, new StoryValue.Int(value));
    }

    @Test
    void leaderBringsSoloProgressIntoNewParty() {
        setSolo(ALICE, 3);

        Party party = data.create(ALICE, null);

        assertEquals(party.id(), data.ownerOf(ALICE));
        assertEquals(Optional.of(new StoryValue.Int(3)), stage(ALICE));
    }

    @Test
    void joinerSeesPartyProgressAndSoloIsFrozen() {
        setSolo(ALICE, 3);
        setSolo(BOB, 1);
        Party party = data.create(ALICE, null);

        data.join(party.id(), BOB);
        assertEquals(Optional.of(new StoryValue.Int(3)), stage(BOB));

        data.leave(BOB, false);
        assertEquals(BOB, data.ownerOf(BOB));
        assertEquals(Optional.of(new StoryValue.Int(1)), stage(BOB));
    }

    @Test
    void leaverKeepsCopyOfPartyProgress() {
        Party party = data.create(ALICE, null);
        data.join(party.id(), BOB);
        data.container(party.id()).put(QUEST_STAGE, new StoryValue.Int(7));

        data.leave(BOB, true);
        data.container(party.id()).put(QUEST_STAGE, new StoryValue.Int(8));

        assertEquals(Optional.of(new StoryValue.Int(7)), stage(BOB));
        assertEquals(Optional.of(new StoryValue.Int(8)), stage(ALICE));
    }

    @Test
    void oldestMemberBecomesLeaderWhenLeaderLeaves() {
        Party party = data.create(ALICE, null);
        data.join(party.id(), BOB);
        data.join(party.id(), CAROL);

        PartyData.LeaveResult result = data.leave(ALICE, true);

        assertFalse(result.disbanded());
        assertEquals(BOB, result.newLeader());
        assertEquals(BOB, party.leader());
        assertEquals(List.of(BOB, CAROL), List.copyOf(party.members()));
    }

    @Test
    void memberLeavingDoesNotChangeLeader() {
        Party party = data.create(ALICE, null);
        data.join(party.id(), BOB);

        assertNull(data.leave(BOB, true).newLeader());
    }

    @Test
    void lastMemberLeavingDisbandsParty() {
        Party party = data.create(ALICE, null);

        PartyData.LeaveResult result = data.leave(ALICE, true);

        assertTrue(result.disbanded());
        assertTrue(data.party(party.id()).isEmpty());
        assertEquals(ALICE, data.ownerOf(ALICE));
    }

    @Test
    void disbandGivesEveryMemberACopy() {
        Party party = data.create(ALICE, null);
        data.join(party.id(), BOB);
        data.container(party.id()).put(QUEST_STAGE, new StoryValue.Int(5));

        List<UUID> members = data.disband(party.id(), true);

        assertEquals(List.of(ALICE, BOB), members);
        assertEquals(Optional.of(new StoryValue.Int(5)), stage(ALICE));
        assertEquals(Optional.of(new StoryValue.Int(5)), stage(BOB));
        assertTrue(data.partyOf(ALICE).isEmpty());
    }

    @Test
    void invitesExpireAndAreSingleUse() {
        Party party = data.create(ALICE, null);
        data.addInvite(BOB, party.id(), 1_000);
        data.addInvite(CAROL, party.id(), 1_000);

        assertEquals(Optional.of(party.id()), data.takeInvite(BOB, 999));
        assertEquals(Optional.empty(), data.takeInvite(BOB, 999));
        assertEquals(Optional.empty(), data.takeInvite(CAROL, 1_000));
    }

    @Test
    void invitesToDisbandedPartyAreDropped() {
        Party party = data.create(ALICE, null);
        data.addInvite(BOB, party.id(), Long.MAX_VALUE);

        data.disband(party.id(), true);

        assertEquals(Optional.empty(), data.takeInvite(BOB, 0));
    }

    @Test
    void survivesSaveAndLoad() {
        setSolo(CAROL, 2);
        Party party = data.create(ALICE, "Wolf Pack");
        data.join(party.id(), BOB);
        data.container(party.id()).put(QUEST_STAGE, new StoryValue.Int(4));

        CompoundTag tag = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        PartyData loaded = PartyData.load(tag, RegistryAccess.EMPTY);

        Party loadedParty = loaded.partyOf(BOB).orElseThrow();
        assertEquals(party.id(), loadedParty.id());
        assertEquals(ALICE, loadedParty.leader());
        assertEquals(List.of(ALICE, BOB), List.copyOf(loadedParty.members()));
        assertEquals(Optional.of("Wolf Pack"), loadedParty.name());
        assertEquals(Optional.of(new StoryValue.Int(4)), loaded.container(loaded.ownerOf(BOB)).get(QUEST_STAGE));
        assertEquals(Optional.of(new StoryValue.Int(2)), loaded.container(CAROL).get(QUEST_STAGE));
    }
}
