package dev.dzenthai.storybound.network;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dialogue.ClientDialogue;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.module.dialogue.CameraCue;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The options of a choice, in display order; the client answers with an index into this list.
 * Hidden options are never sent. Shown on the dialogue screen; in subtitles and bubbles the player opens it first.
 *
 * @param flags        {@link #closable()} and {@link #asks()}
 * @param timeoutTicks 0 when the choice has no timer
 * @param camera       a choice is framed on the player, over the partner's shoulder
 */
public record DialogueChoicesPayload(int session, int step, int flags, List<Shown> options, int timeoutTicks,
                                     CameraCue camera) implements CustomPacketPayload {
    private static final int CLOSABLE = 1;
    private static final int ASKS = 2;

    public DialogueChoicesPayload(int session, int step, boolean closable, List<Shown> options, int timeoutTicks, CameraCue camera) {
        this(session, step, flags(closable, false), options, timeoutTicks, camera);
    }

    public static int flags(boolean closable, boolean asks) {
        return (closable ? CLOSABLE : 0) | (asks ? ASKS : 0);
    }

    /** ESC leaves the dialogue. */
    public boolean closable() {
        return (flags & CLOSABLE) != 0;
    }

    /**
     * Shown in subtitles or bubbles (November 6, 2026): the window waits for the talk key, a prompt "[H] Answer" until
     * then, and ESC in it goes back to the prompt.
     */
    public boolean asks() {
        return (flags & ASKS) != 0;
    }

    public static final Type<DialogueChoicesPayload> TYPE = new Type<>(Storybound.id("dialogue_choices"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DialogueChoicesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DialogueChoicesPayload::session,
            ByteBufCodecs.VAR_INT, DialogueChoicesPayload::step,
            ByteBufCodecs.VAR_INT, DialogueChoicesPayload::flags,
            Shown.STREAM_CODEC.apply(ByteBufCodecs.list()), DialogueChoicesPayload::options,
            ByteBufCodecs.VAR_INT, DialogueChoicesPayload::timeoutTicks,
            CameraCue.STREAM_CODEC, DialogueChoicesPayload::camera,
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
