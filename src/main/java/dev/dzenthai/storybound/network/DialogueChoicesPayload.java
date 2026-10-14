package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dialogue.ClientDialogue;
import dev.dzenthai.storybound.core.text.ResolvedText;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The options of a choice, in display order; the client answers with an index into this list.
 * Hidden options are never sent. Always shown on the dialogue screen, even in HUD dialogues.
 *
 * @param timeoutTicks 0 when the choice has no timer
 */
public record DialogueChoicesPayload(int session, int step, boolean closable, List<Shown> options, int timeoutTicks) implements CustomPacketPayload {
    public static final Type<DialogueChoicesPayload> TYPE = new Type<>(Storybound.id("dialogue_choices"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DialogueChoicesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DialogueChoicesPayload::session,
            ByteBufCodecs.VAR_INT, DialogueChoicesPayload::step,
            ByteBufCodecs.BOOL, DialogueChoicesPayload::closable,
            Shown.STREAM_CODEC.apply(ByteBufCodecs.list()), DialogueChoicesPayload::options,
            ByteBufCodecs.VAR_INT, DialogueChoicesPayload::timeoutTicks,
            DialogueChoicesPayload::new);

    @Override
    public Type<DialogueChoicesPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(DialogueChoicesPayload payload, IPayloadContext context) {
        ClientDialogue.onChoices(payload);
    }

    /** @param enabled false for a {@code locked: show} option whose conditions fail; {@code hint} says why */
    public record Shown(ResolvedText text, boolean enabled, Optional<ResolvedText> hint) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Shown> STREAM_CODEC = StreamCodec.composite(
                ResolvedText.STREAM_CODEC, Shown::text,
                ByteBufCodecs.BOOL, Shown::enabled,
                ByteBufCodecs.optional(ResolvedText.STREAM_CODEC), Shown::hint,
                Shown::new);
    }
}
