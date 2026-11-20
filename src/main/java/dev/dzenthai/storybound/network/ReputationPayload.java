package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ReputationHud;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * An NPC's relationship scale for the HUD: with {@code focus} false, it changed by {@code delta} to {@code value}
 * (shown a few seconds); with {@code focus} true, the player talks with that NPC now (shown all the while), or, with no
 * scale, the talk ended.
 */
public record ReputationPayload(Optional<ResourceLocation> scale, Component name, int value, int delta, boolean focus) implements CustomPacketPayload {
    public static final Type<ReputationPayload> TYPE = new Type<>(Storybound.id("reputation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ReputationPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), ReputationPayload::scale,
            ComponentSerialization.STREAM_CODEC, ReputationPayload::name,
            ByteBufCodecs.VAR_INT, ReputationPayload::value,
            ByteBufCodecs.VAR_INT, ReputationPayload::delta,
            ByteBufCodecs.BOOL, ReputationPayload::focus,
            ReputationPayload::new);

    @Override
    public Type<ReputationPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(ReputationPayload payload, IPayloadContext context) {
        ReputationHud.apply(payload);
    }
}
