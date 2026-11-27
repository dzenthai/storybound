package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.world.ClientWorldRules;
import dev.dzenthai.storybound.core.text.ResolvedText;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Active item locks currently enforced for this player: drives item tooltips, client-side crafting slot preview,
 * and optional JEI hiding. Sent only when the player's effective locked item set changes.
 */
public record WorldLocksPayload(List<Entry> locks) implements CustomPacketPayload {
    public static final byte FLAG_USE = 1 << 0;
    public static final byte FLAG_ATTACK = 1 << 1;
    public static final byte FLAG_EQUIP = 1 << 2;
    public static final byte FLAG_CRAFT = 1 << 3;
    public static final byte FLAG_PICKUP = 1 << 4;
    public static final byte FLAG_HIDE_JEI = 1 << 5;

    public static final Type<WorldLocksPayload> TYPE = new Type<>(Storybound.id("world_locks"));

    public record Entry(List<ResourceLocation> items, byte flags, Optional<ResolvedText> message) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), Entry::items,
                ByteBufCodecs.BYTE, Entry::flags,
                ByteBufCodecs.optional(ResolvedText.STREAM_CODEC), Entry::message,
                Entry::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, WorldLocksPayload> STREAM_CODEC = StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), WorldLocksPayload::locks,
            WorldLocksPayload::new);

    @Override
    public Type<WorldLocksPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(WorldLocksPayload payload, IPayloadContext context) {
        ClientWorldRules.apply(payload.locks());
    }
}

