package dev.dzenthai.storybound.core.state;

import com.mojang.authlib.GameProfile;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.event.PartyEvent;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * Player-facing party actions: permission checks, messages, events and state resync. Every change sends each player
 * online what the party tab shows ({@link PartySync}).
 */
public final class PartyManager {
    private static final long INVITE_TTL_MILLIS = 5 * 60 * 1000L;
    /** A party's own name, at most this long. */
    public static final int MAX_NAME = 24;

    private PartyManager() {}

    public static Party create(ServerPlayer leader, @Nullable String name) throws StoryException {
        PartyData data = PartyData.get(leader.server);
        if (data.partyOf(leader.getUUID()).isPresent()) {
            throw error("storybound.party.error.already_in_party");
        }
        Party party = data.create(leader.getUUID(), cleanName(name));
        NeoForge.EVENT_BUS.post(new PartyEvent.Created(leader.server, party.id(), leader.getUUID()));
        StateSync.sendFull(leader);
        leader.sendSystemMessage(Component.translatable("storybound.party.created", displayName(leader.server, party)));
        PartySync.sendAll(leader.server);
        return party;
    }

    public static void invite(ServerPlayer inviter, ServerPlayer target) throws StoryException {
        PartyData data = PartyData.get(inviter.server);
        Party party = requireLeader(data, inviter);
        if (inviter.getUUID().equals(target.getUUID())) {
            throw error("storybound.party.error.self");
        }
        if (data.partyOf(target.getUUID()).isPresent()) {
            throw new StoryException(Component.translatable("storybound.party.error.target_in_party", target.getDisplayName()));
        }
        data.addInvite(target.getUUID(), party.id(), System.currentTimeMillis() + INVITE_TTL_MILLIS);
        inviter.sendSystemMessage(Component.translatable("storybound.party.invited", target.getDisplayName()));
        // With [Accept] and [Decline] to click.
        target.sendSystemMessage(Component.translatable("storybound.party.invite_received", inviter.getDisplayName(), displayName(inviter.server, party))
                .append(" ").append(chatButton("storybound.party.button.accept", "/storybound party accept", ChatFormatting.GREEN))
                .append(" ").append(chatButton("storybound.party.button.decline", "/storybound party decline", ChatFormatting.RED)));
        PartySync.sendAll(inviter.server);
    }

    /** The party tab's "Invite": who is in no party yet creates one first, so one click is enough. */
    public static void inviteCreating(ServerPlayer inviter, ServerPlayer target) throws StoryException {
        if (PartyData.get(inviter.server).partyOf(inviter.getUUID()).isEmpty()) {
            create(inviter, null);
        }
        invite(inviter, target);
    }

    /** Turning an invite down: it goes, and the party's leader, when online, is told. */
    public static void decline(ServerPlayer player) throws StoryException {
        PartyData data = PartyData.get(player.server);
        UUID partyId = data.dropInvite(player.getUUID(), System.currentTimeMillis())
                .orElseThrow(() -> error("storybound.party.error.no_invite"));
        player.sendSystemMessage(Component.translatable("storybound.party.you_declined"));
        data.party(partyId).ifPresent(party -> {
            ServerPlayer leader = player.server.getPlayerList().getPlayer(party.leader());
            if (leader != null) {
                leader.sendSystemMessage(Component.translatable("storybound.party.declined", player.getDisplayName()));
            }
        });
        PartySync.sendAll(player.server);
    }

    /**
     * An action of the party tab ({@code create}, {@code invite}, {@code accept}, {@code decline}, {@code leave},
     * {@code kick}, {@code disband}); {@code target} names the player for invite and kick. The same checks as the
     * commands: a stale click (the player left, the invite ran out) is refused with its reason.
     */
    public static void act(ServerPlayer player, String action, @Nullable UUID target) throws StoryException {
        act(player, action, target, null);
    }

    /** As {@link #act(ServerPlayer, String, UUID)}, with the name typed for {@code create} (empty: the default name). */
    public static void act(ServerPlayer player, String action, @Nullable UUID target, @Nullable String name) throws StoryException {
        switch (action) {
            case "create" -> create(player, name);
            case "invite" -> {
                ServerPlayer online = target != null ? player.server.getPlayerList().getPlayer(target) : null;
                if (online == null) {
                    throw error("storybound.party.error.offline");
                }
                inviteCreating(player, online);
            }
            case "accept" -> accept(player);
            case "decline" -> decline(player);
            case "leave" -> leave(player);
            case "kick" -> {
                if (target == null) {
                    throw error("storybound.party.error.offline");
                }
                kick(player, new GameProfile(target, nameOf(player.server, target)));
            }
            case "disband" -> disband(player);
            default -> throw new StoryException(Component.literal("Unknown party action " + action));
        }
    }

    public static Party accept(ServerPlayer player) throws StoryException {
        PartyData data = PartyData.get(player.server);
        if (data.partyOf(player.getUUID()).isPresent()) {
            throw error("storybound.party.error.already_in_party");
        }
        UUID partyId = data.takeInvite(player.getUUID(), System.currentTimeMillis())
                .orElseThrow(() -> error("storybound.party.error.no_invite"));
        Party party = data.party(partyId).orElseThrow(() -> error("storybound.party.error.party_gone"));
        var brought = Map.copyOf(data.container(player.getUUID()).quests());
        var before = Map.copyOf(data.container(partyId).quests());
        data.join(partyId, player.getUUID());
        NeoForge.EVENT_BUS.post(new PartyEvent.Joined(player.server, partyId, player.getUUID(), brought, before));
        // The newcomer may have brought quests and flags: every member sees the party's state anew.
        for (UUID member : party.members()) {
            ServerPlayer online = player.server.getPlayerList().getPlayer(member);
            if (online != null) {
                StateSync.sendFull(online);
            }
        }
        broadcast(player.server, party, Component.translatable("storybound.party.joined", player.getDisplayName()));
        PartySync.sendAll(player.server);
        return party;
    }

