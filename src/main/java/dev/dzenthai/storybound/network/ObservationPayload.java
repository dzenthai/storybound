package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ObservationHud;
import dev.dzenthai.storybound.core.text.ResolvedText;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The observation bar under the crosshair (see {@code ObservationTracker}): what it says and how full it is, sent while
 * the player observes or has just looked away; without a label, hidden.
 *
 * @param progress 0 to 1
 * @param active   whether the player is looking at it now (looking away pauses it, shown faded)
 */
public record ObservationPayload(Optional<ResolvedText> label, float progress, boolean active) implements CustomPacketPayload {
    public static final Type<ObservationPayload> TYPE = new Type<>(Storybound.id("observation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ObservationPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(ResolvedText.STREAM_CODEC), ObservationPayload::label,
            ByteBufCodecs.FLOAT, ObservationPayload::progress,
            ByteBufCodecs.BOOL, ObservationPayload::active,
            ObservationPayload::new);

    @Override
    public Type<ObservationPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(ObservationPayload payload, IPayloadContext context) {
        ObservationHud.update(payload.label().map(ResolvedText::toComponent).orElse(null), payload.progress(), payload.active());
    }
}
