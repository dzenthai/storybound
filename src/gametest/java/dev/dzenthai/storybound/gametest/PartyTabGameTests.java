package dev.dzenthai.storybound.gametest;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.state.PartyData;
import dev.dzenthai.storybound.core.state.PartyManager;
import dev.dzenthai.storybound.core.state.PartySync;
import dev.dzenthai.storybound.network.PartySyncPayload;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The party tab's actions and what it shows (November 19, 2026): the same rules as the commands, reached through
 * {@link PartyManager#act}, and each player's view as {@link PartySync} sends it. Other tests' mock players may be
 * online at the same time, so the lists are checked for these players, never counted.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PartyTabGameTests {
    private PartyTabGameTests() {}

    /** "Invite" from one in no party makes a party for them first; the invite shows on both sides. */
    @GameTest(template = "empty")
    public static void inviteFromNoPartyCreatesOneAndShowsOnBothSides(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer guest = helper.makeMockServerPlayerInLevel();
        ok(() -> PartyManager.act(leader, "invite", guest.getUUID()));
        helper.assertTrue(PartyData.get(leader.server).partyOf(leader.getUUID()).isPresent(), "the inviter now leads a party");
        PartySyncPayload leaderView = PartySync.snapshot(leader, null);
        helper.assertTrue(leaderView.party().map(info -> info.leader().equals(leader.getUUID())).orElse(false), "the leader's tab shows their party");
        helper.assertTrue(player(leaderView, guest.getUUID()).map(PartySyncPayload.Player::invited).orElse(false), "the guest shows as invited");
        PartySyncPayload guestView = PartySync.snapshot(guest, null);
        helper.assertTrue(guestView.invite().isPresent(), "the guest's tab shows the invite");
        helper.assertTrue(guestView.invite().get().remainingMillis() > 0, "the invite still holds");
        fails(helper, () -> PartyManager.act(leader, "invite", guest.getUUID()), "the guest is in no party yet, but invited again is fine")
                .ifPresent(message -> helper.fail("a second invite to the same player is refused: " + message));
        ok(() -> PartyManager.act(guest, "accept", null));
        PartySyncPayload joined = PartySync.snapshot(guest, null);
        helper.assertTrue(joined.party().map(info -> info.members().stream().anyMatch(member -> member.id().equals(guest.getUUID()))).orElse(false),
                "the guest is a member");
        helper.assertTrue(joined.invite().isEmpty(), "no invite once in the party");
        helper.assertTrue(player(PartySync.snapshot(leader, null), guest.getUUID()).isEmpty(), "a member is no longer among the others");
        ok(() -> PartyManager.act(leader, "disband", null));
        remove(helper, leader, guest);
        helper.succeed();
    }

    /** Declining removes the invite: no accepting it afterwards, no declining twice; the inviter sees no "invited". */
    @GameTest(template = "empty")
    public static void declineRemovesTheInvite(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer guest = helper.makeMockServerPlayerInLevel();
        ok(() -> PartyManager.act(leader, "create", null));
        ok(() -> PartyManager.act(leader, "invite", guest.getUUID()));
        ok(() -> PartyManager.act(guest, "decline", null));
        helper.assertTrue(PartySync.snapshot(guest, null).invite().isEmpty(), "the invite is gone");
        helper.assertTrue(!player(PartySync.snapshot(leader, null), guest.getUUID()).map(PartySyncPayload.Player::invited).orElse(true),
                "the leader no longer sees them invited");
        helper.assertTrue(fails(helper, () -> PartyManager.act(guest, "accept", null), "accept after decline").isPresent(), "accept after decline is refused");
        helper.assertTrue(fails(helper, () -> PartyManager.act(guest, "decline", null), "decline twice").isPresent(), "declining twice is refused");
        ok(() -> PartyManager.act(leader, "disband", null));
        remove(helper, leader, guest);
        helper.succeed();
    }

    /**
     * Only the leader removes a member; one gone from the server is shown offline and can be removed; a stale invite
     * of a player who left the server is refused; a member's tab shows the party, not the invite.
     */
    @GameTest(template = "empty")
    public static void leaderRemovesMembersAndOfflineShowsAsSuch(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ServerPlayer other = helper.makeMockServerPlayerInLevel();
        ok(() -> PartyManager.act(leader, "invite", member.getUUID()));
        ok(() -> PartyManager.act(member, "accept", null));
        helper.assertTrue(fails(helper, () -> PartyManager.act(member, "kick", leader.getUUID()), "a member kicking").isPresent(), "a member may not remove");
        helper.assertTrue(fails(helper, () -> PartyManager.act(member, "invite", other.getUUID()), "a member inviting").isPresent(), "a member may not invite");
        UUID memberId = member.getUUID();
        helper.getLevel().getServer().getPlayerList().remove(member);
        PartySyncPayload view = PartySync.snapshot(leader, null);
        helper.assertTrue(view.party().map(info -> info.members().stream().anyMatch(m -> m.id().equals(memberId) && !m.online())).orElse(false),
                "the member gone from the server shows offline");
        ok(() -> PartyManager.act(leader, "kick", memberId));
        helper.assertTrue(PartyData.get(leader.server).partyOf(memberId).isEmpty(), "the offline member is removed");
        helper.assertTrue(fails(helper, () -> PartyManager.act(leader, "invite", memberId), "inviting one not online").isPresent(),
                "an invite to one not online is refused");
        ok(() -> PartyManager.act(leader, "disband", null));
        remove(helper, leader, other);
        helper.succeed();
    }

    /**
     * The name typed above "Create party": formatting codes and the spaces around it go, a long one is cut to
     * {@link PartyManager#MAX_NAME}; only spaces give the default name ("<leader>'s party", an empty name in the view).
     */
    @GameTest(template = "empty")
    public static void createdPartyTakesTheTypedNameMadeSafe(GameTestHelper helper) {
        ServerPlayer leader = helper.makeMockServerPlayerInLevel();
        ok(() -> PartyManager.act(leader, "create", null, "  §cWolves of the North and the Far Hills  "));
        String name = PartySync.snapshot(leader, null).party().map(PartySyncPayload.Info::name).orElse("?");
        helper.assertTrue(!name.contains("§"), "no formatting code in " + name);
        helper.assertTrue(name.startsWith("Wolves of the North"), "the name kept: " + name);
        helper.assertTrue(name.length() <= PartyManager.MAX_NAME && name.equals(name.strip()), "cut and trimmed: '" + name + "'");
        ok(() -> PartyManager.act(leader, "disband", null));
        ok(() -> PartyManager.act(leader, "create", null, "   "));
        helper.assertTrue(PartySync.snapshot(leader, null).party().map(PartySyncPayload.Info::name).orElse("?").isEmpty(),
                "only spaces: the default name");
        ok(() -> PartyManager.act(leader, "disband", null));
        remove(helper, leader);
        helper.succeed();
    }

    private static Optional<PartySyncPayload.Player> player(PartySyncPayload view, UUID id) {
        return view.players().stream().filter(player -> player.id().equals(id)).findFirst();
    }

    private static void ok(Action action) {
        try {
            action.run();
        } catch (StoryException e) {
            throw new GameTestAssertException("Unexpected refusal: " + e.getMessage());
        }
    }

    /** The refusal's message, or empty when the action went through. */
    private static Optional<String> fails(GameTestHelper helper, Action action, String what) {
        try {
            action.run();
            return Optional.empty();
        } catch (StoryException refused) {
            return Optional.of(what + ": " + refused.getMessage());
        }
    }

    private static void remove(GameTestHelper helper, ServerPlayer... players) {
        for (ServerPlayer player : players) {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws StoryException;
    }
}