    public static void leave(ServerPlayer player) throws StoryException {
        PartyData data = PartyData.get(player.server);
        Party party = data.partyOf(player.getUUID()).orElseThrow(() -> error("storybound.party.error.not_in_party"));
        removeMember(player.server, data, party, player.getUUID(), PartyEvent.Left.Reason.LEFT);
        player.sendSystemMessage(Component.translatable("storybound.party.you_left", displayName(player.server, party)));
        broadcast(player.server, party, Component.translatable("storybound.party.left", player.getDisplayName()));
        PartySync.sendAll(player.server);
    }

    public static void kick(ServerPlayer leader, GameProfile target) throws StoryException {
        PartyData data = PartyData.get(leader.server);
        Party party = requireLeader(data, leader);
        if (leader.getUUID().equals(target.getId())) {
            throw error("storybound.party.error.self");
        }
        if (!party.members().contains(target.getId())) {
            throw new StoryException(Component.translatable("storybound.party.error.not_member", target.getName()));
        }
        removeMember(leader.server, data, party, target.getId(), PartyEvent.Left.Reason.KICKED);
        ServerPlayer kicked = leader.server.getPlayerList().getPlayer(target.getId());
        if (kicked != null) {
            kicked.sendSystemMessage(Component.translatable("storybound.party.you_were_kicked", displayName(leader.server, party)));
        }
        broadcast(leader.server, party, Component.translatable("storybound.party.kicked", target.getName()));
        PartySync.sendAll(leader.server);
    }

    public static void disband(ServerPlayer leader) throws StoryException {
        PartyData data = PartyData.get(leader.server);
        Party party = requireLeader(data, leader);
        Component partyName = displayName(leader.server, party);
        for (UUID member : data.disband(party.id(), copyStateOnLeave())) {
            NeoForge.EVENT_BUS.post(new PartyEvent.Left(leader.server, party.id(), member, PartyEvent.Left.Reason.DISBANDED));
            ServerPlayer online = leader.server.getPlayerList().getPlayer(member);
            if (online != null) {
                StateSync.sendFull(online);
                online.sendSystemMessage(Component.translatable("storybound.party.disbanded", partyName));
            }
        }
        PartySync.sendAll(leader.server);
    }

    public static Component displayName(MinecraftServer server, Party party) {
        return party.name()
                .<Component>map(Component::literal)
                .orElseGet(() -> Component.translatable("storybound.party.default_name", nameOf(server, party.leader())));
    }

    /** Player name even when offline; the UUID if the server has never seen them. */
    public static String nameOf(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        GameProfileCache cache = server.getProfileCache();
        return cache != null ? cache.get(player).map(GameProfile::getName).orElse(player.toString()) : player.toString();
    }

    private static void removeMember(MinecraftServer server, PartyData data, Party party, UUID member, PartyEvent.Left.Reason reason) {
        PartyData.LeaveResult result = data.leave(member, copyStateOnLeave());
        NeoForge.EVENT_BUS.post(new PartyEvent.Left(server, party.id(), member, reason));
        ServerPlayer online = server.getPlayerList().getPlayer(member);
        if (online != null) {
            StateSync.sendFull(online);
        }
        if (result.newLeader() != null) {
            broadcast(server, party, Component.translatable("storybound.party.new_leader", nameOf(server, result.newLeader())));
        }
    }

    private static Party requireLeader(PartyData data, ServerPlayer player) throws StoryException {
        Party party = data.partyOf(player.getUUID()).orElseThrow(() -> error("storybound.party.error.not_in_party"));
        if (!party.isLeader(player.getUUID())) {
            throw error("storybound.party.error.not_leader");
        }
        return party;
    }

    private static void broadcast(MinecraftServer server, Party party, Component message) {
        for (UUID member : party.members()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                online.sendSystemMessage(message);
            }
        }
    }

    /**
     * A party's name as typed, made safe for the chat: no formatting codes or control characters, the spaces around it
     * trimmed, at most {@link #MAX_NAME} characters; null (the default name) when nothing is left.
     */
    public static @Nullable String cleanName(@Nullable String name) {
        if (name == null) {
            return null;
        }
        // A formatting code is "\u00A7" and the letter after it: both go.
        String clean = name.replaceAll("\u00A7.?", "").replaceAll("\\p{Cntrl}", "").strip();
        if (clean.length() > MAX_NAME) {
            clean = clean.substring(0, MAX_NAME).strip();
        }
        return clean.isEmpty() ? null : clean;
    }

    /** A word in brackets in the chat that runs a command when clicked, the command shown on hover. */
    private static MutableComponent chatButton(String key, String command, ChatFormatting color) {
        return Component.literal("[").append(Component.translatable(key)).append("]").withStyle(style -> style.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    private static boolean copyStateOnLeave() {
        return StoryboundConfig.COMMON.copyPartyStateOnLeave.get();
    }

    private static StoryException error(String key) {
        return new StoryException(Component.translatable(key));
    }
}
