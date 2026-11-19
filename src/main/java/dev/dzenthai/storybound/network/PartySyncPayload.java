package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientParty;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * What the party tab shows (November 19, 2026): the player's party with its members, the invite waiting for them, and
 * the other players online. Sent to everyone online whenever a party changes and when someone joins or leaves the
 * server; replaces what the client had.
 */
public record PartySyncPayload(Optional<Info> party, Optional<Invite> invite, List<Player> players) implements CustomPacketPayload {
    public static final Type<PartySyncPayload> TYPE = new Type<>(Storybound.id("sync_party"));

    /** A member; {@code online} while on the server. */
    public record Member(UUID id, String name, boolean online) {
        static final StreamCodec<ByteBuf, Member> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Member::id,
                ByteBufCodecs.STRING_UTF8, Member::name,
                ByteBufCodecs.BOOL, Member::online,
                Member::new);
    }

    /** The player's party: its own name, empty when it has none (the client says "<leader>'s party"), in join order. */
    public record Info(UUID id, String name, UUID leader, List<Member> members) {
        static final StreamCodec<ByteBuf, Info> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Info::id,
                ByteBufCodecs.STRING_UTF8, Info::name,
                UUIDUtil.STREAM_CODEC, Info::leader,
                Member.STREAM_CODEC.apply(ByteBufCodecs.list()), Info::members,
                Info::new);
    }

    /** The invite waiting for the player: the party's own name (or empty), its leader's name, how long it still holds. */
    public record Invite(String partyName, String leaderName, long remainingMillis) {
        static final StreamCodec<ByteBuf, Invite> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Invite::partyName,
                ByteBufCodecs.STRING_UTF8, Invite::leaderName,
                ByteBufCodecs.VAR_LONG, Invite::remainingMillis,
                Invite::new);
    }

    /** Another player online, not of the player's party: whether they are in a party, and whether it invited them. */
    public record Player(UUID id, String name, boolean inParty, boolean invited) {
        static final StreamCodec<ByteBuf, Player> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Player::id,
                ByteBufCodecs.STRING_UTF8, Player::name,
                ByteBufCodecs.BOOL, Player::inParty,
                ByteBufCodecs.BOOL, Player::invited,
                Player::new);
    }

    public static final StreamCodec<ByteBuf, PartySyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(Info.STREAM_CODEC), PartySyncPayload::party,
            ByteBufCodecs.optional(Invite.STREAM_CODEC), PartySyncPayload::invite,
            Player.STREAM_CODEC.apply(ByteBufCodecs.list()), PartySyncPayload::players,
            PartySyncPayload::new);

    @Override
    public Type<PartySyncPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(PartySyncPayload payload, IPayloadContext context) {
        ClientParty.apply(payload);
    }
}
